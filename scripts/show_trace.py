#!/usr/bin/env python3
"""Place one order and print the trace it produced, hop by hop.

    python scripts/show_trace.py

This is the Step 13 Definition of Done executed rather than asserted: "one trace shows a full order
path with per-hop timings". It places a real order through the gateway, waits for the saga to settle,
then reads the trace back out of Zipkin and renders it as a waterfall.

WHY THE TRACE ID IS NOT TAKEN FROM A RESPONSE HEADER. It would be easier, and it would only prove the
synchronous half. The interesting part of this platform's order path is asynchronous - the outbox
relay publishes after the request has returned, and inventory and payment react to Kafka messages
minutes of wall-clock later in principle. So the trace is looked up by the order key, after the saga
has finished, which is the only way to find out whether the async hops joined the same trace or
started their own.
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
ZIPKIN = "http://localhost:9411"


def call(method, url, token=None, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text.strip().startswith(("{", "[")) else text)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def find_buyable(token):
    """A product with stock, checked rather than assumed.

    The smoke test learned this the hard way: always buying page[0] eventually drives that product to
    available = 0, and then the test fails for a reason that has nothing to do with what it tests.
    """
    _, page = call("GET", f"{GATEWAY}/api/products?size=40")
    for p in page["content"]:
        _, inv = call("GET", f"{GATEWAY}/api/inventory/{p['productKey']}")
        if isinstance(inv, dict) and inv.get("available", 0) >= 2:
            return p
    raise SystemExit("no product with stock; run scripts/load_olist.py")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin-dev-password")
    ap.add_argument("--settle", type=float, default=8.0,
                    help="seconds to wait for the saga and for spans to reach Zipkin")
    args = ap.parse_args()

    _, login = call("POST", f"{GATEWAY}/api/auth/login",
                    body={"username": args.user, "password": args.password})
    token = login["token"]

    product = find_buyable(token)
    print(f"buying {product['productKey']}  ({product['category']}, {product['price']})")

    status, order = call("POST", f"{GATEWAY}/api/orders", token=token,
                         body={"lines": [{"productKey": product["productKey"], "quantity": 1}]})
    if status != 201:
        print(f"order failed: {status} {order}")
        return 1
    order_key = order["orderKey"]
    print(f"order {order_key} -> {order['status']}")

    deadline = time.time() + args.settle
    while time.time() < deadline:
        _, o = call("GET", f"{GATEWAY}/api/orders/key/{order_key}", token=token)
        if o["status"] in ("CONFIRMED", "CANCELED"):
            print(f"settled -> {o['status']}")
            break
        time.sleep(0.3)

    # Spans are batched by the reporter before being flushed, so the last ones arrive after the saga
    # has finished. Asking Zipkin immediately returns a truncated trace and makes the pipeline look
    # broken when it is merely asynchronous - which is the same mistake as measuring a cache by
    # whether Redis answered.
    print(f"waiting {args.settle:.0f}s for spans to flush...")
    time.sleep(args.settle)

    # QUERY BY SPAN NAME, not by service.
    #
    # `?serviceName=order-service` returns the most recent traces that touched it, and on an idle
    # platform that list is entirely Eureka heartbeats and Prometheus scraping /actuator/prometheus
    # every ten seconds. The order path was pushed off the end of a 40-trace page by monitoring
    # traffic - the observability stack hiding the thing it was installed to observe.
    #
    # Searching by the span name of the write itself is exact and cheap.
    q = urllib.parse.quote("http post /api/orders")
    _, traces = call("GET",
                     f"{ZIPKIN}/api/v2/traces?serviceName=order-service&spanName={q}&limit=10")
    if not traces:
        print("no POST /api/orders traces in Zipkin - is the observability profile up?")
        return 1

    def services_in(trace):
        return {s.get("localEndpoint", {}).get("serviceName") for s in trace}

    # Prefer the trace tagged with this order key; fall back to the most recent, widest one.
    # The tag is set by order-service on the span that creates the order - see OrderServiceImpl.
    tagged = [t for t in traces if order_key in json.dumps(t)]
    pool = tagged or traces
    target = max(pool, key=lambda t: (len(services_in(t)),
                                      max(s["timestamp"] for s in t)))
    if not tagged:
        print("(no trace carried the order key as a tag - showing the widest recent order trace)")

    render(target, order_key)
    return 0


def render(spans, order_key):
    spans = sorted(spans, key=lambda s: s.get("timestamp", 0))
    t0 = spans[0]["timestamp"]
    total_us = max(s["timestamp"] + s.get("duration", 0) for s in spans) - t0

    trace_id = spans[0]["traceId"]
    services = sorted({s.get("localEndpoint", {}).get("serviceName", "?") for s in spans})

    print()
    print(f"trace {trace_id}")
    print(f"  {len(spans)} spans across {len(services)} services: {', '.join(services)}")
    print(f"  total {total_us / 1000:.0f} ms")
    print(f"  http://localhost:9411/zipkin/traces/{trace_id}")
    print()

    width = 46
    print(f"{'service':<18} {'span':<34} {'start':>7} {'dur':>8}")
    print("-" * (18 + 34 + 7 + 8 + 3 + width + 2))
    for s in spans:
        svc = s.get("localEndpoint", {}).get("serviceName", "?")
        name = s.get("name", "?")[:34]
        start_us = s["timestamp"] - t0
        dur_us = s.get("duration", 0)

        # The bar is drawn against the WHOLE trace, so an async hop that starts 500 ms in looks like
        # what it is - a gap - rather than being normalised away.
        left = int(width * start_us / total_us) if total_us else 0
        span_w = max(1, int(width * dur_us / total_us)) if total_us else 1
        bar = " " * left + "#" * min(span_w, width - left)

        print(f"{svc:<18} {name:<34} {start_us / 1000:6.0f}ms {dur_us / 1000:7.1f}ms  |{bar:<{width}}|")

    print()
    kinds = {}
    for s in spans:
        kinds[s.get("kind", "INTERNAL")] = kinds.get(s.get("kind", "INTERNAL"), 0) + 1
    print("span kinds:", ", ".join(f"{k} x{v}" for k, v in sorted(kinds.items())))
    print(f"order key present in trace: {order_key in json.dumps(spans)}")


if __name__ == "__main__":
    raise SystemExit(main())
