#!/usr/bin/env python3
"""Generate CSVs with the Olist schema, for when the real download is not present.

WHY THIS EXISTS, AND WHAT IT IS NOT
-----------------------------------
The real dataset is a 45 MB Kaggle download that needs an account, so it cannot be committed here or
fetched non-interactively. Everything downstream of ingestion - the saga, the cache measurements, the
search index, the CDC pipeline, the warehouse, the agent - needs *some* data at *some* volume to be
demonstrated at all. Without this script the honest options were "demonstrate nothing until the
download happens" or "hand-wave the numbers", and both are worse.

**Nothing produced by this script is a claim about the real dataset.** Every table it writes is
tagged in `docs/measurements.md` as SYNTHETIC, and every number measured against it says so. The
loader is byte-identical for both sources - same schema module, same COPY path, same idempotency -
so re-running against the real CSVs is one command and replaces the numbers rather than the code.

What is real here, and what is fabricated:

  real       the 71 category names and their English translations (from the published
             translation file), the 27 Brazilian states, the order-status and payment-type
             vocabularies and their approximate proportions, the column names and their
             misspellings, the (order_id, order_item_id) natural key, the fact that a product
             has no name and no price
  fabricated every id, every timestamp, every price, every customer, every join

Deterministic by default (`--seed 42`), so two runs produce the same files and a measurement is
reproducible.
"""

from __future__ import annotations

import argparse
import csv
import random
import sys
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from olist_schema import (  # noqa: E402
    BR_STATES,
    CATEGORY_TRANSLATIONS,
    CUSTOMERS,
    CATEGORY_TRANSLATION,
    ORDER_ITEMS,
    ORDER_PAYMENTS,
    ORDER_STATUSES,
    ORDERS,
    PAYMENT_TYPES,
    PRODUCTS,
    SELLERS,
)

HEX = "0123456789abcdef"


def olist_id(rng: random.Random) -> str:
    """A 32-character lowercase hex id, which is what every Olist key looks like."""
    return "".join(rng.choice(HEX) for _ in range(32))


def weighted(rng: random.Random, pairs):
    population = [p[0] for p in pairs]
    weights = [p[-1] for p in pairs]
    return rng.choices(population, weights=weights, k=1)[0]


def weighted_state(rng: random.Random):
    idx = rng.choices(range(len(BR_STATES)), weights=[s[2] for s in BR_STATES], k=1)[0]
    return BR_STATES[idx]


def generate(out_dir: Path, orders_count: int, seed: int) -> dict[str, int]:
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Ratios taken from the published dataset, so the shape of the data is right even though the
    # content is not: ~3 products per 10 orders, ~1 seller per 32 orders, 1.13 items per order.
    product_count = max(50, round(orders_count * 0.331))
    seller_count = max(10, round(orders_count * 0.031))

    counts: dict[str, int] = {}

    # --- categories -----------------------------------------------------------------------------
    path = out_dir / CATEGORY_TRANSLATION.filename
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(CATEGORY_TRANSLATION.columns)
        for pt, en in CATEGORY_TRANSLATIONS.items():
            w.writerow([pt, en])
    counts[CATEGORY_TRANSLATION.filename] = len(CATEGORY_TRANSLATIONS)

    # --- products -------------------------------------------------------------------------------
    # Category popularity is heavily skewed in the real data (health_beauty and bed_bath_table
    # dominate). A uniform draw would make every "top category" question a tie, so the weights decay
    # geometrically over a shuffled category order.
    categories = list(CATEGORY_TRANSLATIONS.keys())
    rng.shuffle(categories)
    category_weights = [0.94 ** i for i in range(len(categories))]

    product_ids: list[str] = []
    path = out_dir / PRODUCTS.filename
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(PRODUCTS.columns)
        for _ in range(product_count):
            pid = olist_id(rng)
            product_ids.append(pid)
            category = rng.choices(categories, weights=category_weights, k=1)[0]
            # ~1.9% of real products have a null category. Kept, because a loader that has never
            # seen a null in this column will fail on the real file.
            if rng.random() < 0.019:
                category = ""
            w.writerow([
                pid,
                category,
                rng.randint(20, 76),      # product_name_lenght  (sic)
                rng.randint(100, 3990),   # product_description_lenght (sic)
                rng.randint(1, 6),
                rng.randint(50, 30_000),  # weight in grams
                rng.randint(7, 105),
                rng.randint(2, 105),
                rng.randint(6, 118),
            ])
    counts[PRODUCTS.filename] = product_count

    # --- sellers --------------------------------------------------------------------------------
    seller_ids: list[str] = []
    path = out_dir / SELLERS.filename
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(SELLERS.columns)
        for _ in range(seller_count):
            sid = olist_id(rng)
            seller_ids.append(sid)
            code, city, _ = weighted_state(rng)
            w.writerow([sid, rng.randint(1000, 99999), city, code])
    counts[SELLERS.filename] = seller_count

    # --- customers ------------------------------------------------------------------------------
    # One customer_id per order, and customer_unique_id shared by ~3% of them, which is what makes
    # "repeat customers" a real (small) number rather than zero.
    customer_ids: list[str] = []
    unique_pool: list[str] = []
    path = out_dir / CUSTOMERS.filename
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(CUSTOMERS.columns)
        for _ in range(orders_count):
            cid = olist_id(rng)
            customer_ids.append(cid)
            if unique_pool and rng.random() < 0.031:
                unique_id = rng.choice(unique_pool)
            else:
                unique_id = olist_id(rng)
                unique_pool.append(unique_id)
            code, city, _ = weighted_state(rng)
            w.writerow([cid, unique_id, rng.randint(1000, 99999), city, code])
    counts[CUSTOMERS.filename] = orders_count

    # --- orders, items, payments ----------------------------------------------------------------
    # Written in one pass so the three files are consistent by construction. Generating them
    # separately and joining afterwards is how a synthetic dataset ends up with payments for orders
    # that do not exist - which the loader's foreign keys would then reject, at 3am.
    start = datetime(2016, 9, 4)
    span_days = 750  # Sept 2016 -> Oct 2018, the real dataset's window

    # A per-product base price, log-normal-ish: a few expensive items, many cheap ones. Fixed per
    # product so the median-price derivation in the loader has something stable to find.
    base_price = {pid: round(min(6735.0, max(0.85, rng.lognormvariate(4.0, 0.85))), 2)
                  for pid in product_ids}

    orders_path = out_dir / ORDERS.filename
    items_path = out_dir / ORDER_ITEMS.filename
    payments_path = out_dir / ORDER_PAYMENTS.filename

    item_rows = 0
    payment_rows = 0

    with orders_path.open("w", newline="", encoding="utf-8") as of, \
            items_path.open("w", newline="", encoding="utf-8") as itf, \
            payments_path.open("w", newline="", encoding="utf-8") as pf:

        ow, iw, pw = csv.writer(of), csv.writer(itf), csv.writer(pf)
        ow.writerow(ORDERS.columns)
        iw.writerow(ORDER_ITEMS.columns)
        pw.writerow(ORDER_PAYMENTS.columns)

        for i in range(orders_count):
            order_id = olist_id(rng)
            customer_id = customer_ids[i]
            status = weighted(rng, ORDER_STATUSES)

            purchased = start + timedelta(
                days=rng.randint(0, span_days),
                seconds=rng.randint(0, 86_399),
            )
            approved = purchased + timedelta(minutes=rng.randint(10, 2880))
            estimated = purchased + timedelta(days=rng.randint(6, 40))

            carrier_date = ""
            delivered = ""
            if status in ("delivered", "shipped"):
                carrier = approved + timedelta(days=rng.randint(0, 6))
                carrier_date = carrier.strftime("%Y-%m-%d %H:%M:%S")
            if status == "delivered":
                # ~8% arrive after the estimate. That proportion is what makes "how many shipped
                # late" a question worth asking of the agent.
                if rng.random() < 0.08:
                    d = estimated + timedelta(days=rng.randint(1, 25))
                else:
                    d = purchased + timedelta(days=rng.randint(1, max(2, (estimated - purchased).days)))
                delivered = d.strftime("%Y-%m-%d %H:%M:%S")

            ow.writerow([
                order_id, customer_id, status,
                purchased.strftime("%Y-%m-%d %H:%M:%S"),
                approved.strftime("%Y-%m-%d %H:%M:%S"),
                carrier_date, delivered,
                estimated.strftime("%Y-%m-%d %H:%M:%S"),
            ])

            # 1.13 items per order on average: mostly one, occasionally several of the same product.
            n_items = 1
            r = rng.random()
            if r > 0.885:
                n_items = 2
            if r > 0.975:
                n_items = rng.randint(3, 6)

            order_total = 0.0
            shipping_limit = approved + timedelta(days=rng.randint(1, 10))
            for item_no in range(1, n_items + 1):
                pid = rng.choice(product_ids)
                price = round(base_price[pid] * rng.uniform(0.9, 1.1), 2)
                freight = round(max(0.0, rng.gauss(19.99, 9.0)), 2)
                order_total += price + freight
                iw.writerow([
                    order_id, item_no, pid, rng.choice(seller_ids),
                    shipping_limit.strftime("%Y-%m-%d %H:%M:%S"),
                    f"{price:.2f}", f"{freight:.2f}",
                ])
                item_rows += 1

            # Most orders have one payment row; vouchers split across several.
            ptype = weighted(rng, PAYMENT_TYPES)
            if ptype == "voucher" and rng.random() < 0.5:
                parts = rng.randint(2, 4)
                remaining = round(order_total, 2)
                for seq in range(1, parts + 1):
                    part = round(remaining / (parts - seq + 1), 2) if seq < parts else round(remaining, 2)
                    remaining = round(remaining - part, 2)
                    pw.writerow([order_id, seq, "voucher", 1, f"{part:.2f}"])
                    payment_rows += 1
            else:
                installments = 1
                if ptype == "credit_card":
                    installments = rng.choices(
                        [1, 2, 3, 4, 5, 6, 8, 10, 12],
                        weights=[52, 12, 10, 7, 5, 4, 3, 4, 3], k=1)[0]
                pw.writerow([order_id, 1, ptype, installments, f"{order_total:.2f}"])
                payment_rows += 1

    counts[ORDERS.filename] = orders_count
    counts[ORDER_ITEMS.filename] = item_rows
    counts[ORDER_PAYMENTS.filename] = payment_rows
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path,
                        default=Path(__file__).resolve().parent.parent / "data" / "generated",
                        help="directory to write the CSVs into")
    parser.add_argument("--orders", type=int, default=100_000,
                        help="how many orders to generate (the real dataset has 99,441)")
    parser.add_argument("--seed", type=int, default=42,
                        help="RNG seed; the same seed produces byte-identical files")
    args = parser.parse_args()

    counts = generate(args.out, args.orders, args.seed)

    print(f"Wrote synthetic Olist-shaped CSVs to {args.out}")
    width = max(len(name) for name in counts)
    for name, n in counts.items():
        print(f"  {name:<{width}}  {n:>9,} rows")
    print()
    print("SYNTHETIC DATA. Every number measured against it must say so - see docs/data-setup.md.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
