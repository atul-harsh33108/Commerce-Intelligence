from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.state import DataAgentState


async def fail_sql(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    attempts = state.get("correction_attempts", 0)
    error = state.get("error") or "SQL validation failed"
    writer({
        "type": "error",
        "message": f"SQL validation failed after {attempts} correction attempt(s): {error}",
    })
    raise ValueError(f"SQL validation failed after {attempts} correction attempt(s): {error}")
