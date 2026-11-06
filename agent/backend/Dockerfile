# The agent's image.
#
# ============================================================================================
# DEPENDENCY DRIFT, AND WHY THE LOCKFILE COULD NOT SAVE US
# ============================================================================================
#
# `pip install -e .` resolves pyproject.toml's ranges afresh at build time and ignores the uv.lock
# sitting next to it. A build today therefore picked up a langchain-huggingface newer than the one
# this code was written against, and the agent failed at startup:
#
#     ValidationError: 1 validation error for HuggingFaceEndpointEmbeddings
#       Value error, `model` must be a HuggingFace repo ID, not a URL.
#       input_value={'model': 'http://embedding:80'}
#
# The obvious fix is to install from the lock (`uv sync --frozen`), and it does not work here: the
# upstream uv.lock is STALE. It still lists `jieba` and contains no `spacy`, `redis` or
# `langchain-google-genai` - all of which pyproject.toml requires - so the environment it describes
# cannot run this code either. Installing from it fails with "No module named spacy".
#
# So the fix is one pinned version in pyproject.toml, documented there. Recorded here because the
# lesson is the useful part: **a lockfile that is not regenerated is worse than no lockfile**, since
# it looks like a reproducibility guarantee and is not one.
# ============================================================================================

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    APP_CONFIG_FILE=/app/conf/app_config.docker.yaml

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential curl \
    && rm -rf /var/lib/apt/lists/*

# pyproject.toml on its own layer, ahead of the source: dependencies change far less often than
# application code, so this layer is cached across every code edit.
COPY pyproject.toml ./
COPY app ./app
COPY conf ./conf
COPY prompts ./prompts
COPY main.py ./main.py

RUN pip install --no-cache-dir -e . \
    && python -m spacy download en_core_web_sm

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
