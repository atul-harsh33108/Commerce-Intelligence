# Evaluation — Step 10

**Execution accuracy: 78.8% → 97.0%** (26/33 → 32/33) after fixing two systematic failures found by
the first run.

```bash
python eval/run_eval.py --check-gold     # verify the gold SQL runs (fast, no LLM calls)
python eval/run_eval.py --tag baseline   # the full run, ~7 minutes
```

Reports land in `eval/reports/*.json` with every generated query, both result sets, and per-question
timing.

---

## What this measures, and what it does not

This is a **domain evaluation over this project's own warehouse**, not a public benchmark. Being
straight about that matters more than the number:

| | |
|---|---|
| **It does measure** | the system actually deployed — this schema, this metadata, the real Olist distributions — end to end **through the gateway**, exactly as a user reaches it |
| **It cannot do** | settle "is this agent good" against anyone else's. Spider and BIRD exist so that a number is *comparable*, and a hand-written set is not comparable by construction |

The **metric** is Spider's even though the questions are not: **execution accuracy** — run the gold
SQL, run the generated SQL, compare the result sets.

Why not the two obvious alternatives:

- **String or AST similarity** punishes a correct query written differently. `JOIN a … JOIN b` and
  `JOIN b … JOIN a` are the same answer.
- **"The expected value appears in the output"** — which the upstream agent's own accuracy test uses
  — passes a query returning the right number in the wrong shape: a per-category breakdown scores as
  correct for a question that asked for a total, because the total happens to be one of the rows.

### Comparison rules

| Rule | Why it is not just leniency |
|---|---|
| Column **names** ignored | `SUM(line_revenue)` vs `AS total_revenue` — the question asked for a number, not a label |
| Column **order** ignored | Rows compare as sorted tuples of values |
| Row **order** ignored *unless* the question is a ranking | "Top 5 by revenue" is wrong in the wrong order; "revenue per region" is not. Flagged per question with `ordered: true` |
| Numbers rounded to **2dp** | MySQL returns `DECIMAL`, the agent returns JSON; `13591687.34` vs `…340000001` is not a modelling error |
| Strings trimmed and lowercased | `'Southeast'` vs `'southeast '` is the same category |

**What the metric still cannot catch:** a query that is right on *this* data and wrong in general —
a `LIMIT 5` that matches because there happen to be five groups. That is a limitation of execution
accuracy itself, and it is why the question set deliberately includes groupings with many distinct
values.

---

## The question set

33 questions, 8 categories, in [`eval/questions.yaml`](../eval/questions.yaml) with hand-written gold
SQL. Every gold query is executed before any LLM call — **a wrong reference turns a correct agent
into a failure and is the easiest way to produce a meaningless accuracy number.**

| Category | n | What it probes |
|---|--:|---|
| simple-aggregation | 5 | `SUM`, `COUNT`, `AVG` over the fact table |
| filtering | 5 | `WHERE` on status, year, category, state |
| ranking | 5 | `GROUP BY` + `ORDER BY` + `LIMIT`, order-sensitive |
| time-series | 4 | Joins to `dim_date`; monthly, quarterly, yearly |
| trap | 4 | The four places the metadata exists to stop a wrong-but-plausible answer |
| multi-join | 4 | Two and three dimensions at once |
| ratio | 3 | Percentages and derived measures |
| counting | 3 | `COUNT(DISTINCT …)` over the right column |

---

## Before and after

| Run | Accuracy | |
|---|---:|---|
| `baseline` | **78.8%** | 26/33 |
| `after-fix-v2` | **97.0%** | 32/33 |

| Category | Before | After |
|---|---:|---:|
| simple-aggregation | 5/5 | 5/5 |
| filtering | 5/5 | 5/5 |
| ranking | 4/5 | **5/5** |
| time-series | 3/4 | **4/4** |
| trap | 3/4 | **4/4** |
| multi-join | 3/4 | **4/4** |
| ratio | 2/3 | **3/3** |
| counting | 1/3 | **2/3** |

~12.7 s per question, ~7 minutes for a full run.

---

## The failure analysis

Seven failures in the baseline. Grouped by **root cause**, not by symptom — which is the step that
turns a list of failures into two fixes:

| Cause | Failures | Whose fault |
|---|--:|---|
| "Percentage" produced a **ratio** (0.0791 instead of 7.91) | 2 | **My metadata** |
| "State" resolved to `dim_geography` instead of `dim_customer` | 2 | **My metadata** |
| Gold SQL selected a column the question did not ask for | 2 | **My benchmark** |
| Agent added a `WHERE order_status = 'DELIVERED'` nobody asked for | 1 | Ambiguous question |

**Five of seven were mine, not the model's.** That is the finding, and it is the one worth carrying
into an interview: an NL→SQL agent's accuracy is mostly a property of the metadata you give it.

### Cause 1 — my metric description asked for a ratio

The generated SQL was `SUM(is_late) / COUNT(is_late)` — *verbatim my own metric description*:

> Share of delivered orders that arrived after the estimated date - SUM(is_late) / COUNT(is_late)

The model did exactly what it was told and returned `0.0791` to a question asking for a percentage.
The number was right and the answer was wrong, which is the most expensive kind of near-miss because
it looks plausible on a dashboard.

Fixed by saying **PERCENTAGE**, writing `* 100.0` into the description, and stating the rule once at
the top of the metrics block so a metric added later inherits it.

### Cause 2 — two dimensions carry a state, and nothing said which to use

`dim_customer.state` (one row per customer, 27 real states) and `dim_geography.state_name` (a lookup
table, **28 rows including an `Unknown` member**). Nothing in the metadata said which a state-level
question should use, so `COUNT(DISTINCT state)` returned 28 where the answer is 27.

Fixed by making `dim_customer.state` explicitly canonical for state-level grouping, and by warning —
in the description the retriever can actually see — that `dim_geography` contains a synthetic
`Unknown` member.

### Cause 3 — the gold was answering a question I had not asked

*"Which single month had the highest revenue?"* — the agent answered `2017-11`, which is a correct
answer. My gold selected the month **and** the revenue, so execution accuracy called it a mismatch.

Fixed by rewording both questions to `"…, and how much was it?"` so the question and its gold agree.
**This is a fix to the benchmark, not tuning it** — and the line not crossed is recorded in
`questions.yaml`: no question was changed because the model got it wrong.

### The one remaining failure, left in on purpose

*"How many distinct products have actually been sold?"* → the agent filtered
`WHERE order_status = 'DELIVERED'` and returned 32,216 against a gold of 32,951.

The word *"actually"* genuinely invites that reading. It could be made to pass by rewording, and it
is not, because **a benchmark you edit until it passes measures nothing.** It stays as an honest
illustration that execution accuracy punishes ambiguity in the question as hard as error in the
model.

---

## The two fixes that appeared not to work — the most useful part of this step

After fixing the metadata, accuracy went 78.8% → 87.9%, and **the percentage failures were still
there, still generating the old formula.**

Two separate causes, both silent, both reporting success:

**1. The config was baked into the image.** The Dockerfile does `COPY conf ./conf`, so
`meta_config.yaml` lives *inside* the image. Editing it on the host and re-running the builder
rebuilt the index from the **old** descriptions — with a success message. The evidence was a
generated query reproducing the previous description's formula word for word, and confirming it took
one query:

```sql
SELECT LEFT(description,130) FROM meta.metric_info WHERE name LIKE '%Late%';
-- still: "Share of delivered orders ... SUM(is_late) / COUNT(is_late)"
```

Fixed by mounting `./agent/backend/conf` read-only into the container.

**2. The retrieval cache was warm.** The agent caches Qdrant recalls and embeddings in Redis db 1 —
717 keys at that point. Rebuilding the index without flushing it answers the next question from the
*previous* descriptions: the build succeeds, the index is correct, and behaviour does not change.

Both are now handled by [`scripts/rebuild_agent_knowledge.py`](../scripts/rebuild_agent_knowledge.py),
which clears MySQL `meta.*`, the Qdrant collections, the Elasticsearch index **and** Redis db 1
(db 1 only — db 0 holds the catalog cache and the gateway's rate-limit counters).

The generalisable lesson: **"I changed the config and nothing happened" has more causes than people
check.** Here it was two at once, and neither produced an error.

---

## The self-correction loop, finally observed

It fired on **0 of 33** evaluation questions — every one produced valid SQL first time, which is the
good outcome and leaves the retry path untested. Provoking it needed a question the schema cannot
answer:

> *"What is the average age of our customers?"*

| Attempt | SQL | Outcome |
|--:|---|---|
| 1 | *(empty)* | rejected |
| 2 | `SELECT AVG(age) FROM customers` | **hallucinated table and column** → validation failed |
| 3 | `SELECT NULL AS average_age` | accepted; answers `null` rather than inventing a number |

Two `Correct SQL` cycles, then a graceful surrender. That is the loop working: the guardrail is
`EXPLAIN` against the real schema, so a hallucinated column cannot reach the user as a confident
wrong answer.

**A weakness observed in the same probe.** Asked for *"profit margin per product category"* — a
measure the warehouse does not contain — it produced:

```sql
SELECT 'Error: Cannot generate SQL for profit margin per product category. ...'
```

Valid SQL that returns the error message *as a data row*. The user sees a one-row table containing a
sentence. Refusing is the right decision; smuggling the refusal through the result channel is not,
and a caller cannot distinguish it from an answer. Not fixed — it is in the agent's prompt, and this
project's rule is to integrate rather than fork it.

---

## Why not Spider, concretely

The roadmap asked for Spider, and it is the right instinct: *"measured on the Spider dev set"* is
bulletproof and a home-made set is not. What it would actually take here:

1. **20 databases, not one.** Spider's dev set spans 20 schemas. This agent is bound to one
   warehouse through `meta_config.yaml`, `db_dw` and one Qdrant collection.
2. **SQLite → MySQL.** Spider ships SQLite; the agent speaks MySQL. Every schema and every gold query
   would need translating, and dialect differences are exactly where a translation introduces errors
   the agent then gets blamed for.
3. **Metadata per database.** The agent's accuracy comes from hand-written descriptions and aliases.
   Spider provides schemas, not curated descriptions — so it would be evaluated *without the thing
   that makes it work*, and would score poorly for a reason that says nothing about the deployed
   system.

Point 3 is the real objection. Running Spider would produce a comparable number about a *differently
configured* agent. The honest options are to report a domain number and say so — what this document
does — or to build the Spider harness as a separate exercise measuring the LangGraph pipeline rather
than this deployment.

**So: 97.0% is a real, reproducible number about this system, and it is not comparable to a published
Spider score. Both halves of that sentence matter.**
