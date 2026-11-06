"""
DataQuery Agent MCP Server

Exposes the Text-to-SQL pipeline as MCP tools so AI assistants
(e.g. Claude Desktop) can query the data warehouse in natural language.

Tools:
  - query_database      : Ask a question in plain English, get SQL + results
  - list_tables         : List all available tables with descriptions
  - get_table_schema    : Get columns and types for a specific table

Usage:
  python server.py [--backend-url http://localhost:8000]
"""

import argparse
import asyncio
import json

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

# ── Configuration ──────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="DataQuery Agent MCP Server")
parser.add_argument("--backend-url", default="http://localhost:8000",
                    help="Base URL of the DataQuery backend (default: http://localhost:8000)")
args, _ = parser.parse_known_args()
BACKEND_URL = args.backend_url.rstrip("/")

# ── MCP Server ─────────────────────────────────────────────────────────────────
server = Server("dataquery-agent")


@server.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="query_database",
            description=(
                "Convert a natural language business question into SQL and execute it "
                "against a star-schema data warehouse. Returns structured query results. "
                "Example questions: 'What are total sales by region?', "
                "'Show top 5 customers by order amount', 'Which brand had the highest revenue?'"
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "question": {
                        "type": "string",
                        "description": "A business question in plain English"
                    }
                },
                "required": ["question"]
            }
        ),
        Tool(
            name="list_tables",
            description=(
                "List all tables available in the data warehouse, with their roles "
                "(fact/dimension) and descriptions. Call this first to understand "
                "what data is available before querying."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "required": []
            }
        ),
        Tool(
            name="get_table_schema",
            description=(
                "Get the column definitions for a specific table, including column names, "
                "data types, roles, and descriptions. Useful for understanding table structure "
                "before writing queries."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "table_name": {
                        "type": "string",
                        "description": "Name of the table (e.g. 'fact_order', 'dim_region')"
                    }
                },
                "required": ["table_name"]
            }
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent]:

    async with httpx.AsyncClient(timeout=120.0) as client:

        # ── query_database ──────────────────────────────────────────────────
        if name == "query_database":
            question = arguments.get("question", "").strip()
            if not question:
                return [TextContent(type="text", text="Error: 'question' is required.")]

            # Call SSE endpoint and collect result
            result_data = None
            generated_sql = None
            steps = []
            error_msg = None

            try:
                async with client.stream(
                    "POST", f"{BACKEND_URL}/api/query",
                    json={"query": question},
                    headers={"Content-Type": "application/json"}
                ) as response:
                    buffer = ""
                    async for chunk in response.aiter_text():
                        buffer += chunk
                        while "\n\n" in buffer:
                            event, buffer = buffer.split("\n\n", 1)
                            event = event.strip()
                            if not event.startswith("data:"):
                                continue
                            try:
                                data = json.loads(event[5:].strip())
                            except json.JSONDecodeError:
                                continue

                            if data.get("type") == "progress" and data.get("status") == "success":
                                steps.append(data.get("step", ""))
                            elif data.get("type") == "sql":
                                generated_sql = data.get("sql")
                            elif data.get("type") == "result":
                                result_data = data.get("data", [])
                            elif data.get("type") == "error":
                                error_msg = data.get("message", "Unknown error")

            except Exception as e:
                return [TextContent(type="text", text=f"Error calling backend: {str(e)}")]

            if error_msg:
                return [TextContent(type="text", text=f"Query failed: {error_msg}")]

            # Format output
            lines = []
            lines.append(f"**Question:** {question}")

            if generated_sql:
                lines.append(f"\n**Generated SQL:**\n```sql\n{generated_sql}\n```")

            if result_data is not None:
                lines.append(f"\n**Results:** {len(result_data)} row(s)")
                if result_data:
                    # Format as markdown table
                    cols = list(result_data[0].keys())
                    header = "| " + " | ".join(cols) + " |"
                    sep = "| " + " | ".join(["---"] * len(cols)) + " |"
                    rows = ["| " + " | ".join(str(row.get(c, "")) for c in cols) + " |"
                            for row in result_data]
                    lines.append("\n" + "\n".join([header, sep] + rows))
                else:
                    lines.append("No rows returned.")
            else:
                lines.append("\nNo result data received.")

            if steps:
                lines.append(f"\n**Pipeline:** {' → '.join(steps)}")

            return [TextContent(type="text", text="\n".join(lines))]

        # ── list_tables ─────────────────────────────────────────────────────
        elif name == "list_tables":
            try:
                resp = await client.get(f"{BACKEND_URL}/api/tables")
                resp.raise_for_status()
                tables = resp.json()
            except Exception as e:
                return [TextContent(type="text", text=f"Error fetching tables: {str(e)}")]

            if not tables:
                return [TextContent(type="text", text="No tables found.")]

            lines = ["**Available Tables:**\n"]
            for t in tables:
                role_badge = "📊 Fact" if t["role"] == "fact" else "📁 Dimension"
                lines.append(f"- **{t['name']}** ({role_badge}): {t['description']}")

            return [TextContent(type="text", text="\n".join(lines))]

        # ── get_table_schema ────────────────────────────────────────────────
        elif name == "get_table_schema":
            table_name = arguments.get("table_name", "").strip()
            if not table_name:
                return [TextContent(type="text", text="Error: 'table_name' is required.")]

            try:
                resp = await client.get(f"{BACKEND_URL}/api/schema/{table_name}")
                if resp.status_code == 404:
                    return [TextContent(type="text", text=f"Table '{table_name}' not found.")]
                resp.raise_for_status()
                schema = resp.json()
            except Exception as e:
                return [TextContent(type="text", text=f"Error fetching schema: {str(e)}")]

            lines = [f"**Schema: {schema['table']}**\n"]
            header = "| Column | Type | Role | Description |"
            sep    = "| --- | --- | --- | --- |"
            rows   = [
                f"| {c['name']} | {c['type']} | {c['role']} | {c['description']} |"
                for c in schema["columns"]
            ]
            lines.append("\n".join([header, sep] + rows))

            return [TextContent(type="text", text="\n".join(lines))]

        else:
            return [TextContent(type="text", text=f"Unknown tool: {name}")]


# ── Entry point ─────────────────────────────────────────────────────────────────
async def main():
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
