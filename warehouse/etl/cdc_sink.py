#!/usr/bin/env python3
"""Consume Debezium change events and maintain the MySQL star schema.

    python warehouse/etl/cdc_sink.py                  # run until interrupted
    python warehouse/etl/cdc_sink.py --drain           # exit once the topics are caught up
    python warehouse/etl/cdc_sink.py --reset-offsets   # re-read every topic from the beginning

================================================================================================
WHY THIS IS NOT A KAFKA CONNECT JDBC SINK
================================================================================================

The obvious architecture is Debezium -> Kafka -> Confluent's JDBC sink connector -> MySQL, with no
code at all. It was rejected, and the reason is not "we wanted to write Python".

**A JDBC sink writes a table copy.** Give it `order.public.orders` and it produces a MySQL table
called `orders` with the same columns. That is replication, not a warehouse. What Step 8 needs is:

  - one fact row per ORDER LINE, joining a row from `orders` to a row from `order_items` that
    arrive on different topics at different times;
  - surrogate keys resolved against dimensions (product_key -> product_id) that may not exist yet;
  - derived measures (line_revenue, delivery_days, is_late) that no source column contains;
  - a date dimension key computed from a timestamp.

Every one of those is a *transformation across streams*, and Kafka Connect's single-message
transforms are, by definition, single-message. The real options were a stream processor (Kafka
Streams, Flink) or a consumer that does the join in the database it is writing to. For 118K facts on
one laptop the second is smaller, debuggable, and does not add a second cluster - and it makes the
dimensional modelling visible instead of hiding it in connector configuration.

The honest cost: this process is a single point of failure with no horizontal scaling story, and a
Flink job would have both. Recorded rather than glossed.

================================================================================================
THE THREE THINGS THAT MAKE IT CORRECT
================================================================================================

1. **Every write is an upsert on a natural key.** Kafka is at-least-once and a restart re-reads
   uncommitted offsets, so the same change event WILL be processed twice. `INSERT ... ON DUPLICATE
   KEY UPDATE` against `uk_fact_order_line` makes that a no-op instead of duplicated revenue.

2. **Late-arriving dimensions get a stub.** An order line can reference a product whose change event
   has not been consumed yet - the topics are independent and their consumer lag is independent. The
   sink inserts a placeholder dimension row and lets the real one fill it in later, which is the
   standard warehouse answer and the reason the fact table has no foreign key constraints.

3. **Order and line are joined in the database, not in memory.** A fact needs both, and holding
   half-facts in a dict until the other half arrives is a memory leak with a correctness bug in it
   (what if the other half never comes?). Instead the order's attributes are written to a small
   staging table and the fact is (re)built whenever either side changes.
"""

from __future__ import annotations

import argparse
import json
import signal
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone

try:
    import pymysql
    from kafka import KafkaConsumer, TopicPartition
except ImportError:
    print("pip install pymysql kafka-python-ng", file=sys.stderr)
    raise SystemExit(2)

MYSQL = dict(host="127.0.0.1", port=3307, user="warehouse", password="warehouse",
             database="dw", charset="utf8mb4", autocommit=False)

TOPICS = [
    "catalog.public.products",
    "user.public.users",
    "order.public.orders",
    "order.public.order_items",
]

GROUP = "warehouse-sink"

running = True


def stop(_sig, _frame):
    global running
    running = False
    print("\nstopping after the current batch...")


# -------------------------------------------------------------------------------------------------
# Debezium envelope
# -------------------------------------------------------------------------------------------------

def payload_of(value: dict | None):
    """The row after the change, and the operation.

    With `schemas.enable=false` a Debezium message is {before, after, op, source, ts_ms}. `op` is
    c(reate), u(pdate), d(elete) or r(ead, i.e. from the snapshot).

    A DELETE has `after: null` and is followed by a TOMBSTONE - a record with a null value, which is
    what makes Kafka's log compaction able to drop the key entirely. Both are returned as
    (None, 'd') so the caller handles them the same way.
    """
    if value is None:
        return None, "d"          # tombstone
    op = value.get("op", "r")
    if op == "d":
        return value.get("before"), "d"
    return value.get("after"), op


def as_date_id(ts: str | None) -> int | None:
    if not ts:
        return None
    try:
        # Debezium with time.precision.mode=connect emits epoch milliseconds for timestamptz.
        if isinstance(ts, (int, float)):
            dt = datetime.fromtimestamp(ts / 1000, tz=timezone.utc)
        else:
            dt = datetime.fromisoformat(str(ts).replace("Z", "+00:00"))
        return int(dt.strftime("%Y%m%d"))
    except (ValueError, OSError):
        return None


def as_datetime(ts) -> datetime | None:
    if ts is None:
        return None
    try:
        if isinstance(ts, (int, float)):
            return datetime.fromtimestamp(ts / 1000, tz=timezone.utc).replace(tzinfo=None)
        return datetime.fromisoformat(str(ts).replace("Z", "+00:00")).replace(tzinfo=None)
    except (ValueError, OSError):
        return None


def day_diff(a, b) -> int | None:
    da, db = as_datetime(a), as_datetime(b)
    if da is None or db is None:
        return None
    return (db - da).days


# -------------------------------------------------------------------------------------------------
# Staging: the order attributes a fact row needs, keyed by order_key.
#
# A tiny table rather than a Python dict, for a reason worth stating: an order's lines can arrive
# minutes after the order (or before it), and a dict would lose them on restart while the fact table
# would not. Putting the join state in the same database as the facts means the sink is stateless and
# can be killed at any point.
# -------------------------------------------------------------------------------------------------
STAGING_DDL = [
"""
CREATE TABLE IF NOT EXISTS stg_order (
    order_id       BIGINT      PRIMARY KEY COMMENT 'orders.id from the OLTP database',
    order_key      VARCHAR(64) NOT NULL,
    customer_key   VARCHAR(64),
    order_status   VARCHAR(24),
    purchased_at   DATETIME,
    delivered_at   DATETIME,
    estimated_at   DATETIME,
    UNIQUE KEY uk_stg_order_key (order_key)
) COMMENT 'Order-level attributes, staged. Not part of the star schema.'
""",
"""
CREATE TABLE IF NOT EXISTS stg_order_item (
    order_id       BIGINT      NOT NULL COMMENT 'FK to stg_order, as it is in the source',
    line_no        INT         NOT NULL,
    product_key    VARCHAR(64),
    seller_key     VARCHAR(64),
    quantity       INT         NOT NULL DEFAULT 1,
    unit_price     DECIMAL(12,2) NOT NULL DEFAULT 0,
    freight_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (order_id, line_no)
) COMMENT 'Order lines, staged. Not part of the star schema.'
""",
]


class Sink:
    """Stages both sides of the join, then builds facts set-based.

    THE PROBLEM THIS SHAPE SOLVES, and it is the reason a JDBC sink cannot do this job.

    A fact row needs an order AND its line. They arrive on two different topics with two independent
    consumer positions, so either can be first - and during the initial snapshot they are emitted in
    whatever order the connector chooses, not in a business order. Worse, `order_items` references
    `orders(id)`, an internal surrogate the fact table does not carry, so the line does not even know
    its own order's business key.

    Three ways to handle that:

      hold half-facts in memory   a dict that grows without bound, loses everything on restart, and
                                  has no answer for "what if the other half never arrives"
      look the order up per line  118,000 single-row SELECTs during the snapshot
      STAGE BOTH SIDES            two small tables, and one set-based INSERT..SELECT that joins them

    The third is what is done. It is stateless across restarts (the state is in the database being
    written), it is one round trip per order rather than per line, and "which lines have no order"
    becomes a query anybody can run rather than an invisible dict.
    """

    def __init__(self, conn):
        self.conn = conn
        self.counts = defaultdict(int)
        self.dirty_orders: set[int] = set()
        self.geography_ids: dict[str, int] = {}
        with self.conn.cursor() as cur:
            cur.execute("SELECT state_code, geography_id FROM dim_geography")
            self.geography_ids = {code: gid for code, gid in cur.fetchall()}

    # --- dimensions ------------------------------------------------------------------------------

    def upsert_product(self, row: dict):
        with self.conn.cursor() as cur:
            cur.execute("""
                INSERT INTO dim_product
                    (product_key, product_name, category, category_pt, unit_price,
                     weight_g, photos_qty, is_active)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    product_name = VALUES(product_name),
                    category     = VALUES(category),
                    category_pt  = VALUES(category_pt),
                    unit_price   = VALUES(unit_price),
                    weight_g     = VALUES(weight_g),
                    photos_qty   = VALUES(photos_qty),
                    is_active    = VALUES(is_active)
            """, (row["product_key"], row.get("name"), row.get("category"),
                  row.get("category_key"), row.get("price"), row.get("weight_g"),
                  row.get("photos_qty"), 1 if row.get("active") else 0))
        self.counts["dim_product"] += 1

    def upsert_customer(self, row: dict):
        # Only seeded (Olist) users become warehouse customers. A registered account with no
        # customer_key has no historical orders and would be a dimension row nothing joins to.
        key = row.get("customer_key")
        if not key:
            self.counts["users_skipped_no_customer_key"] += 1
            return
        with self.conn.cursor() as cur:
            cur.execute("""
                INSERT INTO dim_customer
                    (customer_key, customer_unique_key, city, state, zip_prefix)
                VALUES (%s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    customer_unique_key = VALUES(customer_unique_key),
                    city                = VALUES(city),
                    state               = VALUES(state),
                    zip_prefix          = VALUES(zip_prefix)
            """, (key, row.get("customer_unique_key"), row.get("city"),
                  row.get("state"), row.get("zip_prefix")))
        self.counts["dim_customer"] += 1

    # --- staging ---------------------------------------------------------------------------------

    def stage_order(self, row: dict):
        with self.conn.cursor() as cur:
            cur.execute("""
                INSERT INTO stg_order
                    (order_id, order_key, customer_key, order_status,
                     purchased_at, delivered_at, estimated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    order_key    = VALUES(order_key),
                    customer_key = VALUES(customer_key),
                    order_status = VALUES(order_status),
                    purchased_at = VALUES(purchased_at),
                    delivered_at = VALUES(delivered_at),
                    estimated_at = VALUES(estimated_at)
            """, (row["id"], row["order_key"], row.get("customer_key"), row.get("status"),
                  as_datetime(row.get("purchased_at")),
                  as_datetime(row.get("delivered_customer_at")),
                  as_datetime(row.get("estimated_delivery_at"))))
        self.counts["stg_order"] += 1
        self.dirty_orders.add(row["id"])

    def stage_item(self, row: dict):
        with self.conn.cursor() as cur:
            cur.execute("""
                INSERT INTO stg_order_item
                    (order_id, line_no, product_key, seller_key, quantity, unit_price, freight_amount)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    product_key    = VALUES(product_key),
                    seller_key     = VALUES(seller_key),
                    quantity       = VALUES(quantity),
                    unit_price     = VALUES(unit_price),
                    freight_amount = VALUES(freight_amount)
            """, (row["order_id"], row["line_no"], row.get("product_key"), row.get("seller_key"),
                  int(row.get("quantity") or 1), row.get("unit_price") or 0,
                  row.get("freight_amount") or 0))
        self.counts["stg_order_item"] += 1
        self.dirty_orders.add(row["order_id"])

    # --- fact ------------------------------------------------------------------------------------

    def ensure_dimension_rows(self):
        """Create STUB dimension rows for any key a staged fact references but no dimension has.

        The late-arriving-dimension pattern, done set-based. The topics are independent, so an order
        line can reference a product whose change event has not been consumed yet.

        Skipping such facts until the dimension shows up is the alternative, and it loses revenue
        silently and permanently if the dimension event was compacted away or the connector was
        restarted past it. A stub is visible in the warehouse as a product with a null category -
        something a person can see and ask about - and it is filled in when the real event arrives,
        because the upsert matches on product_key.
        """
        with self.conn.cursor() as cur:
            cur.execute("""
                INSERT IGNORE INTO dim_product (product_key, product_name)
                SELECT DISTINCT i.product_key, CONCAT('(unresolved) ', LEFT(i.product_key, 8))
                  FROM stg_order_item i
                  LEFT JOIN dim_product p ON p.product_key = i.product_key
                 WHERE i.product_key IS NOT NULL AND p.product_id IS NULL
            """)
            if cur.rowcount > 0:
                self.counts["dim_product_stubs"] += cur.rowcount
            cur.execute("""
                INSERT IGNORE INTO dim_customer (customer_key)
                SELECT DISTINCT o.customer_key
                  FROM stg_order o
                  LEFT JOIN dim_customer c ON c.customer_key = o.customer_key
                 WHERE o.customer_key IS NOT NULL AND c.customer_id IS NULL
            """)
            if cur.rowcount > 0:
                self.counts["dim_customer_stubs"] += cur.rowcount

    def build_facts(self, order_ids: set[int] | None = None) -> int:
        """One INSERT..SELECT that joins staging to dimensions and upserts the facts.

        Set-based rather than row-by-row: during the snapshot this turns 118,000 round trips into one
        statement per batch of orders. The derived measures (line_revenue, delivery_days, is_late)
        are computed HERE rather than in each analytical query, so "late" has one definition - which
        matters more than usual when the queries are written by a language model.

        `ON DUPLICATE KEY UPDATE` against uk_fact_order_line is the idempotency guarantee: Kafka is
        at-least-once, a restart re-reads uncommitted offsets, and the same change event WILL be
        processed twice. Without it that is duplicated revenue.
        """
        self.ensure_dimension_rows()

        where = ""
        params: tuple = ()
        if order_ids:
            placeholders = ",".join(["%s"] * len(order_ids))
            where = f"WHERE o.order_id IN ({placeholders})"
            params = tuple(order_ids)

        with self.conn.cursor() as cur:
            cur.execute(f"""
                INSERT INTO fact_order_items
                    (order_key, line_no, date_id, product_id, customer_id, geography_id,
                     order_status, seller_key, quantity, unit_price, freight_amount,
                     line_revenue, line_total, delivery_days, estimated_days, is_late, purchased_at)
                SELECT
                    o.order_key,
                    i.line_no,
                    COALESCE(CAST(DATE_FORMAT(o.purchased_at, '%%Y%%m%%d') AS UNSIGNED), 19700101),
                    p.product_id,
                    c.customer_id,
                    -- COALESCE to the Unknown member, never NULL: a NULL foreign key drops the row
                    -- out of every inner-join query, so revenue goes quietly missing instead of
                    -- showing up under "Unknown" where somebody notices it.
                    COALESCE(g.geography_id, 999),
                    o.order_status,
                    i.seller_key,
                    i.quantity,
                    i.unit_price,
                    i.freight_amount,
                    ROUND(i.quantity * i.unit_price, 2),
                    ROUND(i.quantity * i.unit_price + i.freight_amount, 2),
                    CASE WHEN o.delivered_at IS NOT NULL AND o.purchased_at IS NOT NULL
                         THEN DATEDIFF(o.delivered_at, o.purchased_at) END,
                    CASE WHEN o.estimated_at IS NOT NULL AND o.purchased_at IS NOT NULL
                         THEN DATEDIFF(o.estimated_at, o.purchased_at) END,
                    -- NULL rather than 0 when undelivered. "Not late" and "not yet delivered" are
                    -- different answers, and collapsing them makes every on-time percentage
                    -- optimistic.
                    CASE WHEN o.delivered_at IS NOT NULL AND o.estimated_at IS NOT NULL
                         THEN o.delivered_at > o.estimated_at END,
                    o.purchased_at
                  FROM stg_order_item i
                  JOIN stg_order o      ON o.order_id = i.order_id
                  LEFT JOIN dim_product p  ON p.product_key = i.product_key
                  LEFT JOIN dim_customer c ON c.customer_key = o.customer_key
                  LEFT JOIN dim_geography g ON g.state_code = c.state
                  {where}
                ON DUPLICATE KEY UPDATE
                    date_id        = VALUES(date_id),
                    product_id     = VALUES(product_id),
                    customer_id    = VALUES(customer_id),
                    geography_id   = VALUES(geography_id),
                    order_status   = VALUES(order_status),
                    seller_key     = VALUES(seller_key),
                    quantity       = VALUES(quantity),
                    unit_price     = VALUES(unit_price),
                    freight_amount = VALUES(freight_amount),
                    line_revenue   = VALUES(line_revenue),
                    line_total     = VALUES(line_total),
                    delivery_days  = VALUES(delivery_days),
                    estimated_days = VALUES(estimated_days),
                    is_late        = VALUES(is_late),
                    purchased_at   = VALUES(purchased_at),
                    ingested_at    = CURRENT_TIMESTAMP
            """, params)
            affected = cur.rowcount
        self.counts["fact_upserts"] += max(affected, 0)
        return affected

    def reconcile_geography(self) -> int:
        """Re-resolve `geography_id` on facts whose customer dimension has since been filled in.

        THE HALF OF "LATE-ARRIVING DIMENSION" THAT A STUB ROW DOES NOT COVER, and it cost 27% of the
        warehouse being wrong before it was noticed.

        The stub pattern solves *existence*: a fact can reference a customer whose change event has
        not arrived, so a placeholder row is inserted and the real event fills it in later. That
        works, and it is not enough here, because `geography_id` is not a foreign key to the
        customer - it is DERIVED from `dim_customer.state`, resolved once at the moment the fact is
        written.

        When the customer was still a stub, `state` was NULL, so the lookup fell to the Unknown
        member (999). Filling the customer in afterwards updates `dim_customer` and does nothing to
        the facts already pointing at 999. Measured on the real Olist load: **30,633 facts on the
        Unknown member whose customer had a state by the end of the run** - which would have made
        every "revenue by region" answer wrong by about a quarter, silently, with no orphan and no
        constraint violation to notice.

        The general rule: **a stub fixes a missing ROW; it does not fix a value DERIVED from that
        row's attributes.** Anything a fact computes from a dimension has to be reconciled once the
        dimension is complete.

        Cheap because of the predicate: it only touches facts currently on the Unknown member whose
        customer now resolves, so it is a no-op once the backlog is drained.
        """
        with self.conn.cursor() as cur:
            cur.execute("""
                UPDATE fact_order_items f
                  JOIN dim_customer c  ON c.customer_id = f.customer_id
                  JOIN dim_geography g ON g.state_code  = c.state
                   SET f.geography_id = g.geography_id
                 WHERE f.geography_id = 999
                   AND c.state IS NOT NULL
            """)
            fixed = cur.rowcount
        if fixed:
            self.counts["geography_reconciled"] += fixed
        return fixed

    def flush(self):
        """Build the facts for every order touched since the last flush, then commit."""
        if self.dirty_orders:
            # Chunked, because MySQL's max_allowed_packet bounds how many placeholders one statement
            # can carry, and a snapshot batch can touch thousands of orders.
            ids = list(self.dirty_orders)
            for i in range(0, len(ids), 500):
                self.build_facts(set(ids[i:i + 500]))
            self.dirty_orders.clear()
        self.conn.commit()


# -------------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bootstrap", default="localhost:9092")
    parser.add_argument("--drain", action="store_true",
                        help="exit once every partition is caught up")
    parser.add_argument("--reset-offsets", action="store_true",
                        help="re-read every topic from the beginning")
    parser.add_argument("--batch", type=int, default=2000,
                        help="rows per MySQL commit during a backlog")
    parser.add_argument("--flush-interval", type=float, default=0.5,
                        help="seconds after which a partial batch is committed anyway")
    args = parser.parse_args()

    signal.signal(signal.SIGINT, stop)

    conn = pymysql.connect(**MYSQL)
    with conn.cursor() as cur:
        for ddl in STAGING_DDL:
            cur.execute(ddl)
    conn.commit()

    consumer = KafkaConsumer(
        *TOPICS,
        bootstrap_servers=args.bootstrap,
        group_id=None if args.reset_offsets else GROUP,
        # earliest, because a warehouse that starts from "now" is missing all history.
        auto_offset_reset="earliest",
        # OFFSETS ARE COMMITTED BY THIS CODE, after the MySQL transaction commits. Auto-commit would
        # acknowledge records that have been FETCHED, so a crash mid-batch would skip them - silent
        # data loss in a warehouse, which is the one failure nobody notices for a quarter.
        enable_auto_commit=False,
        value_deserializer=lambda b: json.loads(b) if b else None,
        key_deserializer=lambda b: json.loads(b) if b else None,
        consumer_timeout_ms=5000,
        max_poll_records=1000,
    )
    if args.reset_offsets:
        consumer.poll(timeout_ms=2000)
        consumer.seek_to_beginning()

    sink = Sink(conn)
    started = time.perf_counter()
    processed = 0
    since_commit = 0
    idle_polls = 0
    last_flush = time.perf_counter()

    print(f"consuming {len(TOPICS)} topics from {args.bootstrap}")

    while running:
        batch = consumer.poll(timeout_ms=2000, max_records=args.batch)
        if not batch:
            idle_polls += 1
            # RECONCILE WHEN THE STREAM GOES QUIET, not on every flush.
            #
            # It is an UPDATE over the facts sitting on the Unknown geography member, so running it
            # mid-backlog would repeat work the next batch invalidates anyway. An idle poll means
            # the dimensions are as complete as they are going to get for now, which is exactly when
            # the derived values are worth re-resolving.
            if idle_polls == 1:
                fixed = sink.reconcile_geography()
                conn.commit()
                if fixed:
                    print(f"  reconciled geography on {fixed:,} fact rows", flush=True)
            if args.drain and idle_polls >= 2:
                break
            continue
        idle_polls = 0

        for tp, records in batch.items():
            for record in records:
                row, op = payload_of(record.value)
                topic = tp.topic
                try:
                    if row is None or op == "d":
                        # Deletes and tombstones are counted and not applied. A warehouse that
                        # deletes facts loses the history it exists to keep; the correct handling is
                        # a soft-delete flag on the dimension, and neither service hard-deletes
                        # anything (catalog deactivates, orders are cancelled rather than removed).
                        # Counted so "we ignore deletes" is visible rather than assumed.
                        sink.counts["deletes_ignored"] += 1
                    elif topic.endswith("products"):
                        sink.upsert_product(row)
                    elif topic.endswith("users"):
                        sink.upsert_customer(row)
                    elif topic.endswith("public.orders"):
                        sink.stage_order(row)
                    elif topic.endswith("order_items"):
                        sink.stage_item(row)
                except Exception as exc:                      # noqa: BLE001
                    # One bad record must not stop the stream. It is counted and logged; the
                    # alternative - crash and restart - replays the whole batch and hits it again.
                    sink.counts["errors"] += 1
                    if sink.counts["errors"] <= 5:
                        print(f"  error on {topic}: {exc.__class__.__name__}: {exc}",
                              file=sys.stderr)
                processed += 1
                since_commit += 1

        # COMMIT ON SIZE **OR** ON TIME, and the second half is not an optimisation.
        #
        # With only the size trigger, a quiet stream never reaches the threshold and the changes sit
        # in an uncommitted transaction indefinitely - so the warehouse is stale forever while the
        # sink reports itself healthy and busy. That is exactly what the first lag measurement found:
        # five probes, none visible within 60 s, with a running consumer that had already read them.
        #
        # The size trigger is for draining a backlog efficiently; the time trigger is what bounds
        # freshness when there is no backlog. A streaming sink needs both.
        now = time.perf_counter()
        if since_commit >= args.batch or (since_commit and now - last_flush >= args.flush_interval):
            sink.flush()
            last_flush = now
            # OFFSETS AFTER THE MYSQL COMMIT, never before. Committing first would acknowledge
            # records whose effects are not durable, and a crash in between loses them silently -
            # which in a warehouse means a quarter of revenue that nobody notices for a quarter.
            if not args.reset_offsets:
                consumer.commit()
            since_commit = 0
            elapsed = time.perf_counter() - started
            if processed % 5000 < args.batch:
                pass
            print(f"  {processed:>8,} records  {processed / max(elapsed, 0.001):>7,.0f}/s  "
                  + "  ".join(f"{k}={v:,}" for k, v in sorted(sink.counts.items())),
                  flush=True)

    sink.flush()
    # A final pass, so a run that ends immediately after a burst still leaves the warehouse
    # consistent rather than waiting for the next start.
    fixed = sink.reconcile_geography()
    conn.commit()
    if fixed:
        print(f"reconciled geography on {fixed:,} fact rows")
    if not args.reset_offsets:
        try:
            consumer.commit()
        except Exception:                                     # noqa: BLE001
            pass
    consumer.close()

    elapsed = time.perf_counter() - started
    print(f"\n{processed:,} records in {elapsed:.1f}s ({processed / max(elapsed, 0.001):,.0f}/s)")
    for k, v in sorted(sink.counts.items()):
        print(f"  {k:<26} {v:,}")

    with conn.cursor() as cur:
        for table in ("dim_product", "dim_customer", "fact_order_items"):
            cur.execute(f"SELECT COUNT(*) FROM {table}")
            print(f"  {table:<26} {cur.fetchone()[0]:,} rows in the warehouse")
    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
