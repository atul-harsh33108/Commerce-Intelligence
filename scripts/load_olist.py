#!/usr/bin/env python3
"""Load the Olist dataset into the platform's PostgreSQL service databases.

Repeatable and idempotent by construction: every insert is `ON CONFLICT (natural key) DO NOTHING`,
so a half-finished load is *resumed* rather than restarted, and running it twice is a no-op instead
of a duplicate-key crash. That property is not politeness - it is what makes the row counts in
docs/measurements.md reproducible from a clean volume, which is the Definition of Done for Step 2.

WHY THIS IS SQL AND NOT THE REST API
------------------------------------
The obvious alternative is to POST 100K orders through the gateway and let the saga run. It would be
slow (hours) and, more importantly, *wrong*: these are historical facts, not new business events.
Replaying them as live orders would reserve stock that was consumed two years ago, charge payments
that already settled, and emit 100K OrderPlaced events into Kafka for consumers that would treat
them as today's traffic. Seeding a database with history and placing an order are different
operations and should not share a path.

WHAT THE DATASET DOES NOT CONTAIN, AND WHAT IS DERIVED FROM WHAT
----------------------------------------------------------------
Olist is real anonymised commercial data, and real data has holes. Each one is filled explicitly
here rather than silently:

  product name    ABSENT. Derived as "<english category> #<first 8 of product_id>", deterministically,
                  in one place. It exists so a UI has something to render and is never authoritative.
  product price   ABSENT from the product table. Taken as the MEDIAN unit price the product actually
                  sold at across all order items - observed, not invented. A product that never sold
                  gets no price and is loaded `active = false`.
  stock level     ABSENT entirely; Olist has no inventory data. Seeded as the number of units the
                  product sold in the dataset, plus a floor of 20, i.e. "the warehouse restocked
                  exactly what it shipped". Arbitrary, and labelled arbitrary.
  order total     ABSENT as a column. Summed from the order's items (price + freight), which is the
                  only definition the data supports.

Usage:
    python scripts/load_olist.py --source data/olist          # the real Kaggle download
    python scripts/load_olist.py --source data/generated      # synthetic, same schema
    python scripts/load_olist.py --counts-only                # just report what is loaded
"""

from __future__ import annotations

import argparse
import csv
import io
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

try:
    import psycopg
except ImportError:  # pragma: no cover - a dependency message beats a traceback
    print("psycopg is required:  pip install 'psycopg[binary]'", file=sys.stderr)
    raise SystemExit(2)

sys.path.insert(0, str(Path(__file__).resolve().parent))

from olist_schema import (  # noqa: E402
    CATEGORY_TRANSLATION,
    CUSTOMERS,
    LOADED_SPECS,
    ORDER_ITEMS,
    ORDER_PAYMENTS,
    ORDERS,
    PRODUCTS,
)

# Host ports from docker-compose.yml. Overridable per-service through the environment for the
# containerised path, where the host is a service name and the port is always 5432.
# A connect_timeout on every DSN, deliberately. Without it a stopped database makes psycopg block on
# the TCP connect for the OS default (~2 minutes on Windows) with no output at all, which during
# development reads as "the loader has hung" rather than "that container is not running". Found the
# slow way.
TARGETS = {
    "catalog": "postgresql://catalogdb:catalogdb@localhost:5434/catalogdb?connect_timeout=5",
    "user": "postgresql://userdb:userdb@localhost:5433/userdb?connect_timeout=5",
    "inventory": "postgresql://inventorydb:inventorydb@localhost:5435/inventorydb?connect_timeout=5",
    "order": "postgresql://orderdb:orderdb@localhost:5436/orderdb?connect_timeout=5",
    "payment": "postgresql://paymentdb:paymentdb@localhost:5437/paymentdb?connect_timeout=5",
}

BATCH = 5_000


# -------------------------------------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------------------------------------

def read_csv(path: Path):
    """Stream a CSV as dicts, tolerating the UTF-8 BOM Excel leaves in Kaggle downloads."""
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        yield from csv.DictReader(f)


def table_exists(conn, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass(%s) IS NOT NULL", (f"public.{table}",))
        return cur.fetchone()[0]


def copy_rows(conn, table: str, columns: list[str], rows) -> int:
    """Bulk-insert through COPY into a staging table.

    COPY rather than executemany, and the difference is not marginal: 100K single-row INSERTs is
    ~100K round trips and a WAL record each. COPY streams one statement. Measured on this machine in
    Step 2 - see docs/measurements.md.
    """
    n = 0
    cols = ", ".join(columns)
    with conn.cursor() as cur:
        with cur.copy(f"COPY {table} ({cols}) FROM STDIN") as copy:
            for row in rows:
                copy.write_row(row)
                n += 1
    return n


def optional(value: str | None):
    """'' -> None. Olist writes empty strings where a real null belongs."""
    if value is None:
        return None
    value = value.strip()
    return value or None


def as_int(value: str | None):
    v = optional(value)
    if v is None:
        return None
    try:
        return int(float(v))
    except ValueError:
        return None


def as_float(value: str | None):
    v = optional(value)
    if v is None:
        return None
    try:
        return float(v)
    except ValueError:
        return None


def as_ts(value: str | None):
    return optional(value)


# -------------------------------------------------------------------------------------------------
# Derivations
# -------------------------------------------------------------------------------------------------

def derive_product_facts(source: Path):
    """Median unit price and units sold per product, from the order items.

    MEDIAN, not mean. Olist prices for one product vary by seller and by promotion, and a single
    outlier (one item sold at 10x during a listing error, which the real data contains) drags a mean
    far enough to make the catalog page look broken. The median is what a person would call "the
    price".
    """
    prices: dict[str, list[float]] = defaultdict(list)
    units: dict[str, int] = defaultdict(int)

    path = source / ORDER_ITEMS.filename
    for row in read_csv(path):
        pid = row["product_id"]
        price = as_float(row["price"])
        if price is not None:
            prices[pid].append(price)
        units[pid] += 1

    median_price = {pid: round(statistics.median(v), 2) for pid, v in prices.items()}
    return median_price, units


def derive_order_totals(source: Path):
    """Order total = sum(price + freight) over the order's items, and the line count."""
    totals: dict[str, float] = defaultdict(float)
    lines: dict[str, int] = defaultdict(int)
    for row in read_csv(source / ORDER_ITEMS.filename):
        oid = row["order_id"]
        totals[oid] += (as_float(row["price"]) or 0.0) + (as_float(row["freight_value"]) or 0.0)
        lines[oid] += 1
    return {k: round(v, 2) for k, v in totals.items()}, lines


def category_map(source: Path) -> dict[str, str]:
    return {
        row["product_category_name"]: row["product_category_name_english"]
        for row in read_csv(source / CATEGORY_TRANSLATION.filename)
    }


def display_name(english_category: str | None, product_id: str) -> str:
    """The one place a product name is invented, so it is reproducible and reversible."""
    label = english_category or "uncategorised"
    return f"{label} #{product_id[:8]}"


# -------------------------------------------------------------------------------------------------
# Per-service loaders. Each is a no-op if the service's table does not exist yet, so the loader can
# be written before every service is, and re-run as they arrive.
# -------------------------------------------------------------------------------------------------

def load_catalog(source: Path, dsn: str, report: dict):
    with psycopg.connect(dsn) as conn:
        if not table_exists(conn, "products"):
            report["catalog"] = "skipped: table `products` does not exist yet"
            return

        categories = category_map(source)
        median_price, units = derive_product_facts(source)

        conn.execute("""
            CREATE TEMP TABLE stage_products (
                product_key  TEXT,
                name         TEXT,
                category_key TEXT,
                category     TEXT,
                price        NUMERIC(12,2),
                weight_g     INTEGER,
                length_cm    INTEGER,
                height_cm    INTEGER,
                width_cm     INTEGER,
                photos_qty   INTEGER,
                active       BOOLEAN
            ) ON COMMIT DROP
        """)

        def rows():
            for row in read_csv(source / PRODUCTS.filename):
                pid = row["product_id"]
                cat_pt = optional(row["product_category_name"])
                cat_en = categories.get(cat_pt) if cat_pt else None
                price = median_price.get(pid)
                yield (
                    pid,
                    display_name(cat_en, pid),
                    cat_pt,
                    cat_en,
                    price,
                    as_int(row["product_weight_g"]),
                    as_int(row["product_length_cm"]),
                    as_int(row["product_height_cm"]),
                    as_int(row["product_width_cm"]),
                    as_int(row["product_photos_qty"]),
                    price is not None,
                )

        staged = copy_rows(conn, "stage_products", [
            "product_key", "name", "category_key", "category", "price",
            "weight_g", "length_cm", "height_cm", "width_cm", "photos_qty", "active"], rows())

        # ON CONFLICT DO NOTHING against uk_products_product_key. This is the idempotency guarantee,
        # and it lives in the schema (a unique constraint) rather than in this script - a loader that
        # checks "does it exist?" first has a race with itself and with anything else writing.
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO products (product_key, name, category_key, category, price,
                                      weight_g, length_cm, height_cm, width_cm, photos_qty,
                                      active, version, created_at, updated_at)
                SELECT product_key, name, category_key, category, price,
                       weight_g, length_cm, height_cm, width_cm, photos_qty,
                       active, 0, now(), now()
                  FROM stage_products
                ON CONFLICT (product_key) DO NOTHING
            """)
            inserted = cur.rowcount
        conn.commit()

        with conn.cursor() as cur:
            cur.execute("SELECT count(*), count(*) FILTER (WHERE active), count(DISTINCT category) FROM products")
            total, active, cats = cur.fetchone()
        report["catalog"] = {
            "staged": staged, "inserted": inserted, "already_present": staged - inserted,
            "products_total": total, "products_active": active, "distinct_categories": cats,
            "units_sold_tracked": len(units),
        }


def load_users(source: Path, dsn: str, report: dict):
    with psycopg.connect(dsn) as conn:
        if not table_exists(conn, "users"):
            report["user"] = "skipped: table `users` does not exist yet"
            return

        conn.execute("""
            CREATE TEMP TABLE stage_customers (
                customer_key TEXT, customer_unique_key TEXT,
                zip_prefix TEXT, city TEXT, state TEXT
            ) ON COMMIT DROP
        """)

        def rows():
            for row in read_csv(source / CUSTOMERS.filename):
                yield (row["customer_id"], row["customer_unique_id"],
                       optional(row["customer_zip_code_prefix"]),
                       optional(row["customer_city"]), optional(row["customer_state"]))

        staged = copy_rows(conn, "stage_customers",
                           ["customer_key", "customer_unique_key", "zip_prefix", "city", "state"],
                           rows())

        # Deterministic synthetic credentials, and this deserves saying out loud: Olist is anonymised
        # and contains no names, emails or passwords, so a "user" here is a customer key plus a
        # derived username. The password hash is one shared bcrypt of "olist" for EVERY seeded
        # account, which is fine for a dataset nobody can log into as a real person and would be a
        # catastrophe in anything real. Registered users created through /api/auth/register get a
        # per-user hash from the service, as normal.
        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO users (username, email, password, role, customer_key,
                                   customer_unique_key, city, state, zip_prefix,
                                   version, created_at, updated_at)
                SELECT 'olist_' || left(customer_key, 12),
                       'olist_' || left(customer_key, 12) || '@example.invalid',
                       '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
                       'USER', customer_key, customer_unique_key, city, state, zip_prefix,
                       0, now(), now()
                  FROM stage_customers
                ON CONFLICT (customer_key) DO NOTHING
            """)
            inserted = cur.rowcount
        conn.commit()

        with conn.cursor() as cur:
            cur.execute("SELECT count(*), count(DISTINCT customer_unique_key), count(DISTINCT state) FROM users WHERE customer_key IS NOT NULL")
            total, unique_people, states = cur.fetchone()
        report["user"] = {
            "staged": staged, "inserted": inserted, "already_present": staged - inserted,
            "seeded_users": total, "distinct_people": unique_people, "distinct_states": states,
        }


def load_inventory(source: Path, dsn: str, report: dict):
    with psycopg.connect(dsn) as conn:
        if not table_exists(conn, "stock_items"):
            report["inventory"] = "skipped: table `stock_items` does not exist yet"
            return

        _, units = derive_product_facts(source)

        conn.execute("""
            CREATE TEMP TABLE stage_stock (product_key TEXT, on_hand INTEGER) ON COMMIT DROP
        """)

        def rows():
            for row in read_csv(source / PRODUCTS.filename):
                pid = row["product_id"]
                # "The warehouse restocked exactly what it shipped", with a floor so that products
                # which never sold are still orderable in a demo. ARBITRARY: Olist has no inventory
                # data at all, and no derivation from it would be less arbitrary than this one.
                yield (pid, max(20, units.get(pid, 0)))

        staged = copy_rows(conn, "stage_stock", ["product_key", "on_hand"], rows())

        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO stock_items (product_key, quantity_on_hand, quantity_reserved,
                                         version, created_at, updated_at)
                SELECT product_key, on_hand, 0, 0, now(), now()
                  FROM stage_stock
                ON CONFLICT (product_key) DO NOTHING
            """)
            inserted = cur.rowcount
        conn.commit()

        with conn.cursor() as cur:
            cur.execute("SELECT count(*), coalesce(sum(quantity_on_hand), 0) FROM stock_items")
            total, on_hand = cur.fetchone()
        report["inventory"] = {
            "staged": staged, "inserted": inserted, "already_present": staged - inserted,
            "stock_items": total, "total_units_on_hand": int(on_hand),
        }


def load_orders(source: Path, dsn: str, report: dict):
    with psycopg.connect(dsn) as conn:
        if not table_exists(conn, "orders"):
            report["order"] = "skipped: table `orders` does not exist yet"
            return

        totals, lines = derive_order_totals(source)

        conn.execute("""
            CREATE TEMP TABLE stage_orders (
                order_key TEXT, customer_key TEXT, status TEXT, total NUMERIC(12,2),
                purchased_at TIMESTAMPTZ, approved_at TIMESTAMPTZ,
                carrier_at TIMESTAMPTZ, delivered_at TIMESTAMPTZ, estimated_at TIMESTAMPTZ
            ) ON COMMIT DROP
        """)

        def order_rows():
            for row in read_csv(source / ORDERS.filename):
                oid = row["order_id"]
                yield (oid, row["customer_id"],
                       # Olist statuses are lowercase; the platform's enum is upper. Mapped here, at
                       # the boundary, rather than storing the source's casing and remembering to
                       # fold it in every query.
                       (row["order_status"] or "").upper(),
                       totals.get(oid, 0.0),
                       as_ts(row["order_purchase_timestamp"]),
                       as_ts(row["order_approved_at"]),
                       as_ts(row["order_delivered_carrier_date"]),
                       as_ts(row["order_delivered_customer_date"]),
                       as_ts(row["order_estimated_delivery_date"]))

        staged_orders = copy_rows(conn, "stage_orders", [
            "order_key", "customer_key", "status", "total", "purchased_at", "approved_at",
            "carrier_at", "delivered_at", "estimated_at"], order_rows())

        with conn.cursor() as cur:
            cur.execute("""
                INSERT INTO orders (order_key, customer_key, status, total_amount,
                                    purchased_at, approved_at, delivered_carrier_at,
                                    delivered_customer_at, estimated_delivery_at,
                                    version, created_at, updated_at)
                SELECT order_key, customer_key, status, total,
                       purchased_at, approved_at, carrier_at, delivered_at, estimated_at,
                       0, coalesce(purchased_at, now()), coalesce(purchased_at, now())
                  FROM stage_orders
                ON CONFLICT (order_key) DO NOTHING
            """)
            inserted_orders = cur.rowcount
        conn.commit()

        # --- items ---
        conn.execute("""
            CREATE TEMP TABLE stage_items (
                order_key TEXT, line_no INTEGER, product_key TEXT, seller_key TEXT,
                unit_price NUMERIC(12,2), freight NUMERIC(12,2), shipping_limit_at TIMESTAMPTZ
            ) ON COMMIT DROP
        """)

        def item_rows():
            for row in read_csv(source / ORDER_ITEMS.filename):
                yield (row["order_id"], as_int(row["order_item_id"]), row["product_id"],
                       row["seller_id"], as_float(row["price"]), as_float(row["freight_value"]),
                       as_ts(row["shipping_limit_date"]))

        staged_items = copy_rows(conn, "stage_items", [
            "order_key", "line_no", "product_key", "seller_key",
            "unit_price", "freight", "shipping_limit_at"], item_rows())

        with conn.cursor() as cur:
            # The join to `orders` is what keeps the foreign key satisfiable: Olist's order_items
            # file contains a handful of order ids that are not in the orders file, and inserting
            # them would fail the FK on the last row of a 112K-row batch.
            cur.execute("""
                INSERT INTO order_items (order_id, line_no, product_key, seller_key,
                                         quantity, unit_price, freight_amount, shipping_limit_at)
                SELECT o.id, s.line_no, s.product_key, s.seller_key,
                       1, s.unit_price, s.freight, s.shipping_limit_at
                  FROM stage_items s
                  JOIN orders o ON o.order_key = s.order_key
                ON CONFLICT (order_id, line_no) DO NOTHING
            """)
            inserted_items = cur.rowcount
        conn.commit()

        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM orders")
            total_orders = cur.fetchone()[0]
            cur.execute("SELECT count(*) FROM order_items")
            total_items = cur.fetchone()[0]
            cur.execute("SELECT status, count(*) FROM orders GROUP BY status ORDER BY 2 DESC")
            by_status = dict(cur.fetchall())
            cur.execute("SELECT min(purchased_at)::date, max(purchased_at)::date FROM orders")
            first, last = cur.fetchone()

        report["order"] = {
            "staged_orders": staged_orders, "inserted_orders": inserted_orders,
            "staged_items": staged_items, "inserted_items": inserted_items,
            "orphan_items_skipped": staged_items - inserted_items,
            "orders_total": total_orders, "order_items_total": total_items,
            "by_status": by_status,
            "date_range": f"{first} .. {last}",
        }


def load_payments(source: Path, dsn: str, report: dict):
    with psycopg.connect(dsn) as conn:
        if not table_exists(conn, "payments"):
            report["payment"] = "skipped: table `payments` does not exist yet"
            return

        conn.execute("""
            CREATE TEMP TABLE stage_payments (
                order_key TEXT, sequential INTEGER, method TEXT,
                installments INTEGER, amount NUMERIC(12,2)
            ) ON COMMIT DROP
        """)

        def rows():
            for row in read_csv(source / ORDER_PAYMENTS.filename):
                yield (row["order_id"], as_int(row["payment_sequential"]),
                       (row["payment_type"] or "").upper(),
                       as_int(row["payment_installments"]), as_float(row["payment_value"]))

        staged = copy_rows(conn, "stage_payments",
                           ["order_key", "sequential", "method", "installments", "amount"], rows())

        with conn.cursor() as cur:
            # Historical payments are loaded as CAPTURED with the order key as the idempotency key -
            # which is the same natural key the live saga uses (D10), so a replayed PaymentRequested
            # for a historical order is refused by the same unique constraint rather than charging
            # twice.
            cur.execute("""
                INSERT INTO payments (order_key, payment_sequential, method, installments,
                                      amount, status, idempotency_key, version, created_at, updated_at)
                SELECT order_key, sequential, method, installments, amount, 'CAPTURED',
                       order_key || ':' || sequential, 0, now(), now()
                  FROM stage_payments
                ON CONFLICT (idempotency_key) DO NOTHING
            """)
            inserted = cur.rowcount
        conn.commit()

        with conn.cursor() as cur:
            cur.execute("SELECT count(*), coalesce(sum(amount),0) FROM payments")
            total, amount = cur.fetchone()
            cur.execute("SELECT method, count(*) FROM payments GROUP BY method ORDER BY 2 DESC")
            by_method = dict(cur.fetchall())
        report["payment"] = {
            "staged": staged, "inserted": inserted, "already_present": staged - inserted,
            "payments_total": total, "total_value": float(amount), "by_method": by_method,
        }


LOADERS = {
    "catalog": load_catalog,
    "user": load_users,
    "inventory": load_inventory,
    "order": load_orders,
    "payment": load_payments,
}


# -------------------------------------------------------------------------------------------------

def verify_source(source: Path) -> list[str]:
    problems = []
    for spec in LOADED_SPECS:
        path = source / spec.filename
        if not path.exists():
            problems.append(f"missing: {spec.filename}")
            continue
        with path.open("r", encoding="utf-8-sig", newline="") as f:
            header = next(csv.reader(f), [])
        missing = [c for c in spec.columns if c not in header]
        if missing:
            problems.append(f"{spec.filename}: missing columns {missing}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    root = Path(__file__).resolve().parent.parent
    parser.add_argument("--source", type=Path, default=root / "data" / "olist",
                        help="directory holding the nine Olist CSVs")
    parser.add_argument("--only", nargs="*", choices=sorted(LOADERS),
                        help="load only these services")
    parser.add_argument("--counts-only", action="store_true",
                        help="report what is currently loaded and exit")
    args = parser.parse_args()

    if args.counts_only:
        for name, dsn in TARGETS.items():
            try:
                with psycopg.connect(dsn, connect_timeout=3) as conn, conn.cursor() as cur:
                    cur.execute("""
                        SELECT relname, n_live_tup FROM pg_stat_user_tables
                         WHERE n_live_tup > 0 ORDER BY relname
                    """)
                    rows = cur.fetchall()
                print(f"{name}: " + (", ".join(f"{t}={n:,}" for t, n in rows) or "(empty)"))
            except Exception as exc:  # noqa: BLE001 - a connection failure is the answer here
                print(f"{name}: unreachable ({exc.__class__.__name__})")
        return 0

    source: Path = args.source
    if not source.exists():
        print(f"No such directory: {source}\n", file=sys.stderr)
        print("The Olist CSVs are a Kaggle download and are not in this repository.", file=sys.stderr)
        print("See docs/data-setup.md - it names the nine files and where to put them.", file=sys.stderr)
        print("To exercise the pipeline without them:", file=sys.stderr)
        print("    python scripts/generate_synthetic_olist.py", file=sys.stderr)
        print("    python scripts/load_olist.py --source data/generated", file=sys.stderr)
        return 1

    problems = verify_source(source)
    if problems:
        print(f"{source} does not look like the Olist dataset:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1

    synthetic = source.name == "generated"
    print(f"Source: {source}  ({'SYNTHETIC' if synthetic else 'real Olist download'})")

    report: dict = {}
    targets = args.only or list(LOADERS)
    for name in targets:
        started = time.perf_counter()
        try:
            LOADERS[name](source, TARGETS[name], report)
        except psycopg.OperationalError as exc:
            report[name] = f"unreachable: {exc.__class__.__name__} - is docker compose up?"
            continue
        elapsed = time.perf_counter() - started
        if isinstance(report.get(name), dict):
            report[name]["seconds"] = round(elapsed, 2)

    print()
    for name in targets:
        value = report.get(name, "(not attempted)")
        if isinstance(value, dict):
            print(f"{name}:")
            for k, v in value.items():
                print(f"    {k:<22} {v:,}" if isinstance(v, int) else f"    {k:<22} {v}")
        else:
            print(f"{name}: {value}")

    if synthetic:
        print()
        print("SYNTHETIC SOURCE. Any number derived from this load must be labelled synthetic.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
