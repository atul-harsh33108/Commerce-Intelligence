import spacy
from langgraph.runtime import Runtime

from app.agent.context import DataAgentContext
from app.agent.state import DataAgentState
from app.core.log import logger

# Load spaCy English model at module level (loaded once)
nlp = spacy.load("en_core_web_sm")


async def extract_keywords(state: DataAgentState, runtime: Runtime[DataAgentContext]):
    writer = runtime.stream_writer
    writer({"type": "progress", "step": "Extract Keywords", "status": "running"})

    query = state["query"]

    # Process the query with spaCy and extract tokens with relevant POS tags
    allowed_pos = {"NOUN", "PROPN", "VERB", "ADJ", "ADV"}
    doc = nlp(query)
    keywords = [token.lemma_.lower() for token in doc if token.pos_ in allowed_pos and not token.is_stop]

    # Deduplicate and always include the original query
    keywords = list(set(keywords + [query]))

    writer({"type": "progress", "step": "Extract Keywords", "status": "success"})
    logger.info(f"Extracted keywords: {keywords}")
    return {"keywords": keywords}
