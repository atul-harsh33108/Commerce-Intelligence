import yaml
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import PromptTemplate
from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.llm import llm
from app.agent.state import DataAgentState
from app.core.log import logger
from app.core.sql_safety import sanitize_sql
from app.prompt.prompt_loader import load_prompt


async def correct_sql(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    writer({"type": "progress", "step": "Correct SQL", "status": "running"})

    sql = state["sql"]
    error = state["error"]
    attempts = state.get("correction_attempts", 0) + 1

    query = state["query"]
    table_infos = state["table_infos"]
    metric_infos = state["metric_infos"]
    date_info = state["date_info"]
    db_info = state["db_info"]

    try:
        prompt = PromptTemplate(template=load_prompt("correct_sql"), input_variables=["query", "metric_infos"])
        output_parser = StrOutputParser()

        chain = prompt | llm | output_parser

        result = await chain.ainvoke(
            {"query": query,
             "table_infos": yaml.dump(table_infos, allow_unicode=True, sort_keys=False),
             "metric_infos": yaml.dump(metric_infos, allow_unicode=True, sort_keys=False),
             "date_info": yaml.dump(date_info, allow_unicode=True, sort_keys=False),
             "db_info": yaml.dump(db_info, allow_unicode=True, sort_keys=False),
             "sql": sql,
             "error": error
             })
        sql = sanitize_sql(result)
        writer({"type": "progress", "step": "Correct SQL", "status": "success", "attempt": attempts})
        writer({"type": "sql", "sql": sql, "source": "correction", "attempt": attempts})
        logger.info(f"Corrected SQL attempt {attempts}: {sql}")
        return {"sql": sql, "correction_attempts": attempts}
    except Exception as e:
        writer({"type": "progress", "step": "Correct SQL", "status": "error"})
        logger.error(f"SQL correction failed: {str(e)}")
        raise
