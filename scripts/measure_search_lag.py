#!/usr/bin/env python3
"""Measure catalogue write -> searchable, end to end.

    python scripts/measure_search_lag.py --samples 5

What is timed is the whole chain, not one hop:

    PUT /api/products/{id}          (catalog-service, one transaction: row + outbox row)
      -> outbox relay poll          (500 ms floor, same trade as the saga)
      -> Kafka ecommerce.product.updated
      -> search-service listener
      -> Elasticsearch index
      -> visible to a SEARCH, not just to a GET by id

The last line is the one that matters and the one an easy version of this script would get wrong.
Elasticsearch writes are visible to a direct `GET /index/_doc/{id}` immediately, but NOT to a query
until the index is refreshed - by default once a second. Polling the document would therefore report
a lag that no user experiences. This polls the SEARCH endpoint, which is what a user actually hits.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
import uuid

GATEWAY = "http://localhost:8080"
CATALOG = "http://localhost:8082"


def call(method, url, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text.strip().startswith(("{", "[")) else text)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin-dev-password")
    args = parser.parse_args()

    _, login = call("POST", f"{GATEWAY}/api/auth/login",
                    body={"username": args.user, "password": args.password})
    token = login["token"]

    _, page = call("GET", f"{GATEWAY}/api/products?size=1")
    product = page["content"][0]
    print(f"probing with product {product['productKey']} (id {product['id']})\n")

    lags = []
    for i in range(1, args.samples + 1):
        # A marker in the NAME, because the name is indexed and searchable. Changing the price would
        # test the same pipeline but could only be observed by fetching the document, not by
        # searching for it - and "searchable" is the property being measured.
        marker = f"probe{uuid.uuid4().hex[:10]}"

        status, _ = call("PUT", f"{CATALOG}/api/products/{product['id']}", token=token, body={
            "productKey": product["productKey"],
            "name": marker,
            "category": product["category"],
            "price": product["price"],
            "weightGrams": product["weightGrams"],
            "photosQty": product["photosQty"],
        })
        if status != 200:
            print(f"  sample {i}: write failed ({status})")
            continue
        committed = time.perf_counter()

        deadline = time.time() + args.timeout
        seen = None
        while time.time() < deadline:
            _, res = call("GET", f"{GATEWAY}/api/search?q={marker}&size=1")
            if isinstance(res, dict) and res.get("totalHits", 0) > 0:
                seen = time.perf_counter()
                break
            time.sleep(0.05)

        if seen is None:
            print(f"  sample {i}: NOT SEARCHABLE within {args.timeout:.0f}s "
                  f"- is search-service running and consuming?")
            continue

        lag_ms = (seen - committed) * 1000
        lags.append(lag_ms)
        print(f"  sample {i}: write -> searchable in {lag_ms:8.0f} ms")
        time.sleep(1)

    # Put the original name back, so re-running the script does not slowly rename a product to a
    # string of probe markers.
    call("PUT", f"{CATALOG}/api/products/{product['id']}", token=token, body={
        "productKey": product["productKey"], "name": product["name"],
        "category": product["category"], "price": product["price"],
        "weightGrams": product["weightGrams"], "photosQty": product["photosQty"],
    })

    if not lags:
        return 1
    print(f"\nsamples   {len(lags)}")
    print(f"min       {min(lags):8.0f} ms")
    print(f"median    {statistics.median(lags):8.0f} ms")
    print(f"max       {max(lags):8.0f} ms")
    print()
    print("Two floors are stacked in that number: the outbox relay's 500 ms poll, and")
    print("Elasticsearch's 1 s index refresh. Neither is a defect - both are the cost of not")
    print("blocking the catalogue write on a downstream index.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
