#!/usr/bin/env python3
"""Execution-accuracy evaluation of the NL->SQL agent, end to end through the gateway.

    python eval/run_eval.py                      # run everything
    python eval/run_eval.py --check-gold         # only verify the gold SQL runs (fast, no LLM)
    python eval/run_eval.py --only trap ratio    # run selected categories
    python eval/run_eval.py --tag before-fix     # name the report

================================================================================================
THE METRIC: EXECUTION ACCURACY
================================================================================================

Run the gold SQL, run the SQL the agent generated, compare the RESULT SETS. This is Spider's
definition, and the two obvious alternatives are both worse:

  string / AST similarity   punishes a correct query written differently. `JOIN a ON ... JOIN b`
                            and `JOIN b ON ... JOIN a` are the same answer and score badly.
  "the expected value
   appears in the output"   passes a query that returns the right number inside the wrong shape -
                            e.g. a per-category breakdown when the question asked for a total, where
                            the total happens to appear as one of the rows.

What execution accuracy cannot catch is a query that is right on THIS data and wrong in general - a
`LIMIT 5` that happens to match because there are only five categories. That is a real limitation of
the metric itself, not of this harness, and it is why the question set deliberately includes
groupings with many distinct values.

================================================================================================
COMPARISON RULES, and why each one is not just leniency
================================================================================================

  column names ignored      `SUM(line_revenue)` vs `AS total_revenue` is the same answer. The
                            question asked for a number, not for a label.
  column ORDER ignored      `(category, revenue)` vs `(revenue, category)` is the same answer.
                            Rows are compared as sorted tuples of values.
  row order ignored         UNLESS the question is a ranking (`ordered: true` in the question set).
                            "Top 5 by revenue" is wrong in the wrong order; "revenue per region" is
                            not.
  numbers rounded to 2dp    MySQL returns DECIMAL, the agent may return a float or a string, and
                            `13591687.34` vs `13591687.340000001` is not a modelling error. Rounding
                            to the scale money is stored at is the honest tolerance.
  strings trimmed/lowered   `'Southeast'` vs `'southeast '` is the same category.

Everything else is a mismatch, including a different row count - which is the check that catches a
missing `WHERE` or a wrong join.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, date
from decimal import Decimal
from pathlib import Path

try:
    import pymysql
    import yaml
except ImportError:
    print("pip install pymysql pyyaml", file=sys.stderr)
    raise SystemExit(2)

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS = ROOT / "eval" / "questions.yaml"
REPORTS = ROOT / "eval" / "reports"

GATEWAY = "http://localhost:8080"
MYSQL = dict(host="127.0.0.1", port=3307, user="warehouse", password="warehouse",
             database="dw", charset="utf8mb4")


# -------------------------------------------------------------------------------------------------
# Normalisation
# -------------------------------------------------------------------------------------------------

def normalise_value(v):
    """One value, comparably.

    Numbers are rounded to 2 decimal places - the scale money is stored at. Anything finer is
    floating-point noise between MySQL's DECIMAL, Python's float and JSON's number, not a difference
    in the answer.
    """
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, Decimal, float)):
        return round(float(v), 2)
    if isinstance(v, (datetime, date)):
        return v.isoformat()
    s = str(v).strip()
    # A numeric string from JSON ("13591687.34") must compare equal to the number MySQL returned.
    try:
        return round(float(s), 2)
    except ValueError:
        return s.lower()


def normalise_rows(rows, ordered: bool):
    """A result set, comparably.

    Each row becomes a SORTED tuple of its values, which drops column names and column order. The
    set of rows is then sorted unless the question is a ranking.
    """
    out = []
    for row in rows:
        values = row.values() if isinstance(row, dict) else row
        # sorted() on mixed types would raise, so sort by the string form.
        out.append(tuple(sorted((normalise_value(v) for v in values), key=lambda x: str(x))))
    return out if ordered else sorted(out, key=lambda t: tuple(str(x) for x in t))


def compare(gold_rows, pred_rows, ordered: bool):
    return normalise_rows(gold_rows, ordered) == normalise_rows(pred_rows, ordered)


# -------------------------------------------------------------------------------------------------
# The two sides
# -------------------------------------------------------------------------------------------------

def run_gold(conn, sql: str):
    with conn.cursor(pymysql.cursors.DictCursor) as cur:
        cur.execute(sql)
        return cur.fetchall()


def login(base: str, username: str, password: str) -> str:
    body = json.dumps({"username": username, "password": password}).encode()
    req = urllib.request.Request(f"{base}/api/auth/login", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["token"]


def ask_agent(base: str, token: str, question: str, timeout: float):
    """Ask through the GATEWAY, not the agent directly.

    Deliberate: the number this produces should describe the system a user actually reaches, which
    includes the edge's authentication and the route rewrite. Measuring the agent on :8000 would
    quietly exclude two things that have already broken once each.
    """
    body = json.dumps({"query": question}).encode()
    req = urllib.request.Request(f"{base}/api/analytics/query", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")

    sql = None
    result = None
    error = None
    steps = []
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            for raw in r:
                line = raw.decode("utf-8", errors="replace").strip()
                if not line.startswith("data: "):
                    continue
                try:
                    event = json.loads(line[6:])
                except json.JSONDecodeError:
                    continue
                kind = event.get("type")
                if kind == "sql":
                    sql = event.get("sql")
                elif kind == "result":
                    result = event.get("data")
                elif kind == "progress":
                    steps.append(f"{event.get('step')}:{event.get('status')}")
                elif kind == "error":
                    error = json.dumps(event)[:400]
    except urllib.error.HTTPError as e:
        error = f"HTTP {e.code}: {e.read().decode()[:300]}"
    except Exception as e:                                    # noqa: BLE001
        error = f"{e.__class__.__name__}: {e}"

    return {
        "sql": sql,
        "result": result,
        "error": error,
        "steps": steps,
        # How many times SQL generation ran. >1 means the self-correction loop fired, which is the
        # only way to observe it from outside.
        "corrections": sum(1 for s in steps if s.startswith("Correct SQL")),
        "seconds": round(time.perf_counter() - started, 2),
    }


# -------------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default=GATEWAY)
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin-dev-password")
    parser.add_argument("--only", nargs="*", help="restrict to these categories")
    parser.add_argument("--check-gold", action="store_true",
                        help="only verify the gold SQL executes; no LLM calls")
    parser.add_argument("--timeout", type=float, default=180.0)
    parser.add_argument("--tag", default="", help="suffix for the report filename")
    args = parser.parse_args()

    spec = yaml.safe_load(QUESTIONS.read_text(encoding="utf-8"))
    questions = spec["questions"]
    if args.only:
        questions = [q for q in questions if q["category"] in args.only]

    conn = pymysql.connect(**MYSQL)

    # ---- gold first, always. A wrong reference makes every other number meaningless. -----------
    print(f"Verifying {len(questions)} gold queries against the warehouse")
    gold = {}
    broken = []
    for q in questions:
        try:
            rows = run_gold(conn, q["sql"])
            gold[q["id"]] = rows
            if not rows:
                broken.append((q["id"], "returned 0 rows"))
        except Exception as e:                                # noqa: BLE001
            broken.append((q["id"], f"{e.__class__.__name__}: {e}"))
    if broken:
        print(f"\n{len(broken)} gold queries are unusable:")
        for qid, why in broken:
            print(f"  {qid}: {why}")
        print("\nFix the gold SQL before trusting any accuracy number.")
        return 1
    print(f"  all {len(gold)} gold queries execute and return rows\n")

    if args.check_gold:
        return 0

    token = login(args.base, args.user, args.password)
    results = []
    started = time.perf_counter()

    for i, q in enumerate(questions, 1):
        pred = ask_agent(args.base, token, q["question"], args.timeout)
        ordered = bool(q.get("ordered"))

        if pred["error"] or pred["result"] is None:
            verdict = "ERROR"
            correct = False
        else:
            correct = compare(gold[q["id"]], pred["result"], ordered)
            verdict = "PASS" if correct else "FAIL"

        results.append({
            "id": q["id"],
            "category": q["category"],
            "question": q["question"],
            "ordered": ordered,
            "gold_sql": q["sql"].strip(),
            "gold_rows": json.loads(json.dumps(gold[q["id"]], default=str)),
            "predicted_sql": pred["sql"],
            "predicted_rows": pred["result"],
            "verdict": verdict,
            "corrections": pred["corrections"],
            "seconds": pred["seconds"],
            "error": pred["error"],
        })
        mark = {"PASS": "PASS", "FAIL": "FAIL", "ERROR": "ERR "}[verdict]
        print(f"  [{mark}] {i:2d}/{len(questions)} {q['id']:32s} {pred['seconds']:6.1f}s"
              + (f"  {pred['error'][:60]}" if pred["error"] else ""))

    elapsed = time.perf_counter() - started
    conn.close()

    passed = sum(1 for r in results if r["verdict"] == "PASS")
    total = len(results)
    accuracy = passed / total * 100 if total else 0.0

    # ---- report --------------------------------------------------------------------------------
    REPORTS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    name = f"{stamp}{('-' + args.tag) if args.tag else ''}.json"
    report = {
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "tag": args.tag,
        "questions": total,
        "passed": passed,
        "execution_accuracy_pct": round(accuracy, 1),
        "wall_seconds": round(elapsed, 1),
        "self_corrections_fired": sum(r["corrections"] for r in results),
        "results": results,
    }
    (REPORTS / name).write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")

    # ---- summary -------------------------------------------------------------------------------
    print(f"\n{'=' * 78}")
    print(f"EXECUTION ACCURACY  {passed}/{total} = {accuracy:.1f}%"
          f"   ({elapsed:.0f}s, {elapsed / max(total, 1):.1f}s/question)")
    print(f"{'=' * 78}\n")

    by_cat = {}
    for r in results:
        c = by_cat.setdefault(r["category"], [0, 0])
        c[1] += 1
        if r["verdict"] == "PASS":
            c[0] += 1
    print("By category:")
    for cat, (ok, n) in sorted(by_cat.items(), key=lambda kv: kv[1][0] / kv[1][1]):
        bar = "#" * int(ok / n * 20)
        print(f"  {cat:20s} {ok}/{n:<3d} {ok / n * 100:5.1f}%  {bar}")

    failures = [r for r in results if r["verdict"] != "PASS"]
    if failures:
        print(f"\nFailure analysis ({len(failures)}):")
        for r in failures:
            print(f"\n  {r['id']}  [{r['verdict']}]  {r['question']}")
            if r["error"]:
                print(f"    error: {r['error'][:200]}")
            else:
                print(f"    gold : {json.dumps(r['gold_rows'], default=str)[:180]}")
                print(f"    pred : {json.dumps(r['predicted_rows'], default=str)[:180]}")
                if r["predicted_sql"]:
                    print(f"    sql  : {' '.join(r['predicted_sql'].split())[:220]}")

    corrections = report["self_corrections_fired"]
    print(f"\nSelf-correction loop fired on {corrections} question(s)")
    print(f"Report written to eval/reports/{name}")
    return 0 if not failures else 0   # a failing question is data, not a broken harness


if __name__ == "__main__":
    raise SystemExit(main())
