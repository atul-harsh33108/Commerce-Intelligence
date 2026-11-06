#!/usr/bin/env python3
"""Rebuild the agent's knowledge base from meta_config.yaml, from a clean slate.

    python scripts/rebuild_agent_knowledge.py

WHY THIS EXISTS
---------------
`build_meta_knowledge` INSERTs; it does not upsert and it does not clear. Running it a second time
fails on the first row:

    IntegrityError: (1062, "Duplicate entry 'dim_date.date_id' for key 'column_info.PRIMARY'")

which is fine the first time anyone builds a knowledge base and useless from then on - and editing
metadata is not a one-time act. Every time a column description changes, the index has to be rebuilt
to match, or the agent retrieves the OLD description and the change silently does nothing. That
failure mode is worse than the crash: the build appears to have worked.

So this clears all three stores first, in the order that leaves nothing half-built:

    meta.*           MySQL     the descriptions themselves
    Qdrant           vectors   embeddings of columns and metrics
    Elasticsearch    values    the distinct values behind `sync: true`

It is deliberately NOT part of the agent's own code - clearing a store is an operational act, and a
builder that silently truncates is a builder that will one day truncate production.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import urllib.error
import urllib.request

META_TABLES = ["column_metric", "column_info", "metric_info", "table_info"]
QDRANT_COLLECTIONS = ["data-agent-column", "data-agent-metric"]
ES_INDEX = "ecommerce_agent_values"


def run(cmd: list[str]) -> tuple[int, str]:
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, (p.stdout + p.stderr).strip()


def http(method: str, url: str) -> str:
    req = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return f"{r.status}"
    except urllib.error.HTTPError as e:
        return f"{e.code}"
    except urllib.error.URLError as e:
        return f"unreachable ({e.reason})"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", default="conf/meta_config.yaml")
    parser.add_argument("--qdrant", default="http://localhost:6333")
    parser.add_argument("--es", default="http://localhost:9200")
    parser.add_argument("--skip-clear", action="store_true")
    args = parser.parse_args()

    if not args.skip_clear:
        print("Clearing the metadata catalogue")
        # Truncated in dependency order; column_metric references the other two.
        sql = " ".join(f"TRUNCATE TABLE meta.{t};" for t in META_TABLES)
        code, out = run(["docker", "exec", "ecommerce-warehouse-db",
                         "mysql", "-uroot", "-pwarehouse", "-e", sql])
        print(f"  MySQL meta.*  {'cleared' if code == 0 else 'FAILED: ' + out[:200]}")

        for c in QDRANT_COLLECTIONS:
            print(f"  Qdrant {c}  {http('DELETE', f'{args.qdrant}/collections/{c}')}")

        print(f"  Elasticsearch {ES_INDEX}  {http('DELETE', f'{args.es}/{ES_INDEX}')}")

        # THE RETRIEVAL CACHE, and forgetting it makes a metadata change invisible.
        #
        # The agent caches Qdrant recalls and embeddings in Redis db 1. Rebuild the index without
        # flushing it and the next question is answered from the PREVIOUS descriptions - the build
        # succeeds, the index is correct, and the behaviour does not change. That is a worse failure
        # than a crash because everything reports success.
        #
        # db 1 only, never FLUSHALL: db 0 holds catalog-service's cache and the gateway's rate-limit
        # counters, and taking those out to reindex an agent is collateral damage nobody asked for.
        code, out = run(["docker", "exec", "ecommerce-redis", "redis-cli", "-n", "1", "FLUSHDB"])
        print(f"  Redis db1 (agent retrieval cache)  {out if out else 'cleared'}")
        print()

    print("Building")
    code, out = run(["docker", "compose", "--profile", "agent", "run", "--rm",
                     "analytics-agent", "python", "-m", "app.scripts.build_meta_knowledge",
                     "-c", args.config])
    for line in out.splitlines():
        if any(k in line for k in ("INFO", "Error", "error", "Traceback")):
            print("  " + line.split("| ")[-1] if "| " in line else "  " + line)

    if code != 0:
        print("\nBuild failed.", file=sys.stderr)
        return 1

    print("\nDone. Re-run scripts/validate_agent_metadata.py to confirm the schema still matches.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
