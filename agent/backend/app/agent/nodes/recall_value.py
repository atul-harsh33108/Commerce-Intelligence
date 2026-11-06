import asyncio
from dataclasses import asdict

from langchain_core.output_parsers import JsonOutputParser
from langchain_core.prompts import PromptTemplate
from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.llm import llm
from app.agent.state import DataAgentState
from app.core.log import logger
from app.entities.value_info import ValueInfo
from app.prompt.prompt_loader import load_prompt


async def recall_value(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    writer({"type": "progress", "step": "Recall Values", "status": "running"})

    query = state["query"]
    keywords = state["keywords"]

    value_es_repository = runtime.context["value_es_repository"]
    redis_client = runtime.context["redis_client"]

    try:
        # Use LLM to expand keywords
        prompt = PromptTemplate(template=load_prompt("extend_keywords_for_value_recall"), input_variables=["query"])
        output_parser = JsonOutputParser()

        chain = prompt | llm | output_parser

        result = await chain.ainvoke({"query": query})

        # Use expanded keywords to recall field values
        values_map: dict[str, ValueInfo] = {}
        keywords = list(set(keywords + result))
        logger.info(f"Value recall expanded keywords: {keywords}")
        for keyword in keywords:
            cached_values = await redis_client.get_search_result("es:values", keyword)
            if cached_values is not None:
                values = [ValueInfo(**value) for value in cached_values]
                logger.info(f"Value recall cache hit: {keyword}")
            else:
                values: list[ValueInfo] = await value_es_repository.search(keyword)
                await redis_client.set_search_result("es:values", keyword, [asdict(value) for value in values])
            for value in values:
                value_id = value.id
                if value_id not in values_map:
                    values_map[value_id] = value

        retrieved_values = list(values_map.values())

        writer({"type": "progress", "step": "Recall Values", "status": "success"})
        logger.info(f"Recalled value info: {list(values_map.keys())}")

        return {'retrieved_values': retrieved_values}
    except Exception as e:
        writer({"type": "progress", "step": "Recall Values", "status": "error"})
        logger.error(f"Value recall failed: {str(e)}")
        raise
