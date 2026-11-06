#!/usr/bin/env python3
"""Drive one real purchase through the running platform, and check what it did.

Not a unit test and not a replacement for one. This is the "does the assembled system actually work"
check - the thing a passing test suite cannot tell you, because every test in this repository runs
against its own service with its dependencies stubbed or containerised. It goes through the gateway
on :8080, exactly as a browser would, and it asserts on state in the databases afterwards rather than
on the responses alone.

    python scripts/smoke_test.py
    python scripts/smoke_test.py --base http://localhost:8080

Exit code 0 means every check passed. Any failure prints what was expected and what came back.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

PASS = "PASS"
FAIL = "FAIL"

results: list[tuple[str, str, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    results.append((PASS if ok else FAIL, name, detail))
    print(f"  [{PASS if ok else FAIL}] {name}" + (f"  -- {detail}" if detail else ""))
    return ok


def call(method: str, url: str, token: str | None = None, body: dict | None = None,
         expect: int | None = None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read().decode()
            status = response.status
    except urllib.error.HTTPError as err:
        payload = err.read().decode()
        status = err.code
    except urllib.error.URLError as err:
        return None, f"unreachable: {err.reason}"

    parsed = json.loads(payload) if payload.strip().startswith(("{", "[")) else payload
    if expect is not None and status != expect:
        return status, parsed
    return status, parsed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080",
                        help="the gateway's address; every call goes through it")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    print(f"Smoke test against {base}\n")
    started = time.perf_counter()

    # -- 1. The edge refuses an unauthenticated write BEFORE any service is touched ----------------
    print("1. Edge authentication")
    status, _ = call("POST", f"{base}/api/orders", body={"lines": []})
    check("POST /api/orders with no token -> 401 at the gateway", status == 401, f"got {status}")

    status, _ = call("GET", f"{base}/api/products?size=1")
    check("GET /api/products anonymously -> 200 (browsing is public)", status == 200, f"got {status}")

    # -- 2. Register and log in --------------------------------------------------------------------
    print("\n2. Identity")
    username = f"smoke_{uuid.uuid4().hex[:10]}"
    status, body = call("POST", f"{base}/api/auth/register", body={
        "username": username, "email": f"{username}@example.invalid", "password": "smoke-test-pw"})
    check("POST /api/auth/register -> 201", status == 201, f"got {status}: {body}")

    status, body = call("POST", f"{base}/api/auth/login", body={
        "username": username, "password": "smoke-test-pw"})
    ok = check("POST /api/auth/login -> 200 with a token", status == 200, f"got {status}")
    if not ok:
        return report(started)
    token = body["token"]

    status, body = call("POST", f"{base}/api/auth/login", body={
        "username": username, "password": "wrong"})
    check("login with a wrong password -> 401", status == 401, f"got {status}")

    status, body = call("GET", f"{base}/api/users/me", token=token)
    check("GET /api/users/me -> the caller, from the token",
          status == 200 and body.get("username") == username, f"got {status}: {body}")

    # -- 3. Find something to buy ------------------------------------------------------------------
    print("\n3. Catalogue")
    status, page = call("GET", f"{base}/api/products?size=25")
    ok = check("GET /api/products -> a page of real products",
               status == 200 and page.get("totalElements", 0) > 0,
               f"{page.get('totalElements') if isinstance(page, dict) else page} products")
    if not ok:
        return report(started)

    # PICK A PRODUCT WITH STOCK, rather than the first one on the page.
    #
    # The first version took page[0] and it worked until it did not: repeated runs against a
    # development database drove that one product to available = 0, and the test then failed with
    # "the saga drove the order to CANCELED (Out of stock)" - which is the system behaving CORRECTLY
    # and the test asserting something it had not established.
    #
    # A smoke test has to be robust to the state left by every previous run, because on a real
    # machine that state is arbitrary. Checking the precondition is the difference between a test
    # that reports defects and one that reports history.
    product = None
    stock_before = None
    for candidate in page["content"]:
        if not candidate.get("price"):
            continue
        status, stock = call("GET", f"{base}/api/inventory/{candidate['productKey']}")
        if status == 200 and stock["available"] >= 2:
            product, stock_before = candidate, stock
            break

    ok = check("a priced product with at least 2 available exists",
               product is not None,
               "none on page 1 - every candidate is out of stock or unpriced")
    if not ok:
        return report(started)

    product_key = product["productKey"]
    print(f"       buying {product_key}  ({product['category']}, {product['price']})")
    print(f"       stock before: on_hand={stock_before['onHand']} "
          f"reserved={stock_before['reserved']} available={stock_before['available']}")

    # -- 4. Place the order ------------------------------------------------------------------------
    print("\n4. Order")
    status, order = call("POST", f"{base}/api/orders", token=token,
                         body={"lines": [{"productKey": product_key, "quantity": 2}]})
    ok = check("POST /api/orders -> 201", status == 201, f"got {status}: {order}")
    if not ok:
        return report(started)
    order_key = order["orderKey"]
    print(f"       order {order_key}  status={order['status']}  total={order['totalAmount']}")

    # PENDING, NOT AWAITING_PAYMENT - and this assertion changed in Step 5.
    #
    # Until the outbox landed, POST /api/orders reserved stock synchronously inside the request and
    # came back AWAITING_PAYMENT. It now returns as soon as the order and its outbox row are durable,
    # and the saga runs afterwards. PENDING is the honest status: it reports what has finished.
    #
    # This test was not updated in the same commit, and it went on "passing" for exactly as long as
    # it took to run it again - three checks then failed with "got PENDING". A smoke test asserting
    # the previous architecture is worse than no smoke test: it fails for a reason that is not a
    # defect, and that trains people to ignore it.
    check("the order comes back PENDING - durable and announced, nothing reserved yet",
          order["status"] == "PENDING", f"got {order['status']}")
    check("the server priced the line (the client sent no price)",
          order["items"][0]["unitPrice"] == product["price"],
          f"{order['items'][0]['unitPrice']} vs catalogue {product['price']}")

    # The saga is asynchronous now, so the test has to wait for it. Polling for the expected state
    # rather than sleeping a fixed amount is what keeps this deterministic on a slow machine.
    print("       waiting for the saga...")
    saga_deadline = time.time() + 30
    final = order
    while time.time() < saga_deadline:
        _, final = call("GET", f"{base}/api/orders/key/{order_key}", token=token)
        if final["status"] in ("CONFIRMED", "CANCELED"):
            break
        time.sleep(0.1)

    check("the saga drove the order to CONFIRMED",
          final["status"] == "CONFIRMED",
          f"got {final['status']} ({final.get('failureReason')})")

    # Stock settles one hop after the status changes, so this waits too.
    stock_deadline = time.time() + 15
    stock_after = stock_before
    while time.time() < stock_deadline:
        _, stock_after = call("GET", f"{base}/api/inventory/{product_key}")
        if stock_after["onHand"] == stock_before["onHand"] - 2:
            break
        time.sleep(0.1)

    check("stock left the warehouse: on_hand fell by 2, no reservation left behind",
          stock_after["onHand"] == stock_before["onHand"] - 2
          and stock_after["reserved"] == stock_before["reserved"],
          f"on_hand {stock_before['onHand']} -> {stock_after['onHand']}, "
          f"reserved {stock_before['reserved']} -> {stock_after['reserved']}")

    # -- 5. Somebody else must not be able to read it ----------------------------------------------
    print("\n5. Authorization on the order")
    other = f"smoke_{uuid.uuid4().hex[:10]}"
    call("POST", f"{base}/api/auth/register", body={
        "username": other, "email": f"{other}@example.invalid", "password": "smoke-test-pw"})
    _, other_login = call("POST", f"{base}/api/auth/login", body={
        "username": other, "password": "smoke-test-pw"})
    status, _ = call("GET", f"{base}/api/orders/key/{order_key}", token=other_login["token"])
    check("another user reading this order -> 403", status == 403, f"got {status}")

    status, _ = call("GET", f"{base}/api/orders/key/{order_key}", token=token)
    check("the owner reading their own order -> 200", status == 200, f"got {status}")

    # -- 6. Payment, made by the saga rather than by this test ------------------------------------
    print("\n6. Payment")
    status, all_payments = call("GET", f"{base}/api/payments/order/{order_key}", token=token)
    ok = check("the saga captured exactly one payment",
               status == 200 and isinstance(all_payments, list) and len(all_payments) == 1
               and all_payments[0]["status"] == "CAPTURED",
               f"got {status}: {all_payments}")
    if not ok:
        return report(started)
    payment = all_payments[0]

    # THE IDEMPOTENCY CHECK, and it is stronger than it was.
    #
    # Before Step 5 this test made the payment itself and then repeated the request. Now the saga
    # makes it, so replaying the charge exercises the real path a redelivered Kafka event takes: the
    # derived idempotency key (orderKey:sequential) finds the existing row and returns the ORIGINAL
    # result instead of charging again.
    status, repeat = call("POST", f"{base}/api/payments", token=token, body={
        "orderKey": order_key, "sequential": 1, "method": "CREDIT_CARD",
        "installments": 1, "amount": order["totalAmount"]})
    check("replaying the charge returns the ORIGINAL row, not a second charge",
          status == 200 and repeat.get("id") == payment.get("id"),
          f"saga id={payment.get('id')} replay id={repeat.get('id')}")

    status, all_payments = call("GET", f"{base}/api/payments/order/{order_key}", token=token)
    check("still exactly one payment row for the order",
          status == 200 and len(all_payments) == 1,
          f"got {len(all_payments) if status == 200 else status}")

    return report(started)


def admin_login(base: str) -> str | None:
    """The seeded admin. Created by scripts/create_admin.py, which is idempotent."""
    status, body = call("POST", f"{base}/api/auth/login",
                        body={"username": "admin", "password": "admin-dev-password"})
    return body["token"] if status == 200 else None


def report(started: float) -> int:
    failed = [r for r in results if r[0] == FAIL]
    elapsed = time.perf_counter() - started
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed in {elapsed:.1f}s")
    if failed:
        print("\nFailures:")
        for _, name, detail in failed:
            print(f"  - {name}: {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
