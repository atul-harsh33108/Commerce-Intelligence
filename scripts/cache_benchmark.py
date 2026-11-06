#!/usr/bin/env python3
"""Measure what the Redis cache actually buys, cold vs warm.

Two populations of requests over the SAME set of product ids:

  cold   the cache is flushed first, so every request is a miss -> Postgres
  warm   the same requests again, so every request is a hit -> Redis

Reported: p50/p95/p99 for each, the speed-up, and the hit rate taken from Redis's OWN counters
(`INFO stats`) rather than from the application's. Two independent sources agreeing is what makes the
number defensible; an application-reported hit rate is the application marking its own homework.

    python scripts/cache_benchmark.py --requests 500 --concurrency 8

The absolute latencies are meaningless off this laptop - a JVM, Postgres and Redis on one machine
with everything warm. The RATIO is what transfers.
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080"


def get(url: str) -> tuple[float, int]:
    """Returns (milliseconds, status). THE STATUS IS NOT OPTIONAL - see below.

    This function used to swallow HTTPError, read the body to keep the connection tidy, and return
    the elapsed time regardless. That is how it reported a beautiful cache: every cache HIT in
    catalog-service was throwing a ClassCastException (a Redis serializer wrote no type information,
    so every read came back as a LinkedHashMap), and an error response is FAST. The latency
    improvement was real and it was the latency of failing.

    A benchmark that does not check what came back measures the wrong system. So the status is
    returned, and `run()` refuses to report on a batch that was not entirely 200.
    """
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        e.read()
        status = e.code
    return (time.perf_counter() - started) * 1000, status


def pg_stat() -> tuple[int, int]:
    """(transactions committed, tuples fetched) for catalogdb.

    THE MEASUREMENT THAT MATTERS, and the one a latency histogram hides on a laptop. A cache's job is
    to take load off the database; whether that shows up as latency depends entirely on whether the
    database was the bottleneck, and on this machine - one warm Postgres, 33K rows, a single-row
    lookup on a primary key - it is not. Counting the queries the database never had to serve is the
    honest version of the claim.
    """
    out = subprocess.run(
        ["docker", "exec", "ecommerce-catalog-db", "psql", "-U", "catalogdb", "-d", "catalogdb",
         "-t", "-A", "-F", ",", "-c",
         "SELECT xact_commit, tup_fetched FROM pg_stat_database WHERE datname='catalogdb'"],
        capture_output=True, text=True, check=True).stdout.strip()
    commits, fetched = out.split(",")
    return int(commits), int(fetched)


def redis_stat(name: str) -> int:
    out = subprocess.run(
        ["docker", "exec", "ecommerce-redis", "redis-cli", "INFO", "stats"],
        capture_output=True, text=True, check=True).stdout
    for line in out.splitlines():
        if line.startswith(name + ":"):
            return int(line.split(":", 1)[1])
    return 0


def flush_cache() -> None:
    # Only this service's keys, not FLUSHALL. The prefix is what makes that possible - see
    # CacheConfig.prefixCacheNameWith. FLUSHALL would also wipe the gateway's rate-limit counters and
    # anything else sharing the instance, which on a shared Redis is how a benchmark takes down a
    # feature nobody was testing.
    subprocess.run(["docker", "exec", "ecommerce-redis", "sh", "-c",
                    "redis-cli --scan --pattern 'catalog::*' | xargs -r redis-cli DEL"],
                   capture_output=True, text=True, check=False)


def percentiles(samples: list[float]) -> tuple[float, float, float, float]:
    ordered = sorted(samples)
    return (
        statistics.median(ordered),
        ordered[int(len(ordered) * 0.95) - 1],
        ordered[int(len(ordered) * 0.99) - 1],
        statistics.mean(ordered),
    )


class BenchmarkFailed(RuntimeError):
    pass


def run(urls: list[str], concurrency: int, label: str = "") -> list[float]:
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(get, urls))

    bad = [s for _, s in results if s != 200]
    if bad:
        counts = {c: bad.count(c) for c in sorted(set(bad))}
        raise BenchmarkFailed(
            f"{len(bad)} of {len(results)} requests{' in ' + label if label else ''} did not "
            f"return 200: {counts}. Refusing to report a latency number for responses that are "
            f"not answers - that is the mistake this benchmark made for four steps."
        )
    return [ms for ms, _ in results]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--distinct", type=int, default=50,
                        help="how many distinct products the requests are spread over")
    parser.add_argument("--direct", default="http://localhost:8082",
                        help="the catalog service itself, bypassing the gateway")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    with urllib.request.urlopen(f"{base}/api/products?size={args.distinct}") as r:
        page = json.load(r)
    ids = [p["id"] for p in page["content"]]
    if not ids:
        print("no products; run the loader first")
        return 1

    # Straight at catalog-service, NOT through the gateway. The gateway adds a fixed ~4 ms of hop and
    # JWT filtering to every request, which is real cost a user pays and is also noise for this
    # particular question: it is identical on a hit and on a miss, so including it shrinks the
    # measured difference without changing it. Measured through the gateway first, which is how that
    # was established rather than assumed.
    direct = args.direct.rstrip("/")
    urls = [f"{direct}/api/products/{ids[i % len(ids)]}" for i in range(args.requests)]

    print(f"{args.requests} requests over {len(ids)} distinct products, "
          f"concurrency {args.concurrency}")
    print(f"straight to catalog-service on {direct}, bypassing the gateway\n")

    # --- cold ------------------------------------------------------------------------------------
    flush_cache()
    hits0, misses0 = redis_stat("keyspace_hits"), redis_stat("keyspace_misses")
    # A short warm-up that is DISCARDED: the first request through a cold JVM pays class loading and
    # JIT, which is a property of the process rather than of the cache and would otherwise land
    # entirely in the "cold" column and flatter the result.
    run(urls[:20], args.concurrency, "warm-up")
    flush_cache()

    pg0 = pg_stat()
    cold = run(urls, args.concurrency, "the cold run")
    hits1, misses1 = redis_stat("keyspace_hits"), redis_stat("keyspace_misses")
    pg1 = pg_stat()

    # --- warm ------------------------------------------------------------------------------------
    warm = run(urls, args.concurrency, "the warm run")
    hits2, misses2 = redis_stat("keyspace_hits"), redis_stat("keyspace_misses")
    pg2 = pg_stat()

    # --- cache disabled, for the counterfactual --------------------------------------------------
    # Every request flushed beforehand, so the cache can never serve one. This is what the endpoint
    # cost before Step 6, measured rather than remembered.
    nocache = []
    for i in range(0, len(urls), 50):
        flush_cache()
        nocache.extend(run(urls[i:i + 50], args.concurrency, "the cache-disabled run"))
    pg3 = pg_stat()

    c50, c95, c99, cmean = percentiles(cold)
    w50, w95, w99, wmean = percentiles(warm)
    n50, n95, n99, nmean = percentiles(nocache)

    cold_commits, cold_fetched = pg1[0] - pg0[0], pg1[1] - pg0[1]
    warm_commits, warm_fetched = pg2[0] - pg1[0], pg2[1] - pg1[1]
    none_commits, none_fetched = pg3[0] - pg2[0], pg3[1] - pg2[1]

    cold_hits, cold_misses = hits1 - hits0, misses1 - misses0
    warm_hits, warm_misses = hits2 - hits1, misses2 - misses1

    def rate(h, m):
        total = h + m
        return (h / total * 100) if total else 0.0

    print(f"{'':12s} {'p50':>8s} {'p95':>8s} {'p99':>8s} {'mean':>8s}   "
          f"{'redis h/m':>13s} {'hit%':>6s}   {'pg xacts':>9s} {'pg tuples':>10s}")
    print(f"{'no cache':12s} {n50:8.2f} {n95:8.2f} {n99:8.2f} {nmean:8.2f}   "
          f"{'-':>13s} {'-':>6s}   {none_commits:9d} {none_fetched:10d}")
    print(f"{'cold->warm':12s} {c50:8.2f} {c95:8.2f} {c99:8.2f} {cmean:8.2f}   "
          f"{cold_hits:6d}/{cold_misses:<6d} {rate(cold_hits, cold_misses):5.1f}%   "
          f"{cold_commits:9d} {cold_fetched:10d}")
    print(f"{'warm':12s} {w50:8.2f} {w95:8.2f} {w99:8.2f} {wmean:8.2f}   "
          f"{warm_hits:6d}/{warm_misses:<6d} {rate(warm_hits, warm_misses):5.1f}%   "
          f"{warm_commits:9d} {warm_fetched:10d}")
    print()
    print(f"hit rate, warm      {rate(warm_hits, warm_misses):.1f}%  "
          f"({warm_hits} hits, {warm_misses} misses, from Redis's own counters)")
    print(f"p50 latency         {n50:.2f} ms -> {w50:.2f} ms   "
          f"({(1 - w50 / n50) * 100:+.0f}%)")
    print(f"p99 latency         {n99:.2f} ms -> {w99:.2f} ms   "
          f"({(1 - w99 / n99) * 100:+.0f}%)")
    if none_commits:
        print(f"database queries    {none_commits} -> {warm_commits}   "
              f"({(1 - warm_commits / none_commits) * 100:.1f}% removed)")
    print()
    print("READ THE TWO COLUMNS TOGETHER. On this machine a primary-key lookup against a warm")
    print("Postgres holding 33K rows is already about a millisecond, so the latency column moves")
    print("very little - the cache is not removing a bottleneck, because there is not one here.")
    print("What it does remove is database WORK, and that is the column that would matter first")
    print("under real traffic: the queries are gone whether or not the p50 notices.")
    return 0


def _guarded_main() -> int:
    try:
        return main()
    except BenchmarkFailed as e:
        print("")
        print("BENCHMARK ABORTED")
        print(f"  {e}")
        return 2


if __name__ == "__main__":
    raise SystemExit(_guarded_main())
