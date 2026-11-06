# Reflection

What broke, what is missing, and what I would fix first. Started at the end of the first build
session (2026-08-10) and last updated 2026-08-19, when the final four steps landed.

The rule for this document is that it is more useful than the README. A list of features is a claim;
a list of failures is evidence that the thing was actually run.

**The single most useful thing in it**, if you read nothing else: three separate bugs in this project
had the same shape — a component that reported healthy while producing the wrong thing. Debezium
connectors `RUNNING` and publishing nothing. An Elasticsearch index whose annotations had been
silently ignored, so single-word queries worked and the one query the custom analyser existed for
returned zero. A Redis cache with a 100% hit rate in which **every hit threw**. Each time I had
verified that the component was *up* instead of verifying its *output*, and each time the fix was to
ask the system what it actually contained rather than whether it was running.

---

## What is genuinely done, and how I know

| Step | State | The evidence |
|---|---|---|
| 0 Skeleton | Done | Builds; config server, Eureka and gateway start and register |
| 1 Catalog walking skeleton | Done | 13 tests against a real Postgres 17 (Testcontainers) |
| 2 Olist ingestion | Done, **real Kaggle data** | ~481K rows in 24.9 s; every count matches the published figures |
| 3 Domain services | Done | `smoke_test.py`: **18/18** checks, one real purchase across five services |
| 4 Spring Cloud plumbing | Done | Breaker measured: 6.55 s → 0.014 s refusal, self-healing on recovery |
| 5 Outbox saga | Done | 8 fault-injection tests; compensation observed releasing stock |
| 6 Redis caching | Done, **re-measured** | 100% warm hit rate, **98.8% of database queries removed** — the original numbers described a cache whose every hit threw |
| 7 Elasticsearch search | Done | 32,951 products indexed through the outbox; `beaty` returns the same 2,444 hits as `health beauty`; **median 942 ms** from catalogue write to searchable |
| 8 CDC → warehouse | Done | 344K change events → 112,652 facts; **median lag 495 ms** |
| 9 Agent integration | Done | 31/31 metadata checks; **3/3 questions correct on real data**, verified against hand-written SQL |
| 10 Evaluation | Done | **78.8% → 97.0%** execution accuracy; 5 of 7 baseline failures were my metadata |
| 11 Frontend | Done | A browser completed a purchase (`PENDING`→`CONFIRMED` in 1,289 ms, stock 20→18) and got a correct agent answer; 18 SSE frames over 17.3 s |
| 12 Containers / CI | Done, except the cluster | All **9 services run as containers**, 18/18 smoke against them, and the same stack ran **on an AWS EC2 instance** with a browser purchase against it; CI is **green on GitHub Actions** — its first run failed two of six jobs, both of them checks that could not fail on my laptop (below); 44 Kubernetes objects pass `kubeconform -strict` but have **never been applied to a cluster** |
| 13 Observability | Done | Prometheus + Grafana + Zipkin; **one trace, 20 spans, 4 services, 5 Kafka hops**, connected across the outbox — before the fix the same order produced 4 disconnected traces |

---

## The two things that make the headline numbers weaker than they look

### 1. ~~The data is synthetic~~ — resolved, and the comparison is itself a result

*Superseded.* The real Kaggle download was supplied and everything re-run from empty volumes. Every
row count matches the dataset's published figures exactly.

The interesting part is what **did not** change: load 30.3 s → 24.9 s, CDC throughput 747 → 749
records/s, median lag 510 → 495 ms. Those are properties of Postgres, Kafka and a poll interval, not
of the strings inside the rows — which is what the synthetic run was for, and it predicted them well.

What *did* change is the one thing synthetic data could not have caught: **the geography bug below.**
My generator gave every state exactly one city and populated `state` on every customer, so the
late-arriving-attribute problem never surfaced. Real data has thousands of cities, a different
arrival order, and enough volume that 38,569 customer stubs were created during the snapshot. The bug
needed real data to appear.

### 2. The agent now works — and all three unobserved items have since been observed

*Superseded.* Given a Gemini key and the embedding weights (both were available all along — the model
was already downloaded in `dataquery-agent`), the agent answers correctly: three questions, all
verified against hand-written SQL, including the two traps the metadata exists to prevent.

What is still *unobserved* rather than broken:

- ~~**The self-correction loop never fired.**~~ *Resolved in Step 10.* It fired on 0 of 33
  evaluation questions, and was then provoked deliberately with a question the schema cannot answer:
  it hallucinated `SELECT AVG(age) FROM customers`, validation rejected it, and the third attempt
  returned `NULL` rather than inventing a number. Two correction cycles.
- ~~**SSE has not been consumed by a browser.**~~ *Resolved in Step 11.* The storefront renders the
  frames and timestamps each one on arrival: 18 progress events spread across 17.3 s, which is the
  first proof that the gateway is streaming rather than buffering — a Python client reading the same
  frames cannot tell you *when* they arrived. It also showed, for the first time, that the three
  recall branches run concurrently: all three start within 141 ms of each other and finish 8 s apart.
- ~~**No accuracy number.**~~ *Resolved.* 97.0% execution accuracy — and it is a DOMAIN number, not
  a Spider number, which `docs/evaluation.md` argues rather than glosses.

---

## What broke, in order of how much time it cost

### Spring's self-invocation trap (Step 5) — the most expensive

`OutboxRelay`'s `@Scheduled` method called its own `@Transactional` method. `@Transactional` is
implemented by a proxy, and a call on `this` does not go through the proxy — so the annotation did
nothing, and the pessimistic lock in `claimUnpublished` threw
*"no transaction is known to be in progress"* twice a second, forever.

The error points at the **query**, not at the call. I read the repository, the lock hint and the
Hibernate docs before the shape of the bug registered. Fixed by splitting `OutboxRelay` (the timer,
which must never die) from `OutboxPublisher` (the unit of work, which must commit or not) — which is
also the right decomposition for a second reason: the relay *catches* exceptions to survive a broker
outage, and catching inside a transaction Spring has marked rollback-only produces
`UnexpectedRollbackException` at commit.

### Double JSON encoding (Step 5)

The platform producer serializer was `JsonSerializer` while the relay sends an already-serialised
payload, so the broker received a JSON string containing JSON. Every consumer failed to bind it,
retried three times, and tried to dead-letter it — and the **visible** symptom was a missing DLT
topic, two layers from the cause. The escaped quotes in one log line were the only clue.

Lesson: decide *once* where serialisation happens. Here it is the outbox writer, because that is
where the payload has to become durable bytes anyway.

### `auto.create.topics.enable=false` blocking Debezium (Step 8)

My own deliberate broker setting. Debezium relies on auto-creation, so with it off the connectors
reported `RUNNING`, the tasks reported `RUNNING`, and the log filled with
`UNKNOWN_TOPIC_OR_PARTITION` once a second. **A connector that is "running" and producing nothing is
the worst diagnostic shape there is** — every status endpoint says the system is fine.

### The sink that never committed (Step 8)

`cdc_sink.py` committed only when a batch filled. On a quiet stream the threshold was never reached,
so changes sat in an uncommitted transaction indefinitely — the warehouse stale forever while the
sink reported itself healthy and busy. Found by the lag measurement: five probes, none visible within
60 s, from a consumer that had already read them.

A streaming sink needs commit-on-size **and** commit-on-time. The first drains a backlog; the second
bounds freshness when there is none.

### `KafkaAdmin` ignoring a `List<NewTopic>` (Step 5)

It scans for `NewTopic` and `NewTopics` beans and nothing else. A `List<NewTopic>` bean is created,
injected nowhere, and silently ignored — so three DLTs were "declared" and did not exist, and each
poison message then blocked for the full 60-second metadata timeout.

### A list in two Spring Cloud Config sources does not merge (Step 1)

I put a per-route override in `api-gateway-dev.yml`. Because profile beats specificity, that file's
one-element `routes` list **replaced the entire route table** and every `/api/**` request 404'd. It
looked like a Eureka registration problem because the services were what had just changed.

Rule: a list belongs in exactly one file. Per-environment differences go in scalars the list
interpolates, or in `metadata:` on the route.

### The edge stricter than the services it fronts — three times

`/api/products/key/*`, `/api/products/bulk` and `/api/inventory/*` were each missing from the
gateway's public-route list while the service behind served them anonymously. Each produced a 401
that looked like a broken token.

The gateway comment predicted the *other* direction (a service closing a route while the edge stayed
open, which is safe). Three instances of the unsafe-for-functionality direction is enough to say the
duplication is a real cost, not a theoretical one. **The fix I did not do:** have the services
publish their public routes and the gateway consume that, so there is one source of truth.

### A stale lockfile in the copied agent (Step 9)

`pip install -e .` ignored `uv.lock` and resolved a newer `langchain-huggingface` whose API rejects a
URL as `model` — so the agent failed at startup in code that was correct when written. Installing
from the lock does not work either: the lock is stale (still lists `jieba`, has no `spacy`).

**A lockfile that is not regenerated is worse than no lockfile**, because it looks like a
reproducibility guarantee and is not one.

### 27% of the warehouse pointing at "Unknown" — found only by real data

The first real CDC run left **30,635 of 112,652 facts** on the Unknown geography member, while
`dim_customer.state` was populated for 99,441 of 99,443 rows. A complete dimension, and the facts
still said "Unknown".

`geography_id` is **derived** from `dim_customer.state` and resolved once, when the fact is written.
During the snapshot the customer was usually still a stub with `state IS NULL`, so the lookup fell to
the Unknown member — and filling the customer in afterwards does nothing to a fact already written.

I had implemented the late-arriving-dimension pattern and believed it covered this. It does not:
**a stub fixes a missing ROW; it does not fix a value DERIVED from that row's attributes.**

`reconcile_geography()` now runs when the stream goes quiet and at the end of a drain:
30,635 → **2** (the two orders placed through the API during testing, whose customers genuinely have
no state).

The reason this is the most valuable finding of the session: **nothing was broken.** No orphan row,
no constraint violation, no error in any log, every health check green. Every "revenue by region"
answer would simply have been wrong by a quarter — and the agent would have reported it confidently.

### A config change that did nothing, twice, for two different silent reasons (Step 10)

Fixing the metadata moved accuracy 78.8% → 87.9%, and the failures I had actually targeted were
**still there, still generating the old formula**. Two causes, stacked, neither producing an error:

1. **`conf/` is `COPY`ed into the image.** Editing `meta_config.yaml` on the host and re-running the
   builder rebuilt the index from the version baked into the image — with a success message. Proved
   with one query: `SELECT description FROM meta.metric_info` still returned the old text.
2. **The retrieval cache was warm** — 717 keys in Redis db 1. Even with a correct index, the next
   question is answered from the previously cached recall.

Both now handled by `scripts/rebuild_agent_knowledge.py`. The lesson generalises past this project:
**"I changed the config and nothing happened" has more causes than people check**, and a build that
prints success while reading stale input is worse than one that crashes.

### The smoke test that asserted the previous architecture (found last, worst habit)

Step 5 changed `POST /api/orders` from "reserve synchronously, return AWAITING_PAYMENT" to "return
PENDING as soon as the order and its outbox row are durable". **I did not update `smoke_test.py` in
the same commit**, and it went on passing for exactly as long as it took to run it again — then three
checks failed with `got PENDING`.

A smoke test that asserts the previous architecture is worse than no smoke test: it fails for a
reason that is not a defect, and that is how people learn to ignore a red build. Now updated to wait
for the saga and to poll for the expected state rather than sleep.

It then failed a second time, for a better reason: it always bought `page[0]`, and repeated runs had
driven that product to `available = 0`, so the saga correctly answered *"Out of stock"*. **A smoke
test has to be robust to the state left by every previous run**, because on a real machine that state
is arbitrary. It now checks its precondition — a priced product with at least 2 available — before
asserting anything.

### 12 units of stock reserved against orders that no longer exist

The clearest illustration in this repository of what a dead-letter topic actually means.

During the double-encoding period, `OrderCancelled` events could not be deserialised, so
inventory-service's listener dead-lettered them. The DLT did its job — the partition kept moving and
the service stayed alive — and the consequence is that the compensating releases **never happened**:

```
reservations:  RESERVED 12  |  CONFIRMED 5  |  RELEASED 2
ecommerce.order.cancelled.inventory-service.DLT:  12 messages
```

Twelve and twelve. The stock is held against orders that are `CANCELED` in order-service, and nothing
will ever release it, because *nothing re-reads a DLT*.

This is not a bug in the outbox or in the saga — both behaved exactly as designed. It is the missing
piece around them: **a DLT with no replay tool is a queue of problems nobody will ever solve.** The
two things this needs, neither of which is built:

1. A replay command (`scripts/replay_dlt.py <topic> <group>`) that re-publishes dead-lettered records
   to the original topic once the defect is fixed.
2. A sweeper in inventory-service for reservations that have been `RESERVED` past the saga timeout —
   the index for it already exists (`idx_reservations_status_created`) and the repository method is
   already there (`findByStatusAndCreatedAtBefore`). It was never wired to a `@Scheduled` job.

The second is fifteen lines and would have made this self-correcting. That is the single most
valuable thing left undone in the transactional half of this project.

### Two checks that were quietly depending on my laptop (first CI run)

The repository went public and the workflow ran for the first time. Two of six jobs failed, and
neither failure was in the platform — both were in checks I had written and believed.

**`./mvnw: Permission denied`.** Git tracks the executable bit; Windows filesystems do not. The
wrapper ran perfectly on the machine that produced it and was not executable anywhere else. The fix
is `git update-index --chmod=+x`, which is the only way to set the mode from Windows — `chmod` there
does nothing Git can see.

**`kubectl apply --dry-run=client` is not offline.** I had written, in three separate files, that it
"validates schema against the client's built-in API definitions" and "does NOT contact a cluster".
That is wrong: the client fetches the OpenAPI schema from the API server. On the runner every file
failed with `failed to download openapi: ... connection refused`. It passed here only because a
kubeconfig on this machine points at Docker Desktop's Kubernetes.

So the check was measuring my laptop's configuration, and the sentence describing it was confidently
false. Replaced with `kubeconform -strict`, which genuinely validates offline and additionally
rejects unknown fields the kubectl dry run accepts — 44 resources, 0 invalid.

**The lesson is narrower and sharper than "run your CI".** Both of these are checks that cannot fail
on the author's machine and therefore verify nothing there. That is the same defect as
`cache_benchmark.py` timing error responses, one level up: not an instrument pointed at the wrong
thing, but an instrument whose passing result was a property of the environment rather than of the
code. A green check on one machine is a hypothesis until it runs somewhere else.

### Two demos that failed for reasons that were not the platform (final pass)

Running everything one last time against the containerised stack, two scripts reported failures and
neither was a defect in the system under test. Both are the same mistake in different clothes: a
script asserting an outcome without checking the precondition that outcome depends on.

**`saga_demo.py` reported `expected CONFIRMED, got CANCELED`.** It bought the first priced product
from page one and asked for two units. That product had one unit left, because every previous run of
the demo and of the smoke test had decremented the same one. The saga did exactly the right thing —
refused the reservation and compensated — and the demo called it a failure. The smoke test had the
identical bug and was fixed the identical way two steps earlier, which is the part I should have
generalised at the time instead of fixing one instance.

**`saga_demo.py --expect-fail` reported `expected CANCELED, got CONFIRMED`.** That path needs
payment-service running with `PAYMENT_FAILURE_RATE=1.0`, and nothing had injected it. A bare
`expected CANCELED, got CONFIRMED` reads as "compensation is broken" when it almost always means "the
fault was never injected" — completely different findings, one line apart. The script now says which
one it is and prints the command to fix it.

And the command it prints was itself wrong on the first attempt, which is the third instance:
`PAYMENT_FAILURE_RATE=1.0 docker compose up -d payment-service` does nothing, because Compose only
forwards variables a service actually declares, and the new `apps` profile did not declare that one.
Fixed, and then the compensation path was verified for real: **CANCELED in 960 ms, DECLINED payment,
stock released, `on_hand` unmoved.**

### A documented command that had never been run (Step 12)

`docker compose --profile apps up -d` was in the README and in the compose file's own header
comment. There was no `apps` profile. The command starts the databases, starts no Java service, and
reports success — Compose does not warn about a profile nothing declares.

It had been written at Step 0 and was wrong from that moment. Nothing caught it in twelve steps
because the platform was always started the other documented way, `scripts/dev-stack.ps1`, so the
broken command was never the one anybody typed.

The same shape as the Redis serializer and the Elasticsearch hit count, in a different medium: a
claim that is only false on a path nobody exercises. The difference is that this one was in the
README, which is the file a reader trusts most and the file least likely to be executed.

There is now an `apps` profile, and it was verified the only way that means anything — by stopping
every host JVM first, so a service answering could not possibly have been the old process. Nine
images, nine healthy containers in about 90 s, 18/18 smoke checks, and the distributed trace still
spanning four services container-to-container.

**What I would take from it:** every command in a README is a claim, and the ones that are not part
of the daily loop are the ones that rot. A CI job that runs `docker compose --profile apps config`
would have caught this the day it was written — which is why `compose` is now a job, and why it is
worth being suspicious of any documented alternative path ("or, on Windows, ...") that the author
does not personally use.

### The observability stack hiding the thing it was installed to observe (Step 13)

Not a bug in the platform, and worth recording anyway because the shape recurs.

`show_trace.py` asked Zipkin for the most recent traces touching `order-service` and could not find
the order it had just placed. The list was full: Eureka heartbeats every 30 seconds from nine
services, and Prometheus scraping `/actuator/prometheus` every ten seconds from nine more. The order
path had been pushed off the end of a 40-trace page **by the monitoring traffic**. Turning on
observability made the interesting trace harder to find.

The fix is to query by the span name of the write itself. The real lesson is that "most recent" is
almost never the right index on a system that generates background traffic, and every system does.

An earlier version of the same script was worse in a more interesting way. It searched recent traces
for the order key and found one — the *polling* `GET /api/orders/key/{orderKey}` from the settle
loop, whose URL contains the key. It printed a tidy seven-span waterfall and I nearly wrote it into
`docs/measurements.md` as the Definition of Done. It was a trace of a read, presented as proof of a
write. The tell was that it was too clean: no Kafka spans, two services, ten milliseconds, for
something known to take about six hundred and cross five hops.

**The order key is now a span tag**, set in `OrderService` where the key is generated, because it is
generated server-side and therefore appears in no URL and no header — nothing else could have put it
on the span. "What happened to order a96783e8" is the question tracing exists to answer, and until
that tag existed the only index into it was time.

### A cache in which every hit failed, and two instruments that agreed it was fine (Step 6, found in Step 11)

This is the worst bug in the project, and the reason is not the bug.

`GenericJackson2JsonRedisSerializer` has a no-argument constructor that builds an `ObjectMapper` with
default typing switched on — that is what writes the `"@class"` property telling the deserialiser
what to rebuild. I passed my own mapper, because `Instant` needs `JavaTimeModule`, and the
constructor that takes a mapper uses it exactly as given. Nothing wrote `@class`. Every read came
back a `LinkedHashMap`, and the cast to `ProductResponse` threw.

So a cache **miss** worked and a cache **hit** returned an error, for the whole ten-minute TTL. The
precise inverse of a cache. It sat there through Steps 6, 7, 8, 9 and 10.

**What makes it worth writing down is that I measured it, twice, and both measurements said it was
fine.**

- `scripts/cache_benchmark.py` caught `HTTPError`, read the body to keep the connection tidy, and
  returned the elapsed time regardless. **An error response is fast.** The latency improvement it
  reported was real; it was the latency of failing. I wrote "-23% p50" in a document.
- The hit rate came from Redis's own `INFO stats`, deliberately, on the reasoning that "an
  application-reported hit rate is the application marking its own homework". That reasoning is
  correct and it did not help: Redis counts a lookup, and Redis really did return the bytes. What
  failed happened after the last thing being counted. I had made the instrument independent of the
  application and then measured the wrong stage.

And it was invisible to a person looking straight at it, because `/error` was not permitted in
catalog-service's filter chain. Spring forwards an unhandled exception to `/error`, that forward is a
fresh dispatch through the whole chain, and with no rule for it `anyRequest().authenticated()`
applies. So the 500 was rendered as:

```
401  {"message":"Authentication required. Send a valid Bearer token.","path":"/error"}
```

on a public endpoint that needs no token. Wrong status, wrong reason, wrong subsystem. Anyone
debugging that — including me, in Step 7, on a different service — starts with the token.

**What found it: a browser.** The storefront's product page fetches a product, and React StrictMode
double-invokes effects in development, so it fetched the same product twice. First 200, second 401.
Four steps of `curl` never did, because curl asks once and moves on. The roadmap's Definition of Done
for Step 11 says *"an API test is not a frontend test — curl sends no `Origin` and runs no
component"*. I wrote that line at the start of the project as a note about CORS. It turned out to be
about something much larger.

Three fixes, only one of which is the bug:

1. One typed serializer per cache — `Jackson2JsonRedisSerializer<>(mapper, ProductResponse.class)` —
   rather than re-enabling default typing. Each cache holds exactly one type, known at compile time,
   so nothing needs inferring at runtime; and default typing means the deserialiser instantiates
   whatever class name it finds in the cache, which is the shape of every Jackson gadget CVE.
2. The benchmark now returns the status alongside the timing and **refuses to report a latency for a
   batch that was not entirely 200**.
3. Every service permits `/error`, not just the two where the symptom was seen.

The general lesson is sharper than "test more". Both instruments were pointed at something adjacent
to the thing that mattered: one measured how long a response took without asking what it was, the
other measured whether Redis answered without asking whether the answer was usable. **A measurement
that cannot fail is not a measurement.** The benchmark, as written, would have reported the same
improvement against a service that returned 500 to everything — and in effect, that is what it did.

### 10,000 matches in a 32,951-document index (Step 7, found in Step 11)

Elasticsearch stops counting matches at 10,000 by default and reports `gte 10000` from there on,
because for a web search "about 10,000 results" is as useful as an exact number and much cheaper —
the collector can skip whole segments once nothing else can reach the top N.

On a 33K index that default is not a rounding. The storefront's empty query showed "10,000 matches ·
page 1 of 834" for a catalogue three times that size.

What is worth noting is why Step 7's measurements missed it. Every query I had measured — `health
beauty` (2,444), `computr acessorie` (2,867), `beleza saude` (2,444) — returns fewer than 10,000
hits, so every number in that section is correct. The case that breaks is the one with **no query at
all**, which I had not measured because there was nothing in it to get wrong. `.withTrackTotalHits(true)`
is the fix and it costs nothing at this scale.

### An index that was 80% working, which is the hardest kind of broken (Step 7)

`@Document`, `@Setting` and `@MultiField` describe an index. **They do not create one.** Spring Data
Elasticsearch applies them when *it* creates the index; call `save()` against an index that does not
exist and *Elasticsearch* creates it first, by dynamic mapping, from the shape of the first document.
Every annotation is then silently ignored. Nothing fails. Nothing logs.

What made it expensive was the shape of the symptom:

```
32,951 documents indexed              fine
GET /api/search?q=computr             30 hits      <- fuzzy matching worked
GET /api/search?q=health beauty       0 hits
```

Single-word queries worked, because the default analyser handles them perfectly well. Fuzziness
worked, because that is query-time. The one thing that did not work was the two-word category — the
exact thing the custom analyser exists for. I had already written "search works" in my notes on the
strength of the single-word queries.

The confirmation took one request and I should have made it first:

```
GET /products/_analyze {"analyzer":"product_analyzer", ...}
  -> "failed to find analyzer [product_analyzer]"
GET /products/_settings  ->  analysis: {}
```

Two things I did not know before this:

- **Underscore is not a word boundary** to the standard tokenizer, which follows Unicode text
  segmentation. `health_beauty` is one token, so "health beauty" matches nothing. Fixing it needs a
  `char_filter` — it has to run *before* tokenisation, and a token filter is too late to help,
  because by then the wrong token already exists. All 71 Olist categories are underscore-joined, so
  without it the entire category vocabulary was reachable only by typing the underscore.
- **An analyser cannot be changed in place.** Elasticsearch will add a field to a mapping and will
  not alter an existing one. There is no ALTER; there is delete, recreate, reindex.

The fix is `IndexInitializer`: create the index explicitly with `createWithMapping()` before anything
writes to it, and — the part that matters more — log an error at startup if an index exists whose
settings do not mention `product_analyzer`. That check is three lines and would have turned a
half-day into a startup log line.

The general lesson is the one this project keeps re-teaching in different costumes: **a component
that reports healthy while producing the wrong thing is worse than one that crashes.** The Debezium
connectors that were RUNNING and producing nothing, the CDC sink that never committed, and this
index are the same failure wearing different clothes. What they have in common is that I verified
the thing was *up* instead of verifying its *output*, and the fix each time was to ask the system
what it actually contains rather than whether it is running.

### The `/error` gap, which is the edge-vs-service bug pointing inward (Step 7)

Public `GET /api/search/**` returned 401 with `"path": "/error"`. The endpoint was permitted; Spring
Boot's internal `/error` forward was not, so any error inside a public endpoint came back as an
authentication failure and hid the real cause. This is the fourth variant of "the security config
does not cover a path that the framework itself uses", after three at the gateway — and the tell is
the same every time: the `path` in the response body is not the path that was requested.

### Smaller ones, each fixed where it happened

- `@ConfigurationProperties` records need `@ConfigurationPropertiesScan`; without it the failure
  reads as a missing component rather than a missing registration.
- Spring Data does not scan **nested** repository interfaces. Three in one holder class failed
  startup with `Repositories$StockItemRepository not found`.
- `YEAR_MONTH` is a reserved interval unit in MySQL 8 — using it as a column name is a syntax error
  reported against the *next* line.
- `cte_max_recursion_depth` defaults to 1000; thirteen years of days is 4,748.
- Docker Compose derives its project name from the directory, and an unrelated project on this
  machine had already claimed `ecommerce`. The first `docker compose up` **recreated a container
  belonging to that project.**
- `nohup java &` from Git Bash on Windows produces a process the same shell cannot kill. That cost a
  debugging session spent watching a stale jar serve a route table fixed twenty minutes earlier —
  hence `scripts/dev-stack.ps1`.

---

## What needs a human, and what it costs

Everything below is a step the platform cannot take for itself. Listed because "it runs with one
command" is only true after these, and pretending otherwise is the thing this document exists to
prevent.

| Step | Why it cannot be automated | Cost |
|---|---|---|
| **Download the Olist dataset** | Kaggle requires an account and accepting a licence. `docs/data-setup.md` has the exact steps; `scripts/load_olist.py` does everything after. | ~5 min, once |
| **A Gemini API key** in `.env` | It is a credential. Free tier is enough for the whole evaluation suite. | ~2 min, once |
| **The embedding weights** (`bge-large-en-v1.5`) on disk | ~1.3 GB; not committed and not downloadable without a mirror. `docs/agent-setup.md`. | ~10 min, once |
| **`scripts/create_admin.py`** | The first ADMIN cannot be created through an API that requires an ADMIN. | seconds |
| **`scripts/register_connectors.py`** | Debezium connectors are runtime configuration of Kafka Connect, not of this repository. | seconds |
| **`POST /api/products/reindex`** | The search index is a projection; a fresh Elasticsearch has nothing in it until the catalogue is replayed. | ~4.5 min for 32,951 products |
| **`./mvnw package` before `--profile apps`** | The Dockerfile packages a prebuilt jar on purpose — see the comment at its head. | ~2 min |

Two things that are **not** on this list and could reasonably be expected to be: nothing needs a
cloud account, and nothing needs a Kubernetes cluster. That is a ground rule of the project rather
than a limitation of it — `k8s/` and every AWS mapping in `docs/decisions.md` are documented and
deliberately unprovisioned.

---

## What I would fix first, in order

1. **The agent smuggles refusals through the result channel.** Asked for a measure the warehouse does
   not have, it returns `SELECT 'Error: ...'` — valid SQL whose single row is a sentence. A caller
   cannot distinguish that from an answer. The fix is in the agent's prompt, and this project's rule
   is to integrate rather than fork it, so it is recorded rather than changed.
2. **Wire the reservation sweeper**, and write a DLT replay tool. Twelve units of stock are currently
   held against cancelled orders because their release events were dead-lettered and nothing re-reads
   a DLT. The sweeper is ~15 lines against a repository method and an index that both already exist.
3. **Reconcile more than geography.** `reconcile_geography()` fixes the one derived value that broke.
   Any other fact column derived from a dimension attribute has the same exposure, and a general
   answer (re-run `build_facts` for orders whose dimensions changed) beats one method per column.
4. **Deduplicate the gateway's public-route list** against the services' own rules — three bugs from
   one duplication is the threshold at which it stops being acceptable.
5. ~~**Run the CI workflow.**~~ *Done — and it failed on the first run, which is the point.* Two of
   six jobs: `mvnw` had no executable bit, and `kubectl apply --dry-run=client` turned out not to be
   offline at all. Both were checks that could not fail on the machine that wrote them. Written up
   above.
6. **Re-audit every other measurement against the standard the cache failed.** The
   benchmark reported a fast, healthy cache that was returning errors, because it timed responses
   without reading them. That instrument is fixed; `saga_demo.py`, `measure_cdc_lag.py` and
   `run_eval.py` have not been re-read with that specific question in mind — *what would this script
   report if the thing it measures were broken in a way that is still fast?*

*(This list had "Step 7, Elasticsearch search" in it twice — as item 1 and again as item 4 — which
is its own small lesson about editing a document in two sittings. Both are gone because it is built.)*

---

## What I would do differently

**I would have written the smoke test before the third service, not after the fifth.** Every bug in
the "edge stricter than the services" family was found by it in seconds, and each had been sitting
there since the moment the route was added.

**I would not have disabled Kafka auto-topic-creation without also writing the topic-creation script
in the same commit.** The setting is right; shipping it without its counterpart cost more than it
saved, twice.

**I would have measured the cache before optimising it.** The result — p99 unchanged, 99.6% of
database queries removed — is the interesting finding, and I nearly wrote "23% faster" as the
headline. On this machine the database was never the bottleneck, so the latency number is close to
noise and the query count is the real claim. Measuring first would have made that obvious before the
annotations went in.

**I would treat "the connector is RUNNING" as meaning nothing.** Twice, a component reported healthy
while producing nothing. The check that matters is *did anything arrive at the other end*, which is
why `measure_cdc_lag.py` exists and why it was the thing that found the sink's commit bug.
