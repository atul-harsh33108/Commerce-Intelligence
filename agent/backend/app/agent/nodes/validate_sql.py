from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.state import DataAgentState
from app.core.log import logger


async def validate_sql(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    writer({"type": "progress", "step": "Validate SQL", "status": "running"})

    dw_mysql_repository = runtime.context["dw_mysql_repository"]

    sql = state["sql"]

    try:
        await dw_mysql_repository.validate_sql(sql)
        writer({"type": "progress", "step": "Validate SQL", "status": "success"})
        logger.info(f"SQL validation passed: {sql}")
        return {"error": None}
    except Exception as e:
        writer({"type": "progress", "step": "Validate SQL", "status": "error"})
        logger.error(f"SQL validation failed: {sql}")
        return {"error": str(e)}
