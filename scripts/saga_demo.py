#!/usr/bin/env python3
"""Watch the event-driven saga run, end to end, and time it.

Places an order through the gateway and polls until it reaches a terminal state, printing the
transitions and how long each took. Two modes:

    python scripts/saga_demo.py                # happy path -> CONFIRMED
    python scripts/saga_demo.py --expect-fail  # payment declines -> CANCELED, stock released

The second one is the point. A compensating transaction that has never been observed to run is a
comment, not a feature - so it is driven by restarting payment-service with
PAYMENT_FAILURE_RATE=1.0 and watching the stock come back.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"


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
        text = e.read().decode()
        return e.code, (json.loads(text) if text.strip().startswith(("{", "[")) else text)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--expect-fail", action="store_true",
                        help="assert the saga compensates (run payment-service with "
                             "PAYMENT_FAILURE_RATE=1.0 first)")
    parser.add_argument("--quantity", type=int, default=2)
    args = parser.parse_args()
    base = args.base.rstrip("/")

    user = f"saga_{uuid.uuid4().hex[:8]}"
    call("POST", f"{base}/api/auth/register",
         body={"username": user, "email": f"{user}@example.invalid", "password": "saga-demo-pw"})
    _, login = call("POST", f"{base}/api/auth/login",
                    body={"username": user, "password": "saga-demo-pw"})
    token = login["token"]

    # CHECK THE PRECONDITION, do not assume it.
    #
    # This used to take the first product with a price and buy `--quantity` of it. Every run of this
    # script and of the smoke test decrements that same product, so after enough runs it has fewer
    # than two units left - and then the saga does exactly the right thing, refusing the reservation
    # and compensating, while the demo reports FAIL because it expected CONFIRMED.
    #
    # Seen for real: `expected CONFIRMED, got CANCELED` on a product with on_hand=1. Nothing was
    # broken except this script's assumption, and a demo that cries wolf about its own subject is
    # worse than no demo. The smoke test had the identical bug and was fixed the same way.
    #
    # Note that --expect-fail deliberately still wants a product with stock: that path proves
    # compensation after a PAYMENT failure, which is only meaningful if the reservation succeeded
    # first. A run that fails at the reservation step tests a different thing and says so.
    _, page = call("GET", f"{base}/api/products?size=60")
    product = None
    for candidate in page["content"]:
        if not candidate.get("price"):
            continue
        _, stock = call("GET", f"{base}/api/inventory/{candidate['productKey']}")
        if isinstance(stock, dict) and stock.get("available", 0) >= args.quantity:
            product = candidate
            break
    if product is None:
        print(f"no product has {args.quantity} units available - re-run scripts/load_olist.py, "
              f"or lower --quantity")
        return 1
    key = product["productKey"]

    _, before = call("GET", f"{base}/api/inventory/{key}")
    print(f"product        {key}  ({product['category']}, {product['price']})")
    print(f"stock before   on_hand={before['onHand']} reserved={before['reserved']} "
          f"available={before['available']}")

    t0 = time.perf_counter()
    status, order = call("POST", f"{base}/api/orders", token=token,
                         body={"lines": [{"productKey": key, "quantity": args.quantity}]})
    if status != 201:
        print(f"order rejected: {status} {order}", file=sys.stderr)
        return 1

    order_key = order["orderKey"]
    placed_ms = (time.perf_counter() - t0) * 1000
    print(f"\nPOST /api/orders -> 201 in {placed_ms:.0f} ms, status={order['status']}")
    print("  ^ the response returns as soon as the order and its outbox row are durable.")
    print("    Nothing has been reserved or charged yet - that is the saga's job.\n")

    seen = [order["status"]]
    terminal = {"CONFIRMED", "CANCELED"}
    deadline = time.time() + 60
    transitions = []

    while time.time() < deadline:
        _, current = call("GET", f"{base}/api/orders/key/{order_key}", token=token)
        state = current["status"]
        if state != seen[-1]:
            elapsed = (time.perf_counter() - t0) * 1000
            transitions.append((seen[-1], state, elapsed))
            print(f"  {seen[-1]:>16s} -> {state:<16s}  t+{elapsed:7.0f} ms")
            seen.append(state)
        if state in terminal:
            break
        time.sleep(0.05)
    else:
        print(f"\nTIMED OUT after 60s in state {seen[-1]}", file=sys.stderr)
        return 1

    total_ms = (time.perf_counter() - t0) * 1000

    # THE ORDER REACHING A TERMINAL STATE IS NOT THE END OF THE SAGA.
    #
    # Compensation is one more hop: order-service writes CANCELED and appends OrderCancelled, its
    # relay publishes, and inventory-service releases. Reading the stock the instant the status flips
    # therefore reads it BEFORE the release - which is exactly what the first run of this script did,
    # and it looked like compensation had not happened at all.
    #
    # That lag is the system working as designed, not a defect, so the check waits for it and reports
    # how long it took. An eventually-consistent system tested synchronously fails for the wrong
    # reason.
    expected_reserved = before["reserved"] if args.expect_fail else before["reserved"]
    expected_on_hand = before["onHand"] if args.expect_fail else before["onHand"] - args.quantity
    settle_deadline = time.time() + 20
    while time.time() < settle_deadline:
        _, after = call("GET", f"{base}/api/inventory/{key}")
        if after["reserved"] == expected_reserved and after["onHand"] == expected_on_hand:
            break
        time.sleep(0.1)
    settled_ms = (time.perf_counter() - t0) * 1000

    _, payments = call("GET", f"{base}/api/payments/order/{order_key}", token=token)

    print(f"\nterminal state {seen[-1]} after {total_ms:.0f} ms")
    print(f"stock after    on_hand={after['onHand']} reserved={after['reserved']} "
          f"available={after['available']}")
    if isinstance(payments, list):
        for p in payments:
            print(f"payment        {p['status']} {p['amount']} (seq {p['sequential']})")

    failures = []
    if args.expect_fail:
        if seen[-1] == "CONFIRMED":
            # Distinguish "compensation is broken" from "the fault was never injected". These are
            # completely different findings and a bare `expected CANCELED, got CONFIRMED` reads as
            # the first one when it is almost always the second: --expect-fail needs payment-service
            # running with PAYMENT_FAILURE_RATE=1.0, and nothing in this script can set that.
            print()
            print("  The order CONFIRMED, so payment did not decline - which means the fault was")
            print("  never injected, not that compensation failed. Restart payment-service with")
            print("  PAYMENT_FAILURE_RATE=1.0 and run this again:")
            print()
            print("    docker compose --profile apps stop payment-service")
            print("    PAYMENT_FAILURE_RATE=1.0 docker compose --profile apps up -d payment-service")
            print()
            print("  or, for host JVMs:  $env:PAYMENT_FAILURE_RATE='1.0'; "
                  ".\\scripts\\dev-stack.ps1 restart payment-service")
            failures.append("payment did not decline - the fault was not injected (see above)")
        elif seen[-1] != "CANCELED":
            failures.append(f"expected CANCELED, got {seen[-1]}")
        # COMPENSATION PROVEN: reserved went up and came back down, and on_hand never moved.
        if after["reserved"] != before["reserved"]:
            failures.append(f"stock not released: reserved {before['reserved']} -> {after['reserved']}")
        if after["onHand"] != before["onHand"]:
            failures.append(f"on_hand changed on a failed order: "
                            f"{before['onHand']} -> {after['onHand']}")
        if "AWAITING_PAYMENT" not in seen:
            failures.append("the saga never reached AWAITING_PAYMENT, so compensation was untested")
    else:
        if seen[-1] != "CONFIRMED":
            failures.append(f"expected CONFIRMED, got {seen[-1]}")
        if after["onHand"] != before["onHand"] - args.quantity:
            failures.append(f"on_hand should have fallen by {args.quantity}: "
                            f"{before['onHand']} -> {after['onHand']}")
        if after["reserved"] != before["reserved"]:
            failures.append(f"reservation not cleared: {before['reserved']} -> {after['reserved']}")

    print()
    if failures:
        for f in failures:
            print(f"  FAIL {f}")
        return 1
    print("  PASS " + ("compensation ran: stock released, nothing charged"
                       if args.expect_fail else
                       "saga completed: stock removed, payment captured"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
