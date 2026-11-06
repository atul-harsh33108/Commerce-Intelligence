from dataclasses import asdict

from langchain_core.output_parsers import JsonOutputParser
from langchain_core.prompts import PromptTemplate
from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.llm import llm
from app.agent.state import DataAgentState
from app.core.log import logger
from app.entities.column_info import ColumnInfo
from app.prompt.prompt_loader import load_prompt


async def recall_column(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    writer({"type": "progress", "step": "Recall Columns", "status": "running"})

    query = state["query"]
    keywords = state["keywords"]

    embedding_client = runtime.context["embedding_client"]
    column_qdrant_repository = runtime.context["column_qdrant_repository"]
    redis_client = runtime.context["redis_client"]

    try:
        # Use LLM to expand keywords
        prompt = PromptTemplate(
            template=load_prompt("extend_keywords_for_column_recall"),
            input_variables=["query"],
        )
        output_parser = JsonOutputParser()

        chain = prompt | llm | output_parser

        result = await chain.ainvoke({"query": query})

        # Use expanded keywords to recall column info
        retrieved_columns_map: dict[str, ColumnInfo] = {}

        keywords = list(set(keywords + result))
        logger.info(f"Column recall expanded keywords: {keywords}")
        for keyword in keywords:
            cached_payloads = await redis_client.get_search_result("qdrant:columns", keyword)
            if cached_payloads is not None:
                payloads = [ColumnInfo(**payload) for payload in cached_payloads]
                logger.info(f"Column recall cache hit: {keyword}")
            else:
                # Try Redis cache for embedding
                embedding = await redis_client.get_embedding(keyword)
                if embedding is None:
                    embedding = await embedding_client.aembed_query(keyword)
                    await redis_client.set_embedding(keyword, embedding)

                payloads: list[ColumnInfo] = await column_qdrant_repository.search(
                    embedding
                )
                await redis_client.set_search_result("qdrant:columns", keyword, [asdict(payload) for payload in payloads])
            for payload in payloads:
                column_id = payload.id
                if column_id not in retrieved_columns_map:
                    retrieved_columns_map[column_id] = payload

        retrieved_columns = list(retrieved_columns_map.values())

        writer({"type": "progress", "step": "Recall Columns", "status": "success"})
        logger.info(f"Recalled column info: {list(retrieved_columns_map.keys())}")
        return {"retrieved_columns": retrieved_columns}
    except Exception as e:
        writer({"type": "progress", "step": "Recall Columns", "status": "error"})
        logger.error(f"Column recall failed: {str(e)}")
        raise
