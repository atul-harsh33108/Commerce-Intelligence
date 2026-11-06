import json
import uuid

from langchain_huggingface import HuggingFaceEndpointEmbeddings

from app.agent.context import DataAgentContext
from app.agent.graph import graph
from app.agent.state import DataAgentState
from app.clients.redis_client_manager import RedisClientManager
from app.repositories.es.value_es_repository import ValueESRepository
from app.repositories.mysql.dw.dw_mysql_repository import DWMySQLRepository
from app.repositories.mysql.meta.meta_mysql_repository import MetaMySQLRepository
from app.repositories.qdrant.column_qdrant_repository import ColumnQdrantRepository
from app.repositories.qdrant.metric_qdrant_repository import MetricQdrantRepository
from app.services.query_log_service import QueryLogService


class QueryService:
    def __init__(self,
                 embedding_client: HuggingFaceEndpointEmbeddings,
                 column_qdrant_repository: ColumnQdrantRepository,
                 value_es_repository: ValueESRepository,
                 metric_qdrant_repository: MetricQdrantRepository,
                 meta_mysql_repository: MetaMySQLRepository,
                 dw_mysql_repository: DWMySQLRepository,
                 redis_client: RedisClientManager,
                 query_log_service: QueryLogService):
        self.embedding_client = embedding_client
        self.column_qdrant_repository = column_qdrant_repository
        self.value_es_repository = value_es_repository
        self.metric_qdrant_repository = metric_qdrant_repository
        self.meta_mysql_repository = meta_mysql_repository
        self.dw_mysql_repository = dw_mysql_repository
        self.redis_client = redis_client
        self.query_log_service = query_log_service

    async def query(self, query: str):
        context = DataAgentContext(
            embedding_client=self.embedding_client,
            column_qdrant_repository=self.column_qdrant_repository,
            value_es_repository=self.value_es_repository,
            metric_qdrant_repository=self.metric_qdrant_repository,
            meta_mysql_repository=self.meta_mysql_repository,
            dw_mysql_repository=self.dw_mysql_repository,
            redis_client=self.redis_client,
            query_log_service=self.query_log_service,
        )
        state = DataAgentState(query=query)

        request_id = str(uuid.uuid4())
        await self.query_log_service.start(request_id, query)

        generated_sql = None
        result_rows = None

        try:
            async for chunk in graph.astream(input=state, context=context, stream_mode="custom"):
                # Track progress for logging
                if chunk.get("type") == "progress":
                    step = chunk.get("step", "")
                    status = chunk.get("status", "")
                    if status == "running":
                        self.query_log_service.node_start(step)
                    elif status in ("success", "error"):
                        await self.query_log_service.node_end(step, status)

                if chunk.get("type") == "sql":
                    generated_sql = chunk.get("sql")

                if chunk.get("type") == "result":
                    data = chunk.get("data", [])
                    result_rows = len(data)

                yield f"data: {json.dumps(chunk, ensure_ascii=False, default=str)}\n\n"

            # Get the generated SQL from the final state
            await self.query_log_service.complete("success", generated_sql=generated_sql, result_rows=result_rows)

        except Exception as e:
            await self.query_log_service.complete("error", generated_sql=generated_sql, error=str(e))
            yield f"data: {json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False, default=str)}\n\n"
