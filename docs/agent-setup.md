# Running the NL→SQL agent

The agent is an **integration, not a rewrite**. It comes from
[`dataquery-agent`](../agent/), a finished LangGraph text-to-SQL service, and pointing it at this
project's warehouse required **no application-code changes** — two config files and a metadata
description.

Precisely, the diff against the source is:

| File | Change | Kind |
|---|---|---|
| `conf/app_config.docker.yaml` | Host names, credentials, Redis database number | Configuration |
| `conf/meta_config.yaml` | The e-commerce star schema described for retrieval | Configuration |
| `pyproject.toml` | One dependency pinned (`langchain-huggingface==1.2.0`) | Dependency |
| `app/clients/mysql_client_manager.py` | **One `json_serializer=` argument** — see below | **Application code** |
| `conf/app_config.yaml` | **Not copied** — it held a live API key | Security |

**One application file changed**, and the earlier claim that none did was wrong — it was written
before the knowledge base had ever been built, and the build is what exposed the problem.

`build_meta_knowledge` samples example values out of the warehouse into `column_info.examples`, a
JSON column. Python's encoder handles `str/int/float/bool` and nothing else, so the insert died on
the first `DATE` it met:

```
StatementError: (builtins.TypeError) Object of type date is not JSON serializable
```

This is a **latent bug in the agent, not an incompatibility with this project**. The warehouse it was
written against happened to sample no `DATE`, `DATETIME` or `DECIMAL` columns. This one does,
necessarily: money is `DECIMAL(12,2)` because binary floats cannot represent 0.10, and
`dim_date.full_date` is a `DATE` because that is what a date is.

The fix is one argument to `create_async_engine` plus the function it points at. `Decimal` is
stringified rather than cast to `float` deliberately — these values end up in an LLM prompt as
examples of what a column looks like, and `27.34` is what the column looks like;
`27.340000000000003` is not.

---

## What you must do by hand

Two things, and neither can be automated from here.

### 1. A Gemini API key

The agent generates SQL with `gemini-2.5-flash` through LangChain. It needs a key, and a key is
personal — it bills to an account.

```bash
# Get one at https://aistudio.google.com/apikey  (free tier is enough for this)
cp .env.example .env
# then edit .env and set GEMINI_API_KEY=...
```

The Compose definition has **no default** for it:

```yaml
GEMINI_API_KEY: ${GEMINI_API_KEY:?set GEMINI_API_KEY in .env - see docs/agent-setup.md}
```

That is deliberate. A default would produce an agent that starts, looks healthy, and answers every
question with an authentication error from a provider — a failure three layers away from its cause.

> **The source repository had a live Gemini key committed in `conf/app_config.yaml`.** That file was
> deliberately not copied into this project, and `.gitignore` excludes it. Rotate that key.

### A dependency that drifted, and the lockfile that could not stop it

The first build produced an agent that **failed at startup**:

```
ValidationError: 1 validation error for HuggingFaceEndpointEmbeddings
  Value error, `model` must be a HuggingFace repo ID, not a URL.
  input_value={'model': 'http://embedding:80'}
```

`pip install -e .` resolves `pyproject.toml`'s ranges afresh and ignores the `uv.lock` beside it, so
the build picked up a `langchain-huggingface` newer than the code was written against. The code was
correct; the build did not reproduce the environment it was correct in.

The obvious fix — install from the lock — **does not work**, because the upstream `uv.lock` is stale:
it still lists `jieba` and contains no `spacy`, `redis` or `langchain-google-genai`, all of which
`pyproject.toml` requires. `uv sync --frozen` gets as far as `No module named spacy`.

So the fix is one pinned version (`langchain-huggingface==1.2.0`) in `pyproject.toml`, documented
there. Regenerating the lock is the right long-term fix and belongs to whoever owns that repository.

(This was, for about an hour, "the only change beyond configuration" — until building the knowledge
base exposed the JSON serialisation bug above. Two changes, both listed in the table at the top.)

The transferable lesson: *a lockfile that is not regenerated is worse than no lockfile*, because it
looks like a reproducibility guarantee and is not one.

### 2. The embedding model — **you already have it**

Schema retrieval embeds column descriptions with `BAAI/bge-large-en-v1.5`, served locally by Hugging
Face's Text Embeddings Inference.

`dataquery-agent` already contains this exact model, fully downloaded:

```
D:/project/dataquery-agent/docker/embedding/bge-large-en-v1.5    3.8 GB
  model.safetensors   1.34 GB   <- the only weight file TEI reads
  pytorch_model.bin   1.34 GB   <- the same weights again, PyTorch format
  onnx/                         <- and again, ONNX
  config.json, tokenizer.json, vocab.txt
```

So point at it instead of downloading a second copy. In `.env`:

```bash
EMBEDDING_MODEL_DIR=D:/project/dataquery-agent/docker/embedding/bge-large-en-v1.5
```

The mount is read-only, so this does not modify that repository — which the brief requires.

If you would rather this repository be self-contained (e.g. before pushing to another machine), copy
it in and leave `EMBEDDING_MODEL_DIR` unset — `agent/models/` is gitignored, so the weights never
enter Git:

```bash
mkdir -p agent/models
cp -r D:/project/dataquery-agent/docker/embedding/bge-large-en-v1.5 agent/models/
```

Only if you have neither, download it fresh (~1.3 GB for the safetensors alone):

```bash
pip install huggingface-hub
hf download BAAI/bge-large-en-v1.5 --local-dir agent/models/bge-large-en-v1.5
```

Downloading inside the image build would make a 1.3 GB layer, a slow and unreproducible build, and it
would move a licence acceptance from a person to a Dockerfile.

---

## Then

```bash
# 1. The warehouse must exist and have data in it (Step 8)
docker compose up -d warehouse-db kafka elasticsearch redis
docker compose --profile cdc up -d kafka-connect
python scripts/register_connectors.py
python warehouse/etl/cdc_sink.py --drain

# 2. Check the metadata still describes the schema that exists
python scripts/validate_agent_metadata.py

# 3. Start the agent and its stack
#    (.env must have GEMINI_API_KEY and, unless you copied the model in, EMBEDDING_MODEL_DIR)
docker compose --profile agent up -d

# 4. Build the knowledge base: metadata -> MySQL, embeddings -> Qdrant, values -> Elasticsearch
docker compose run --rm analytics-agent \
    python -m app.scripts.build_meta_knowledge -c conf/meta_config.yaml

# 5. Ask it something, through the gateway
curl -X POST http://localhost:8080/api/analytics/query \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"query":"What was the total revenue by product category in 2018?"}'
```

`$TOKEN` is a normal platform JWT — `POST /api/auth/login`. The agent is reachable **only** through
the gateway route, which is where authentication happens. Its own port 8000 is published for
development, and an agent exposed directly is an unauthenticated door into the warehouse.

---

## What is verified, and what is not

Being precise about this matters more than the feature list.

### Verified

| Check | Result |
|---|---|
| The metadata describes the schema that actually exists — 5 tables, 42 columns, 8 metrics | **31/31 checks pass** (`scripts/validate_agent_metadata.py`) |
| `warehouse_ro` can `SELECT` | 112,652 fact rows |
| `warehouse_ro` cannot `DELETE` / `UPDATE` / `CREATE TABLE` | all three refused, **MySQL 1142 command denied** |
| `warehouse_ro` cannot read the `meta` database | refused |
| Every `sync: true` column has a workable cardinality | 6–71 distinct values each |
| The gateway routes `/api/analytics/**` to the agent, with SSE buffering disabled and a 120 s timeout | route present in `/actuator/gateway/routes` |
| The agent image builds | 351 MB |
| The agent **starts and reports healthy**, having connected to MySQL (`meta` + `dw`), Elasticsearch, Redis, Qdrant and its log database | `{"status":"ok"}` |
| `GET /api/analytics/health` **through the gateway with no token** | **401 at the edge**, before the agent is touched |
| `GET /api/analytics/health` **through the gateway with a valid JWT** | `{"status":"ok"}` — the whole seam, end to end |

### Live, end to end — three questions, all correct

The knowledge base builds in ~15 s (5 tables, 42 columns, 8 metrics into MySQL; column and metric
vectors into Qdrant; **211 distinct values** into Elasticsearch).

**Q1 — "What was the total revenue by product category in 2018? Show the top 5."**

```sql
SELECT dp.category, SUM(foi.line_revenue) AS total_revenue
FROM fact_order_items AS foi
JOIN dim_product AS dp ON foi.product_id = dp.product_id
JOIN dim_date AS dd ON foi.date_id = dd.date_id
WHERE dd.year = 2018
GROUP BY dp.category ORDER BY total_revenue DESC LIMIT 5
```

| category | agent | hand-written SQL |
|---|---:|---:|
| health_beauty | 772,238.15 | 772,238.15 |
| watches_gifts | 708,850.94 | 708,850.94 |
| bed_bath_table | 538,069.26 | 538,069.26 |
| sports_leisure | 532,566.49 | 532,566.49 |
| computers_accessories | 505,476.31 | 505,476.31 |

**Exact match on all five rows.** `health_beauty` leading is a known property of the Olist dataset,
not an artefact of this pipeline.

**Q2 — "Which Brazilian region generated the most revenue?"**

```sql
SELECT T2.region
FROM fact_order_items AS T1
INNER JOIN dim_geography AS T2 ON T1.geography_id = T2.geography_id
GROUP BY T2.region ORDER BY SUM(T1.line_revenue) DESC LIMIT 1
```

**Southeast** — R$ 8,887,393 against South's 1,953,941. Correct, and it is only correct because of
`reconcile_geography()`: before that fix, 30,635 facts sat on the Unknown member and this answer
would have been computed from three quarters of the data.

**Q3 — "How many distinct customers do we have?"** — the trap the metadata warns about.

```sql
SELECT COUNT(DISTINCT dim_customer.customer_unique_key) FROM dim_customer
```

**96,096** — *exactly* the published Olist figure for `customer_unique_id`. It picked
`customer_unique_key` (one per person) over `customer_key` (one per order), which is the distinction
the column description spells out. Getting it wrong would have returned 99,441 and inflated every
per-customer metric by 3.5%.

On the earlier synthetic run the same harness also handled the second trap: *"what percentage of
delivered order lines arrived later than the estimated date"* produced
`SUM(is_late)*100.0/COUNT(is_late) ... WHERE is_late IS NOT NULL` — **7.96%** against 8.0% measured
independently — treating `NULL` as *not yet delivered* rather than *on time*.

Both traps were handled because the metadata says so, which is the argument for writing metadata at
all: **retrieval happens before generation, so what the model is told about a column decides what it
can get right.**

### Still not verified

- The **self-correction loop** has not been observed firing — all three questions generated valid SQL
  on the first attempt, which is the good outcome and leaves that path untested.
- **SSE in a browser.** The stream is confirmed arriving as `data:` frames through the gateway
  (progress → sql → result), but no browser has consumed it, because there is no frontend (Step 11).
- **Accuracy on a public benchmark** — that is Step 10, and it is not built.

---

## The seam, which is the thing worth explaining

```
OLTP services ──► Debezium CDC ──► Kafka ──► star-schema warehouse ──► NL→SQL agent
  (PostgreSQL)                                    (MySQL 8)              (Python/LangGraph)
                                                        ▲
                                                  read-only,
                                                  through the gateway
```

Three properties make it a design rather than a pile of components:

1. **The agent never touches OLTP.** Analytical queries scan millions of rows; running them against
   the order database would slow real customers down. The warehouse exists so that heavy reads and
   transactional writes cannot contend.
2. **Freshness is bounded and measured**, not assumed: median **495 ms** from OLTP commit to
   warehouse visibility (`docs/measurements.md`). That is what makes "the agent reads the warehouse"
   acceptable rather than a compromise.
3. **Least privilege is enforced by the database, not by the prompt.** Generated SQL is untrusted
   input however good the model is. The layers are: `GRANT SELECT` only (a boundary), the agent's
   own single-`SELECT` validator (a mitigation), and gateway authentication (a different concern).
   Being able to say which is which is the point.

## Operating notes

Shared infrastructure, and why:

| Component | Shared with | How they are kept apart |
|---|---|---|
| Elasticsearch | search-service's product index | Different index name (`ecommerce_agent_values`) |
| Redis | catalog cache, gateway rate limits | **Database 1**, not 0 — Redis has no namespaces, and "would not collide by accident" is not a boundary |
| MySQL | the warehouse itself | Different credential (`warehouse_ro`) and a separate `meta` schema |

Its query logs get their own PostgreSQL, following the same rule as every other service on this
platform: the thing that owns the data owns the database.
