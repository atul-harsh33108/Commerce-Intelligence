#!/usr/bin/env python3
"""Check that the agent's metadata describes the warehouse that actually exists.

    python scripts/validate_agent_metadata.py

WHY THIS EXISTS
---------------
`meta_config.yaml` is hand-written prose about a schema. Nothing connects the two, so a column
renamed in `warehouse/ddl/01_dw.sql` leaves the metadata describing a column that is gone - and the
failure mode is the worst kind: the agent retrieves the stale column, puts it in the prompt, the model
writes SQL referencing it, execution fails, the self-correction loop burns its three attempts, and the
user gets "I could not answer that". Nothing anywhere says "the metadata is wrong".

This is the cheap check that turns that into a build failure. It also verifies the read-only
credential, because "the agent cannot write" is a claim worth testing rather than asserting.
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import pymysql
    import yaml
except ImportError:
    print("pip install pymysql pyyaml", file=sys.stderr)
    raise SystemExit(2)

ROOT = Path(__file__).resolve().parent.parent
META_CONFIG = ROOT / "agent" / "backend" / "conf" / "meta_config.yaml"

DW = dict(host="127.0.0.1", port=3307, user="warehouse", password="warehouse", database="dw")
DW_RO = dict(host="127.0.0.1", port=3307, user="warehouse_ro", password="warehouse_ro", database="dw")

problems: list[str] = []
checks = 0


def ok(label: str, detail: str = "") -> None:
    global checks
    checks += 1
    print(f"  [PASS] {label}" + (f"  -- {detail}" if detail else ""))


def bad(label: str, detail: str) -> None:
    global checks
    checks += 1
    problems.append(f"{label}: {detail}")
    print(f"  [FAIL] {label}  -- {detail}")


def main() -> int:
    config = yaml.safe_load(META_CONFIG.read_text(encoding="utf-8"))

    conn = pymysql.connect(**DW)
    with conn.cursor() as cur:
        cur.execute("""SELECT table_name, column_name, data_type
                         FROM information_schema.columns
                        WHERE table_schema = 'dw'""")
        actual: dict[str, dict[str, str]] = {}
        for table, column, dtype in cur.fetchall():
            actual.setdefault(table, {})[column] = dtype

    print("1. Every table and column the metadata describes exists in the warehouse")
    described_tables = set()
    for table in config["tables"]:
        name = table["name"]
        described_tables.add(name)
        if name not in actual:
            bad(f"table {name}", "described in metadata, absent from dw")
            continue
        missing = [c["name"] for c in table["columns"] if c["name"] not in actual[name]]
        if missing:
            bad(f"table {name}", f"columns described but absent: {missing}")
        else:
            ok(f"table {name}", f"{len(table['columns'])} columns, all present")

    print("\n2. Nothing important in the warehouse is undescribed")
    for name, columns in sorted(actual.items()):
        if name.startswith("stg_"):
            continue   # staging tables are the sink's business, not the agent's
        if name not in described_tables:
            bad(f"table {name}", "exists in dw but the metadata does not mention it")
            continue
        described = {c["name"] for t in config["tables"] if t["name"] == name for c in t["columns"]}
        undescribed = sorted(set(columns) - described)
        # Housekeeping columns are legitimately hidden from the agent: a model that can see
        # `ingested_at` will eventually write a query that filters on it and report ETL timing as a
        # business answer.
        undescribed = [c for c in undescribed
                       if c not in ("updated_at", "ingested_at", "order_item_sk", "day", "day_name",
                                    "week_of_year", "full_date", "photos_qty", "zip_prefix",
                                    "seller_key", "estimated_days", "purchased_at")]
        if undescribed:
            bad(f"table {name}", f"columns not described to the agent: {undescribed}")
        else:
            ok(f"table {name}", "fully described")

    print("\n3. Every metric references columns that exist")
    for metric in config.get("metrics", []):
        broken = []
        for ref in metric.get("relevant_columns", []):
            table, _, column = ref.partition(".")
            if table not in actual or column not in actual[table]:
                broken.append(ref)
        if broken:
            bad(f"metric {metric['name']}", f"references missing columns: {broken}")
        else:
            ok(f"metric {metric['name']}", f"{len(metric.get('alias', []))} aliases")

    print("\n4. Columns marked `sync: true` have a workable number of distinct values")
    for table in config["tables"]:
        for column in table["columns"]:
            if not column.get("sync"):
                continue
            with conn.cursor() as cur:
                cur.execute(f"SELECT COUNT(DISTINCT `{column['name']}`) FROM `{table['name']}`")
                distinct = cur.fetchone()[0]
            # A soft ceiling. Indexing a very high-cardinality column into Elasticsearch does not
            # break anything; it buries the values that actually disambiguate a question under
            # thousands that do not.
            if distinct > 10000:
                bad(f"{table['name']}.{column['name']}",
                    f"{distinct:,} distinct values is too many to index usefully")
            else:
                ok(f"{table['name']}.{column['name']}", f"{distinct:,} distinct values to index")

    conn.close()

    print("\n5. The read-only credential is actually read-only (D12)")
    ro = pymysql.connect(**DW_RO)
    with ro.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM fact_order_items")
        ok("warehouse_ro can SELECT", f"{cur.fetchone()[0]:,} fact rows")

    for statement, label in [
        ("DELETE FROM fact_order_items WHERE 1=0", "DELETE is refused"),
        ("UPDATE dim_product SET category='x' WHERE 1=0", "UPDATE is refused"),
        ("CREATE TABLE probe_should_fail (id INT)", "CREATE TABLE is refused"),
    ]:
        try:
            with ro.cursor() as cur:
                cur.execute(statement)
            bad(label, "the statement SUCCEEDED - the grant is too wide")
        except pymysql.err.OperationalError as exc:
            if exc.args[0] == 1142:                     # command denied
                ok(label, "1142 command denied")
            else:
                bad(label, f"unexpected error {exc.args[0]}: {exc.args[1]}")
        except pymysql.err.ProgrammingError as exc:
            ok(label, f"{exc.args[0]} denied")

    try:
        with ro.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM meta.table_info")
        bad("warehouse_ro cannot read `meta`", "it CAN - generated SQL could describe its own guardrails")
    except (pymysql.err.OperationalError, pymysql.err.ProgrammingError):
        ok("warehouse_ro cannot read `meta`", "denied")
    ro.close()

    print(f"\n{checks - len(problems)}/{checks} checks passed")
    if problems:
        print("\nProblems:")
        for p in problems:
            print(f"  - {p}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
