from fastapi import APIRouter, HTTPException
from fastapi.params import Depends
from starlette.responses import StreamingResponse

from app.api.dependencies import get_query_service, get_meta_mysql_repository
from app.api.schemas.query_schema import QuerySchema
from app.repositories.mysql.meta.meta_mysql_repository import MetaMySQLRepository
from app.services.query_service import QueryService

query_router = APIRouter()


@query_router.post("/api/query")
async def query(
    query: QuerySchema, query_service: QueryService = Depends(get_query_service)
):
    return StreamingResponse(
        query_service.query(query.query), media_type="text/event-stream"
    )


@query_router.get("/api/tables")
async def list_tables(repo: MetaMySQLRepository = Depends(get_meta_mysql_repository)):
    tables = await repo.get_all_tables()
    return [{"name": t.name, "role": t.role, "description": t.description} for t in tables]


@query_router.get("/api/schema/{table_name}")
async def get_schema(
    table_name: str,
    repo: MetaMySQLRepository = Depends(get_meta_mysql_repository)
):
    columns = await repo.get_columns_by_table_name(table_name)
    if not columns:
        raise HTTPException(status_code=404, detail=f"Table '{table_name}' not found")
    return {
        "table": table_name,
        "columns": [
            {"name": c.name, "type": c.type, "role": c.role, "description": c.description}
            for c in columns
        ]
    }
