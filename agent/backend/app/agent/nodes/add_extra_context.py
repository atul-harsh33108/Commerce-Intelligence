from datetime import datetime

from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.state import DataAgentState, DateInfoState
from app.core.log import logger


async def add_extra_context(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    writer({"type": "progress", "step": "Add Context", "status": "running"})

    dw_mysql_repository = runtime.context["dw_mysql_repository"]

    try:
        # Current time info
        today = datetime.today()
        # Date
        date = today.strftime("%Y-%m-%d")
        # Weekday
        weekday = today.strftime("%A")
        # Quarter
        quarter = f"Q{(today.month - 1) // 3 + 1}"

        date_info = DateInfoState(date=date, weekday=weekday, quarter=quarter)

        # Data warehouse environment info
        db_info = await dw_mysql_repository.get_db_info()

        writer({"type": "progress", "step": "Add Context", "status": "success"})
        logger.info(f"Adding extra context: db_info-{db_info} date_info-{date_info}")
        return {
            "date_info": date_info,
            "db_info": db_info,
        }
    except Exception as e:
        writer({"type": "progress", "step": "Add Context", "status": "error"})
        logger.error(f"Adding extra context failed: {str(e)}")
        raise
