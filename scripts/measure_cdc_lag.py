#!/usr/bin/env python3
"""Measure end-to-end freshness: an OLTP write, to visible in the warehouse.

    # terminal 1
    python warehouse/etl/cdc_sink.py
    # terminal 2
    python scripts/measure_cdc_lag.py --samples 5

What is being timed is the WHOLE chain, not one hop:

    UPDATE catalogdb.products
      -> PostgreSQL WAL
      -> Debezium logical replication slot
      -> Kafka topic catalog.public.products
      -> the sink consumer
      -> MySQL dim_product

Timing one hop would flatter it. The number that matters to an analyst is "how stale is the answer",
and that is the sum.

The catalogue is used rather than an order because a product update is ONE row through ONE connector
- so the measurement is of the pipeline, not of the saga's own latency. Order freshness is the same
chain plus the outbox poll interval, and that component is already measured in Step 5.
"""

from __future__ import annotations

import argparse
import statistics
import time
import uuid

import psycopg
import pymysql

PG = "postgresql://catalogdb:catalogdb@localhost:5434/catalogdb?connect_timeout=5"
MYSQL = dict(host="127.0.0.1", port=3307, user="warehouse", password="warehouse", database="dw")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=60.0)
    args = parser.parse_args()

    lags: list[float] = []

    with psycopg.connect(PG) as pg, pymysql.connect(**MYSQL) as my:
        with pg.cursor() as cur:
            cur.execute("SELECT product_key, name FROM products WHERE active LIMIT 1")
            row = cur.fetchone()
        if row is None:
            print("no products; run the loader first")
            return 1
        product_key, original_name = row
        print(f"probing with product {product_key}\n")

        for i in range(1, args.samples + 1):
            # A marker that cannot collide with anything the loader wrote, so "has it arrived?" is
            # an exact string comparison rather than a guess about which update won.
            marker = f"cdc-probe-{uuid.uuid4().hex[:12]}"

            started = time.perf_counter()
            with pg.cursor() as cur:
                cur.execute("UPDATE products SET name = %s, updated_at = now() "
                            "WHERE product_key = %s", (marker, product_key))
            pg.commit()
            committed = time.perf_counter()

            deadline = time.time() + args.timeout
            seen = None
            while time.time() < deadline:
                with my.cursor() as cur:
                    # A fresh connection-level read each time: MySQL's default REPEATABLE READ would
                    # otherwise pin the snapshot to the first SELECT of the transaction and the probe
                    # would never see the change - a self-inflicted "lag" of infinity.
                    my.commit()
                    cur.execute("SELECT product_name FROM dim_product WHERE product_key = %s",
                                (product_key,))
                    got = cur.fetchone()
                if got and got[0] == marker:
                    seen = time.perf_counter()
                    break
                time.sleep(0.02)

            if seen is None:
                print(f"  sample {i}: NOT VISIBLE within {args.timeout:.0f}s "
                      f"- is warehouse/etl/cdc_sink.py running?")
                continue

            lag_ms = (seen - committed) * 1000
            lags.append(lag_ms)
            print(f"  sample {i}: commit -> visible in {lag_ms:8.0f} ms")
            time.sleep(1)

        # Put the name back. Added after a run left a real catalogue product called
        # `cdc-probe-c7121093b377`, which then propagated through CDC to the warehouse and, once
        # Step 7 existed, on to the search index - where it was still sitting when the storefront
        # rendered it as a product title. A measurement script that mutates production data has to
        # clean up after itself, and this one measures a pipeline whose whole job is to make sure
        # it did not.
        with pg.cursor() as cur:
            cur.execute("UPDATE products SET name = %s, updated_at = now() WHERE product_key = %s",
                        (original_name, product_key))
        pg.commit()
        print(f"restored the product name to {original_name!r}")

    if not lags:
        return 1

    print(f"\nsamples      {len(lags)}")
    print(f"min          {min(lags):8.0f} ms")
    print(f"median       {statistics.median(lags):8.0f} ms")
    print(f"max          {max(lags):8.0f} ms")
    print()
    print("This is Postgres commit -> Debezium -> Kafka -> the Python sink -> MySQL, measured from")
    print("the moment the OLTP transaction commits. The sink's poll timeout dominates it, which is")
    print("the same trade the outbox relay makes: a poll interval is a floor on freshness.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
