#!/usr/bin/env python3
"""Register the Debezium PostgreSQL source connectors.

Idempotent: a connector that already exists is updated in place (`PUT /config`) rather than failing
with a 409, so this can be re-run after editing a definition.

    python scripts/register_connectors.py            # create or update all
    python scripts/register_connectors.py --status   # what is running, and its tasks
    python scripts/register_connectors.py --delete    # remove them and drop the replication slots

WHY THREE CONNECTORS AND NOT ONE
--------------------------------
One per source database, because a Debezium Postgres connector is bound to one database and one
replication slot. That is not a limitation to work around - it is the database-per-service boundary
showing up in the CDC layer, and it is a feature: catalog's connector can be restarted, re-snapshotted
or deleted without touching order's.

WHAT A REPLICATION SLOT COSTS, stated because it is the way CDC takes a production database down.
A slot makes Postgres RETAIN write-ahead log segments until the consumer has acknowledged them. Stop
the connector and leave the slot, and the WAL grows without bound until the disk fills - which looks
like a storage incident, not a CDC one. That is why --delete drops the slots as well as the
connectors, and why a real deployment monitors `pg_replication_slots.active`.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

CONNECT = "http://localhost:8093"

# The `dbserver` prefix becomes the topic prefix: `catalog.public.products`. Naming it after the
# LOGICAL source rather than after the host means moving the database does not rename every topic.
CONNECTORS = {
    "catalog-source": {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "database.hostname": "catalog-db",
        "database.port": "5432",
        "database.user": "catalogdb",
        "database.password": "catalogdb",
        "database.dbname": "catalogdb",
        "topic.prefix": "catalog",
        "table.include.list": "public.products",
        "slot.name": "debezium_catalog",
        "publication.name": "dbz_catalog",
    },
    "user-source": {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "database.hostname": "user-db",
        "database.port": "5432",
        "database.user": "userdb",
        "database.password": "userdb",
        "database.dbname": "userdb",
        "topic.prefix": "user",
        "table.include.list": "public.users",
        # Only the columns the warehouse needs. A CDC stream that carries a password hash into Kafka
        # - where it is retained, replicated and readable by every consumer - is a data-protection
        # incident waiting for an audit, and `column.exclude.list` is the one line that prevents it.
        "column.exclude.list": "public.users.password",
        "slot.name": "debezium_user",
        "publication.name": "dbz_user",
    },
    "order-source": {
        "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
        "database.hostname": "order-db",
        "database.port": "5432",
        "database.user": "orderdb",
        "database.password": "orderdb",
        "database.dbname": "orderdb",
        "topic.prefix": "order",
        "table.include.list": "public.orders,public.order_items",
        "slot.name": "debezium_order",
        "publication.name": "dbz_order",
    },
}

COMMON = {
    "plugin.name": "pgoutput",

    # `initial`: snapshot every included table, then stream changes. The alternative (`never`) starts
    # from "now" and leaves the warehouse missing all history - which for a warehouse is not a
    # trade-off, it is a broken warehouse. The snapshot is what loads the 100K historical orders.
    "snapshot.mode": "initial",

    "tasks.max": "1",

    # Money columns are numeric(12,2). Debezium's default encoding for those is a base64 VariableScale
    # Decimal - correct, lossless, and unusable from a Python consumer without decoding it by hand.
    # `double` loses precision above 2^53 and is wrong for currency; `string` keeps the exact digits
    # and lets the sink parse them into a Decimal. This is the single most common Debezium surprise.
    "decimal.handling.mode": "string",

    # Timestamps as ISO-8601 strings rather than epoch microseconds, for the same reason: the sink
    # writes them into MySQL DATETIME columns, and a string round-trips without unit confusion.
    "time.precision.mode": "connect",

    # Kafka's default is 1MB and a snapshot batch of wide rows exceeds it. The failure is a connector
    # that fails a minute into the snapshot with RecordTooLargeException.
    "producer.override.max.request.size": "10485760",

    # Heartbeats matter MORE than they look on a low-traffic database. Debezium only advances the
    # replication slot when it emits something; a quiet database therefore holds WAL forever even
    # though nothing is happening. A heartbeat is what lets the slot move.
    "heartbeat.interval.ms": "10000",
}


def request(method: str, path: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(CONNECT + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text.strip() else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, (json.loads(text) if text.strip().startswith("{") else text)
    except urllib.error.URLError as e:
        print(f"Kafka Connect unreachable at {CONNECT}: {e.reason}", file=sys.stderr)
        print("Start it with:  docker compose --profile cdc up -d kafka-connect", file=sys.stderr)
        raise SystemExit(1)


#: The topics Debezium will publish to, and the heartbeat topic each connector needs.
#
# THESE HAVE TO BE CREATED EXPLICITLY, and finding that out cost a debugging session. The broker runs
# with auto.create.topics.enable=false (a deliberate choice: a typo in a topic name would otherwise
# become a brand new topic that nothing reads). Debezium relies on auto-creation, so with it off the
# connectors report RUNNING, the tasks report RUNNING, and the log fills with
#
#     Error while fetching metadata ... {catalog.public.products=UNKNOWN_TOPIC_OR_PARTITION}
#
# once a second, forever, with no failed task and no error state. A connector that is "running" and
# producing nothing is the worst diagnostic shape there is.
#
# Creating them here also lets the partition count be a decision rather than a broker default: keyed
# by primary key, so three partitions give parallelism while keeping every change to one row in
# order.
CDC_TOPICS = [
    "catalog.public.products",
    "user.public.users",
    "order.public.orders",
    "order.public.order_items",
    "__debezium-heartbeat.catalog",
    "__debezium-heartbeat.user",
    "__debezium-heartbeat.order",
]


def create_topics() -> None:
    import subprocess
    for topic in CDC_TOPICS:
        subprocess.run([
            "docker", "exec", "ecommerce-kafka",
            "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092",
            "--create", "--if-not-exists", "--topic", topic,
            "--partitions", "3", "--replication-factor", "1",
        ], capture_output=True, text=True)
    print(f"{len(CDC_TOPICS)} CDC topics created (or already present)")


def register() -> int:
    create_topics()
    existing = request("GET", "/connectors")[1] or []
    for name, specific in CONNECTORS.items():
        config = {**COMMON, **specific}
        if name in existing:
            status, body = request("PUT", f"/connectors/{name}/config", config)
            print(f"{name}: updated ({status})")
        else:
            status, body = request("POST", "/connectors", {"name": name, "config": config})
            print(f"{name}: created ({status})")
        if status >= 400:
            print(f"  {body}", file=sys.stderr)
    return 0


def status() -> int:
    names = request("GET", "/connectors")[1] or []
    if not names:
        print("no connectors registered")
        return 0
    for name in names:
        _, s = request("GET", f"/connectors/{name}/status")
        tasks = ", ".join(f"task {t['id']}={t['state']}" for t in s.get("tasks", []))
        print(f"{name:16s} {s['connector']['state']:8s} {tasks}")
        for t in s.get("tasks", []):
            if t.get("trace"):
                print("  " + t["trace"].splitlines()[0])
    return 0


def delete() -> int:
    for name in request("GET", "/connectors")[1] or []:
        request("DELETE", f"/connectors/{name}")
        print(f"{name}: deleted")
    # DROPPING THE SLOTS IS NOT OPTIONAL. Deleting a connector leaves its replication slot behind,
    # and an inactive slot makes Postgres retain WAL forever - a disk-full incident that looks
    # nothing like a CDC problem.
    import subprocess
    for container, user, db, slot in [
        ("ecommerce-catalog-db", "catalogdb", "catalogdb", "debezium_catalog"),
        ("ecommerce-user-db", "userdb", "userdb", "debezium_user"),
        ("ecommerce-order-db", "orderdb", "orderdb", "debezium_order"),
    ]:
        subprocess.run(["docker", "exec", container, "psql", "-U", user, "-d", db, "-c",
                        f"SELECT pg_drop_replication_slot('{slot}') "
                        f"WHERE EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name='{slot}')"],
                       capture_output=True)
        print(f"{slot}: dropped if present")
    return 0


def main() -> int:
    global CONNECT
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--status", action="store_true")
    parser.add_argument("--delete", action="store_true")
    parser.add_argument("--connect", default=CONNECT)
    args = parser.parse_args()

    CONNECT = args.connect.rstrip("/")

    if args.status:
        return status()
    if args.delete:
        return delete()
    return register()


if __name__ == "__main__":
    raise SystemExit(main())
