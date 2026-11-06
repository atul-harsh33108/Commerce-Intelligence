"""
Execution Accuracy (EX) test for DataQuery Agent.
Compares agent query results against gold-standard SQL answers.

Usage:
    cd D:\\project\\dataquery-agent\\backend
    python tests/test_accuracy.py
"""

import json
import httpx
import asyncio
from datetime import datetime

# Each test has: query (natural language), gold_answer (expected result to verify against)
# gold_answer can be:
#   - a single value (int/float): checks if that value appears in result
#   - a list of values: checks if all values appear in result
#   - a dict: checks if key-value pairs match in result rows
#   - "rows:N": checks exact row count
TEST_QUERIES = [
    # ── Category 1: Simple Aggregation (5) ──
    {
        "query": "What are the total sales by region?",
        "gold_check": {"row_count": 6, "contains_values": [70202, 60210, 47163]},
    },
    {
        "query": "What is the total GMV?",
        "gold_check": {"contains_values": [279159.5]},
    },
    {
        "query": "How many orders are there in total?",
        "gold_check": {"contains_values": [115]},
    },
    {
        "query": "What is the total order quantity across all orders?",
        "gold_check": {"contains_values": [622]},
    },
    {
        "query": "Show me the overall revenue",
        "gold_check": {"contains_values": [279159.5]},
    },

    # ── Category 2: Single Dimension Filtering (5) ──
    {
        "query": "What are the sales in California?",
        "gold_check": {"contains_values": [70202]},
    },
    {
        "query": "Show me orders from Gold members",
        "gold_check": {"row_count": 34},
    },
    {
        "query": "Total sales for Electronics category",
        "gold_check": {"contains_values": [194771]},
    },
    {
        "query": "How much revenue did female customers generate?",
        "gold_check": {"contains_values": [143789]},
    },
    {
        "query": "What are the sales in the West region?",
        "gold_check": {"contains_values": [70202]},
    },

    # ── Category 3: Multi-Dimension Analysis (5) ──
    {
        "query": "What are the total sales by product category and region?",
        "gold_check": {"min_rows": 5},
    },
    {
        "query": "Show monthly sales trend",
        "gold_check": {"row_count": 3, "contains_values": [109030.5, 80009, 90120]},
    },
    {
        "query": "Sales by customer gender",
        "gold_check": {"row_count": 2},
    },
    {
        "query": "Break down order quantity by brand and membership level",
        "gold_check": {"min_rows": 5},
    },
    {
        "query": "Revenue by quarter and region",
        "gold_check": {"min_rows": 1},
    },

    # ── Category 4: Ranking / Top-N (5) ──
    {
        "query": "Top 5 customers by order amount",
        "gold_check": {"row_count": 5, "first_row_contains": "James Wilson"},
    },
    {
        "query": "Which brand has the highest sales?",
        "gold_check": {"first_row_contains": "Apple"},
    },
    {
        "query": "Which state has the most orders?",
        "gold_check": {"first_row_contains": "California"},
    },
    {
        "query": "Bottom 3 regions by total revenue",
        "gold_check": {"row_count": 3, "first_row_contains": "Northwest"},
    },
    {
        "query": "Rank product categories by average order amount",
        "gold_check": {"min_rows": 2},
    },

    # ── Category 5: Computed Metrics (5) ──
    {
        "query": "What is the average order value?",
        "gold_check": {"contains_approx": 2427.47},
    },
    {
        "query": "Average order quantity by region",
        "gold_check": {"row_count": 6},
    },
    {
        "query": "What is the maximum single order amount?",
        "gold_check": {"contains_values": [9499]},
    },
    {
        "query": "What is the average revenue per customer?",
        "gold_check": {"contains_approx": 13957.98},
    },
    {
        "query": "Show the min and max order amount by category",
        "gold_check": {"min_rows": 2},
    },

    # ── Category 6: Time-Based (5) ──
    {
        "query": "What are the sales for Q1 2025?",
        "gold_check": {"contains_values": [279159.5]},
    },
    {
        "query": "Which month had the highest revenue?",
        "gold_check": {"contains_values": [109030.5]},
    },
    {
        "query": "Show the daily order count trend",
        "gold_check": {"min_rows": 5},
    },
    {
        "query": "Total sales by month",
        "gold_check": {"row_count": 3, "contains_values": [109030.5, 80009, 90120]},
    },
    {
        "query": "Compare sales across months",
        "gold_check": {"row_count": 3},
    },

    # ── Category 7: Complex Multi-Table Joins (5) ──
    {
        "query": "Sales by brand in the West region",
        "gold_check": {"min_rows": 3},
    },
    {
        "query": "Top 3 product categories by sales in Northeast",
        "gold_check": {"row_count": 3},
    },
    {
        "query": "Gold member spending by product category in California",
        "gold_check": {"min_rows": 1},
    },
    {
        "query": "Average order value by gender and region",
        "gold_check": {"min_rows": 4},
    },
    {
        "query": "Monthly revenue trend for Electronics in the South",
        "gold_check": {"min_rows": 1},
    },

    # ── Category 8: Ambiguous / Colloquial Phrasing (5) ──
    {
        "query": "How's business doing?",
        "gold_check": {"min_rows": 1},  # any reasonable summary
    },
    {
        "query": "Who are our best buyers?",
        "gold_check": {"first_row_contains": "James Wilson"},
    },
    {
        "query": "What sells the most?",
        "gold_check": {"min_rows": 1},
    },
    {
        "query": "Where do we make the most money?",
        "gold_check": {"first_row_contains": "West"},
    },
    {
        "query": "Any big orders recently?",
        "gold_check": {"min_rows": 1},
    },

    # ── Category 9: Edge Cases & Stress Tests (5) ──
    {
        "query": "How many unique customers placed orders?",
        "gold_check": {"contains_values": [20]},
    },
    {
        "query": "Total revenue and order count by membership level",
        "gold_check": {"row_count": 4},
    },
    {
        "query": "Show all regions with sales above 50000",
        "gold_check": {"row_count": 2, "contains_strings": ["West", "Southeast"]},
    },
    {
        "query": "Percentage of total sales by region",
        "gold_check": {"row_count": 6},
    },
    {
        "query": "Compare male vs female customers average order value by region",
        "gold_check": {"min_rows": 4},
    },
]

BASE_URL = "http://localhost:8000"


def flatten_values(data: list[dict]) -> list:
    """Extract all values from result rows into a flat list."""
    values = []
    for row in data:
        for v in row.values():
            values.append(v)
    return values


def check_gold(data: list[dict] | None, gold_check: dict) -> tuple[bool, str]:
    """Verify query result against gold standard. Returns (passed, reason)."""
    if data is None:
        return False, "No data returned"

    # Check exact row count
    if "row_count" in gold_check:
        expected = gold_check["row_count"]
        actual = len(data)
        if actual != expected:
            return False, f"Expected {expected} rows, got {actual}"

    # Check minimum row count
    if "min_rows" in gold_check:
        expected = gold_check["min_rows"]
        actual = len(data)
        if actual < expected:
            return False, f"Expected >= {expected} rows, got {actual}"

    # Check if specific values appear in results
    if "contains_values" in gold_check:
        flat = flatten_values(data)
        for expected_val in gold_check["contains_values"]:
            # Check with numeric tolerance
            found = False
            for v in flat:
                try:
                    if abs(float(v) - float(expected_val)) < 0.5:
                        found = True
                        break
                except (ValueError, TypeError):
                    continue
            if not found:
                return False, f"Expected value {expected_val} not found in results"

    # Check approximate value (for averages etc.)
    if "contains_approx" in gold_check:
        expected_val = gold_check["contains_approx"]
        flat = flatten_values(data)
        found = False
        for v in flat:
            try:
                if abs(float(v) - expected_val) / max(abs(expected_val), 1) < 0.05:  # 5% tolerance
                    found = True
                    break
            except (ValueError, TypeError):
                continue
        if not found:
            return False, f"Expected approx value {expected_val} not found (5% tolerance)"

    # Check if first row contains a specific string
    if "first_row_contains" in gold_check:
        expected_str = gold_check["first_row_contains"]
        if not data:
            return False, f"No data to check for '{expected_str}'"
        first_row_str = " ".join(str(v) for v in data[0].values())
        if expected_str.lower() not in first_row_str.lower():
            return False, f"First row '{first_row_str}' doesn't contain '{expected_str}'"

    # Check if specific strings appear anywhere in results
    if "contains_strings" in gold_check:
        all_str = " ".join(str(v) for row in data for v in row.values())
        for s in gold_check["contains_strings"]:
            if s.lower() not in all_str.lower():
                return False, f"Expected string '{s}' not found in results"

    return True, "All checks passed"


async def run_query(client: httpx.AsyncClient, query: str, timeout: float = 120.0) -> dict:
    """Send a query and parse SSE response."""
    result = {"query": query, "success": False, "data": None, "error": None, "stages": []}

    try:
        async with client.stream("POST", f"{BASE_URL}/api/query",
                                  json={"query": query}, timeout=timeout) as response:
            async for line in response.aiter_lines():
                if not line.startswith("data: "):
                    continue
                data = json.loads(line[6:])
                msg_type = data.get("type", "")

                if msg_type == "progress":
                    step = data.get("step", "")
                    status = data.get("status", "")
                    if status == "success" and step not in result["stages"]:
                        result["stages"].append(step)
                elif msg_type == "result":
                    result["data"] = data.get("data", [])
                    result["success"] = True
                elif msg_type == "error":
                    result["error"] = data.get("message", "")
    except Exception as e:
        result["error"] = str(e)

    return result


async def main():
    print(f"{'='*70}")
    print(f"DataQuery Agent - Execution Accuracy (EX) Test")
    print(f"{datetime.now().strftime('%Y-%m-%d %H:%M')} | {len(TEST_QUERIES)} queries | 9 categories")
    print(f"{'='*70}\n")

    results = []
    category_stats = {}
    current_category = ""

    async with httpx.AsyncClient() as client:
        for i, test in enumerate(TEST_QUERIES, 1):
            query = test["query"]
            gold_check = test["gold_check"]

            # Detect category changes from comments in source
            cat_idx = (i - 1) // 5
            categories = [
                "Simple Aggregation", "Single Dimension Filtering",
                "Multi-Dimension Analysis", "Ranking / Top-N",
                "Computed Metrics", "Time-Based",
                "Complex Multi-Table Joins", "Ambiguous / Colloquial",
                "Edge Cases & Stress Tests"
            ]
            cat_name = categories[cat_idx] if cat_idx < len(categories) else "Other"
            if cat_name != current_category:
                current_category = cat_name
                print(f"── {current_category} ──")
                if cat_name not in category_stats:
                    category_stats[cat_name] = {"passed": 0, "total": 0}

            category_stats[cat_name]["total"] += 1

            print(f"  [{i:2d}/{len(TEST_QUERIES)}] {query}")
            result = await run_query(client, query)

            # Verify against gold standard
            if result["success"] and result["data"] is not None:
                passed, reason = check_gold(result["data"], gold_check)
            elif result["success"] and result["data"] is None:
                passed, reason = False, "Query succeeded but no data"
            else:
                passed, reason = False, result["error"] or "Query failed"

            result["ex_passed"] = passed
            result["ex_reason"] = reason

            if passed:
                category_stats[cat_name]["passed"] += 1
                print(f"         PASS ✓ | Rows: {len(result['data']) if result['data'] else 0}")
            else:
                print(f"         FAIL ✗ | {reason}")
                if result["data"]:
                    # Show first row for debugging
                    print(f"                  Got: {result['data'][0]}")

            results.append(result)

    # ── Summary ──
    total_passed = sum(1 for r in results if r["ex_passed"])
    total = len(results)
    accuracy = total_passed / total * 100

    print(f"\n{'='*70}")
    print(f"  EXECUTION ACCURACY: {total_passed}/{total} ({accuracy:.1f}%)")
    print(f"{'='*70}")

    # Per-category breakdown
    print(f"\n  Category Breakdown:")
    for cat, stats in category_stats.items():
        cat_acc = stats['passed'] / stats['total'] * 100 if stats['total'] > 0 else 0
        bar = "█" * stats['passed'] + "░" * (stats['total'] - stats['passed'])
        print(f"    {cat:<30s} {bar} {stats['passed']}/{stats['total']} ({cat_acc:.0f}%)")

    # Failed queries detail
    failed = [r for r in results if not r["ex_passed"]]
    if failed:
        print(f"\n  Failed Queries ({len(failed)}):")
        for r in failed:
            print(f"    ✗ {r['query']}")
            print(f"      Reason: {r['ex_reason']}")

    # Save report
    report = {
        "date": datetime.now().isoformat(),
        "total": total,
        "passed": total_passed,
        "execution_accuracy": accuracy,
        "category_breakdown": category_stats,
        "results": results
    }
    with open("tests/accuracy_report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False, default=str)
    print(f"\n  Report saved to tests/accuracy_report.json")


if __name__ == "__main__":
    asyncio.run(main())
