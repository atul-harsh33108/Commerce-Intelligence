import time
from typing import Optional

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


class QueryLogService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.log_id: Optional[int] = None
        self.start_time: float = 0
        self._node_starts: dict[str, float] = {}

    async def start(self, request_id: str, query: str):
        self.start_time = time.time()
        result = await self.session.execute(
            text("INSERT INTO query_logs (request_id, query, status) VALUES (:rid, :q, 'running') RETURNING id"),
            {"rid": request_id, "q": query}
        )
        self.log_id = result.scalar_one()
        await self.session.commit()

    def node_start(self, node_name: str):
        self._node_starts[node_name] = time.time()

    async def node_end(self, node_name: str, status: str):
        start = self._node_starts.pop(node_name, time.time())
        duration_ms = int((time.time() - start) * 1000)
        await self.session.execute(
            text("INSERT INTO query_node_logs (query_log_id, node_name, status, duration_ms) VALUES (:lid, :n, :s, :d)"),
            {"lid": self.log_id, "n": node_name, "s": status, "d": duration_ms}
        )
        await self.session.commit()

    async def complete(self, status: str, generated_sql: str = None, error: str = None, result_rows: int = None):
        duration_ms = int((time.time() - self.start_time) * 1000)
        await self.session.execute(
            text("""UPDATE query_logs
                    SET status = :s, generated_sql = :sql, error_message = :err,
                        result_rows = :rows, duration_ms = :d
                    WHERE id = :lid"""),
            {"s": status, "sql": generated_sql, "err": error, "rows": result_rows, "d": duration_ms, "lid": self.log_id}
        )
        await self.session.commit()
