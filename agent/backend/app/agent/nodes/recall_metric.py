from dataclasses import asdict

from langchain_core.output_parsers import JsonOutputParser
from langchain_core.prompts import PromptTemplate
from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.llm import llm
from app.agent.state import DataAgentState
from app.core.log import logger
from app.entities.metric_info import MetricInfo
from app.prompt.prompt_loader import load_prompt


async def recall_metric(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    writer({"type": "progress", "step": "Recall Metrics", "status": "running"})

    query = state["query"]
    keywords = state["keywords"]

    embedding_client = runtime.context['embedding_client']
    metric_qdrant_repository = runtime.context['metric_qdrant_repository']
    redis_client = runtime.context['redis_client']

    try:
        # Use LLM to expand keywords
        prompt = PromptTemplate(template=load_prompt("extend_keywords_for_metric_recall"), input_variables=["query"])
        output_parser = JsonOutputParser()

        chain = prompt | llm | output_parser

        result = await chain.ainvoke({"query": query})

        # Use expanded keywords to recall metric info
        retrieved_metrics_map: dict[str, MetricInfo] = {}

        keywords = list(set(keywords + result))
        logger.info(f"Metric recall expanded keywords: {keywords}")
        for keyword in keywords:
            cached_payloads = await redis_client.get_search_result("qdrant:metrics", keyword)
            if cached_payloads is not None:
                payloads = [MetricInfo(**payload) for payload in cached_payloads]
                logger.info(f"Metric recall cache hit: {keyword}")
            else:
                # Try Redis cache for embedding
                embedding = await redis_client.get_embedding(keyword)
                if embedding is None:
                    embedding = await embedding_client.aembed_query(keyword)
                    await redis_client.set_embedding(keyword, embedding)

                payloads: list[MetricInfo] = await metric_qdrant_repository.search(embedding)
                await redis_client.set_search_result("qdrant:metrics", keyword, [asdict(payload) for payload in payloads])
            for payload in payloads:
                metric_id = payload.id
                if metric_id not in retrieved_metrics_map:
                    retrieved_metrics_map[metric_id] = payload

        retrieved_metrics = list(retrieved_metrics_map.values())

        writer({"type": "progress", "step": "Recall Metrics", "status": "success"})
        logger.info(f"Recalled metric info: {list(retrieved_metrics_map.keys())}")
        return {"retrieved_metrics": retrieved_metrics}
    except Exception as e:
        writer({"type": "progress", "step": "Recall Metrics", "status": "error"})
        logger.error(f"Metric recall failed: {str(e)}")
        raise
