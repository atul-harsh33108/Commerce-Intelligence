# Design Decisions

Every non-obvious choice, with the reasoning that produced it and — where one exists — the measurement
that settled it. Ordered by when the decision was forced, not by importance.

A decision belongs here when a competent engineer could reasonably have chosen otherwise. Routine
choices (use Flyway, use JUnit) are not decisions; choosing Flyway *over* Liquibase when the project
already had Liquibase would be.

---

## D1 — Two database engines, PostgreSQL for OLTP and MySQL 8 for the warehouse

**Decided before the build started** and fixed for the duration, and worth restating because it is
the first thing an interviewer will push on: "why not one engine?"

| Role | Engine | Why |
|---|---|---|
| OLTP — each service's transactional database | PostgreSQL 17 | The bookstore capstone's Flyway migrations, JPA configuration and Testcontainers setup port directly. Postgres is also the stronger OLTP engine: richer indexing, real `SKIP LOCKED`, logical replication that Debezium consumes natively. |
| OLAP — the star-schema warehouse | MySQL 8 | The existing `dataquery-agent` is metadata-driven **against MySQL**. Reusing it means the agent needs *no code change* — which is the entire premise of Step 9. |

The reflex objection is that two engines is operational complexity for its own sake. Three answers:

1. **It is polyglot persistence, which is what the split is for.** The warehouse is a different
   workload with a different access pattern and a different consistency requirement. Coupling it to
   the OLTP engine because "we already have Postgres" is the same reasoning that produces one shared
   database for six services.
2. **It makes the CDC step real.** A Postgres→Postgres pipeline can be faked with `postgres_fdw`, a
   nightly `pg_dump`, or a logical replication subscription that needs no Kafka at all. A
   Postgres→MySQL pipeline cannot: the change stream has to be decoded, transformed and applied,
   which is exactly what Debezium + Kafka Connect are for. The harder thing is the honest thing.
3. **Changing it would have meant rewriting the agent**, which the brief explicitly rules out.

**What it costs, stated plainly:** two dialects to keep straight, two client libraries, two backup
stories, and a `docker-compose.yml` that is bigger. The warehouse DDL is hand-written MySQL rather
than generated, and the `MEDIUMTEXT`/`DATETIME` choices in `warehouse/ddl` do not transfer.

---

## D2 — No shared "common" library between services

The aggregator `pom.xml` publishes no classes. Every service has its own `ErrorResponse`, its own
`JwtUtil`, its own copy of each event record it consumes.

This looks like duplication and is not the kind that matters. A shared domain jar means one team's
change to a shared class forces every other service to rebuild and redeploy in lockstep — which is
precisely the coupling that splitting into services was meant to remove. A set of microservices with
a shared model jar is a distributed monolith: all of the operational cost, none of the independence.

The concrete failure it avoids shows up in Kafka. `JsonSerializer` writes the producer's
fully-qualified class name into a `__TypeId__` header and `JsonDeserializer` obeys it, so
order-service would be instructing search-service to instantiate
`com.ecommerce.order.event.OrderPlaced` — a class that does not exist there. The fix people reach for
is a shared events jar. The fix taken here is `spring.json.add.type.headers: false` plus a message
converter driven by each `@KafkaListener` method's own parameter type: **the consumer decides how to
read the bytes, because a producer publishes facts, not Java types.**

Inherited from the bookstore capstone's D12, and re-argued rather than assumed.

---

## D3 — Eureka, even though the capstone did fine with configured addresses

The bookstore routed to `http://book-service:8082`, an address supplied by the config server. That
works. "Why add a registry" deserves a better answer than "the roadmap said so".

A configured address names **one** endpoint. Run two copies of order-service and the configuration
has to name a load balancer instead — so the platform acquires a component whose job is to know which
instances are alive, which *is* a registry, only now it is somebody else's and invisible to the
application.

What Eureka buys that a fixed address cannot:

- **Per-request load balancing.** `lb://order-service` resolves to the live instance set and Spring
  Cloud LoadBalancer picks per request. A Kubernetes `Service` picks per *connection*, and the
  capstone measured what that does to a pooled reactive gateway: 20 requests through the gateway all
  landed on one of two pods (pod A 20, pod B 0). The gateway's `lb://` routes here do not have that
  problem; the one fixed-URI route (the Python agent) still does, which is why the Netty pool's
  `max-life-time` is still set.
- **Health-aware removal.** A process that stops sending heartbeats is evicted, so a crashed instance
  stops receiving traffic without anybody editing configuration.

**What it costs:** a ninth process, ~30 s of registration lag on cold start with the default
intervals (cut to 10 s here), and an AP consistency model — Eureka prefers serving a stale registry
to refusing to answer. That is the right trade for discovery (a stale address fails one request; an
unavailable registry fails all of them) and it is why self-preservation is *on* by default. It is
turned **off** in `discovery-server/application.yml` for local development only, because on one
machine a "partition" is always really a dead process, and 90 s of routing to a Ctrl-C'd service
looks like a gateway bug.

Under Kubernetes this module is largely redundant — a `Service` does the resolution — and that is
said out loud in `k8s/README.md` rather than hidden.

---

## D4 — Exactly two fixed addresses on the platform, and they are fixed for different reasons

Every service holds one hard-coded address (the config server's) and learns everything else. The
config server and the discovery server each hold their own configuration in their jar.

The circularity this avoids is real: Spring Cloud Config supports *discovery-first* bootstrap
(`spring.cloud.config.discovery.enabled`), where a client asks Eureka where the config server is.
Combine that with a config server that fetches its own config from Eureka's address and neither can
start. One of the two has to be a fixed address.

The config server is the better choice for the fixed one, because **every service needs it before it
has any beans at all** — before a datasource, before a logger, before Eureka's client. Discovery is
needed later, so its address can come from configuration. Hence `application.yml` in the config repo
carries `eureka.client.service-url.defaultZone` for everyone, and nothing carries the config server's
address except each service's own `spring.config.import`.

---

## D5 — The Compose project is named `ecommerce-ai`, explicitly

Docker Compose derives the project name from the directory, and the project name namespaces every
container, network and volume. An unrelated Python project on this machine, in a directory also
named `E-commerce`, had already claimed `ecommerce`, so the first `docker compose up` here
**recreated a container belonging to that project**.

Caught immediately and reverted, but the failure mode is worth writing down because it is silent by
design: Compose considers containers with a matching project label to be *yours*, and `down` would
have removed them. `name:` at the top of the file costs one line and makes the namespace a decision.

---

## D6 — Flyway owns the schema; Hibernate is demoted to `validate`

`spring.jpa.hibernate.ddl-auto: validate` everywhere, in every profile, including tests.

`update` is the setting that quietly adds a column on one developer's machine and nowhere else, then
produces a production failure whose cause is three weeks old. It also cannot drop, rename, or
backfill — so any real schema change needs a migration tool anyway, and having two things that both
believe they own the schema is worse than having one.

`validate` turns the entity/schema mismatch into a **startup failure with the offending column
named**, which is the cheapest possible place to find it.

Test databases run the same migrations as production, from `classpath:db/migration`. Seed data lives
in a separate location (`classpath:db/seed`) that tests deliberately do **not** load: a test that
depends on demo rows breaks the moment somebody edits the seed file, and "which rows exist" should be
the test's own decision.

---

## D7 — `catalog-service` owns the product *description*; `inventory-service` owns the *count*

The obvious modelling is one `products` table with a `stock` column. Splitting them across two
services with two databases needs a reason, because it costs a network hop on every "is this in
stock" question.

The reason is that they have opposite write patterns and opposite consistency requirements:

| | Catalog | Inventory |
|---|---|---|
| Write rate | Rare — a product's name and category change monthly | Constant — every order reserves and releases |
| Contention | None | Extreme; the hot row *is* the popular product |
| Consistency need | Eventual is fine (a stale description is a cosmetic bug) | Strict — overselling is a real-money bug |
| Cacheable | Yes, aggressively (Step 6) | No, never |

Putting them in one table means the stock counter's row-level contention sits on the same row the
catalog read path wants to cache, and cache invalidation then has to fire on every single order. The
split is what makes "cache the catalog aggressively and never cache stock" expressible at all.

The cost is honest and shows up in Step 5: reserving stock is a saga step over Kafka rather than a
`UPDATE ... WHERE stock >= n` in the same transaction as the order.

---

## D8 — The outbox lives in each service's own database, not in a shared one

See `docs/outbox.md` for the mechanism. The decision recorded here is *where the table lives*: an
`outbox` table inside `orderdb`, written by the same transaction that writes the order.

That is the whole point — the guarantee comes from the event row and the business row being in **one
ACID transaction**. An outbox in a shared "events" database would be a second transactional resource
and would need two-phase commit to be atomic with the order, which reintroduces exactly the
distributed-transaction problem the saga exists to avoid.

---

## D9 — The relay polls, and does not use Debezium, even though Debezium is already in the stack

Step 8 stands up Debezium for CDC, so the obvious economy is to have Debezium tail the `outbox` table
too — the "Outbox Event Router" is a first-class Debezium transform and is arguably the *better*
production design.

Polling is used anyway, for one reason: **the outbox has to work before Debezium exists.** Step 5 is
where the ordering guarantee is introduced and proven with a fault-injection test; Step 8 is three
steps later and adds an entire Kafka Connect cluster. Making Step 5 depend on Step 8's infrastructure
would mean the saga cannot be demonstrated, or tested, without it.

The trade is stated rather than hidden:

| | Polling relay (chosen) | Debezium outbox router |
|---|---|---|
| Latency | Bounded by the poll interval (500 ms here) | Milliseconds |
| Load | A query per interval per service, even when idle | None; reads the WAL |
| Failure mode | Relay down → events queue in the table, nothing lost | Connector down → same |
| Dependencies | None beyond the database it already has | A Connect cluster |

`SELECT ... FOR UPDATE SKIP LOCKED` is what makes the polling version safe with more than one relay
instance running, and that is the part worth knowing: without `SKIP LOCKED`, two relays block on each
other and publish serially; with it, they take disjoint batches.

---

## D10 — Consumers deduplicate on a natural key, in a database table, not in memory

The bookstore's `ProcessedOrders` was an in-memory bounded set, and its own Javadoc called that a
weakness: the guard is lost on restart while the side effect (an email that has left the building) is
not.

Here every idempotency guard is a **row with a unique constraint** in the consuming service's own
database, written in the same transaction as the effect it guards:

- `inventory-service.processed_events(event_id)` — a reservation is money-adjacent.
- `payment-service.payments(order_id UNIQUE)` — a natural key, so a caller cannot forget to send it
  and cannot send a fresh one by mistake. An event carrying a per-message UUID would be deduplicated
  only against *identical retransmissions*; republish the same order after a producer restart with a
  new UUID and it charges twice.

The rule this generalises to, and the one worth being able to state in an interview: **an idempotency
guard must be at least as durable as the effect it guards.**

---

## D11 — Cache-aside, not read-through, and never write-through

Step 6 caches catalog reads with an explicit "check Redis → miss → read Postgres → populate Redis".
Spring's `@Cacheable` makes that a one-line annotation, which is what is used; the decision is what
is *not* used.

- **Write-through** (write to cache and database together) is rejected because it makes Redis a
  participant in the write path: a Redis outage becomes a catalog *write* outage, trading an
  availability problem for a correctness-shaped one.
- **Write-behind** is rejected outright — it makes a cache the source of truth for durable data.
- **Invalidate on write** (`@CacheEvict`) is what is used, and the honest caveat is that it is
  *eventually* consistent across replicas: an evict on one node is a Redis DEL that every node sees,
  but an in-flight read on another node can repopulate with a pre-write value. The TTL is what bounds
  that, which is why there is a TTL at all rather than infinite retention plus perfect invalidation.

---

## D12 — The agent gets its own read-only warehouse credential, and no OLTP credential at all

`warehouse_ro` is granted `SELECT` on `dw.*` and nothing else — no INSERT, no temporary tables, no
access to `meta`, no account on any OLTP database.

Three layers, because a generated SQL string is untrusted input no matter how good the model is:

1. **The credential cannot write.** A `DROP TABLE` that gets past every other check fails at the
   database.
2. **The agent's own validator** rejects anything that is not a single `SELECT`.
3. **The gateway** is the only route to it, so the request carries a real user's JWT.

Layer 1 is the one that actually holds. The other two are defence in depth, and stating which is
which is the point: a prompt-level guardrail is a mitigation, a `GRANT` is a boundary.

---

## D13 — AWS is documented, never provisioned

Nothing in this repository creates an AWS resource. The mapping is written down instead:

| Local (docker-compose) | AWS | What changes in the code |
|---|---|---|
| `kafka` (KRaft, 1 broker) | **MSK** (or MSK Serverless) | `KAFKA_BOOTSTRAP_SERVERS` + IAM auth properties. No application code. |
| `redis` | **ElastiCache for Redis** | `REDIS_HOST`. No application code. |
| `*-db` (PostgreSQL 17) | **RDS for PostgreSQL** | `DB_URL`, and `rds.logical_replication=1` in a parameter group for Step 8. |
| `warehouse-db` (MySQL 8) | **RDS for MySQL** | Connection string in the agent's config file. |
| `elasticsearch` | **OpenSearch Service** | `ELASTICSEARCH_URL` + SigV4 signing. |
| `kafka-connect` + Debezium | **MSK Connect** | Connector JSON is identical; the plugin is uploaded to S3. |
| docker-compose | **EKS** | `k8s/` manifests; images from ECR or GHCR. |
| — | **Secrets Manager / SSM** | Replaces the `${JWT_SECRET}` env var; config server needs no change because the value is already a placeholder. |

The property worth noticing: **every row's "what changes" column is an environment variable.** That
is not luck — it is what `application-prod.yml` being nothing but `${ENV_VAR}` placeholders buys, and
it is the difference between "cloud-ready" and "cloud-shaped marketing".

---

## D14 — Where the Olist data goes, and why it is not one bulk `INSERT`

See `docs/data-setup.md`. Two decisions inside the loader are worth pulling out:

- **It loads through SQL, not through the services' REST APIs.** 100K orders through
  `POST /api/orders` would exercise the saga 100K times and take hours; it would also be *wrong*,
  because these are historical facts, not new business events. Seeding a database with history and
  placing an order are different operations and should not share a path.
- **It is idempotent by construction** (`ON CONFLICT DO NOTHING` keyed on the Olist natural keys), so
  a half-finished load is resumed rather than restarted, and re-running it against a populated
  database is a no-op rather than a duplicate-key crash.


---

## D15 — Gemini for local development, Bedrock for an AWS deployment, and the switch is small

The agent generates SQL with `gemini-2.5-flash`. The obvious question for a project whose deployment
story is AWS is why it is not Bedrock, and the answer is that these are two different environments
with two different constraints — not one choice.

### What the code actually couples to

`agent/backend/app/agent/llm.py` is six lines:

```python
llm = init_chat_model(
    model=app_config.llm.model_name,
    model_provider=app_config.llm.provider,
    api_key=app_config.llm.api_key,
    temperature=0,
)
```

`init_chat_model` is LangChain's provider-agnostic factory, and `model_provider` already comes from
configuration — `langchain-deepseek` is in the dependencies alongside `langchain-google-genai`, so
the service was written to be switchable. **Nothing in the graph, the prompts, the retrieval or the
self-correction loop knows which model it is talking to.**

### What switching to Bedrock costs, precisely

| | Change |
|---|---|
| `pyproject.toml` | add `langchain-aws` |
| `app_config.yaml` | `provider: bedrock_converse`, `model_name: anthropic.claude-3-5-sonnet-20241022-v2:0` (or whichever model is enabled) |
| `llm.py` | **three lines** — `api_key` is currently passed unconditionally, and Bedrock authenticates with IAM, not a key. It needs the same conditional `base_url` already has. |
| Deployment | An IAM role with `bedrock:InvokeModel` + `bedrock:InvokeModelWithResponseStream`. On EKS that is IRSA; the container then needs no credential at all. |

That last row is the actual argument for Bedrock, and it is a security argument rather than a
capability one: **the API key disappears**. No `GEMINI_API_KEY` in a Secret, nothing to rotate,
nothing to leak — the exact class of problem this project already hit once, when the source
repository turned out to have a live key committed in its config file.

### Why local development stays on Gemini

- **Cost.** Bedrock is pay-per-token with no free tier. Gemini's free tier covers a portfolio
  project's traffic entirely. The brief's cost discipline applies to the LLM the same way it applies
  to RDS.
- **Setup.** Bedrock needs an AWS account, a region where the model is offered, and per-model access
  to be requested and granted in the console. A `GEMINI_API_KEY` is a web form.
- **It is one config line either way.** Nothing is being locked in, so paying to develop against
  Bedrock buys nothing that could not be verified in an afternoon later.

### The honest caveat

**Nothing here has been tested against Bedrock.** No AWS resource is created by this repository —
that is a project ground rule — so the "three lines" figure is read off the code, not measured.
Bedrock's Converse API also handles streaming and tool calls slightly differently from Gemini's, and
the SSE path in particular would need re-verifying rather than assumed.

The right way to find out is one afternoon with a Bedrock model enabled, not a rewrite. That it *is*
one afternoon is the point of routing every model call through `init_chat_model`.

---

## D16 — The search index is a read model, rebuilt from events, and owns nothing

`search-service` has no database. It has an Elasticsearch index and a Kafka consumer, and if the
index is deleted the only cost is the time to rebuild it — 263 seconds, measured.

That is a deliberate constraint, and it decides three things that would otherwise each need
arguing:

**Nobody writes to Elasticsearch except the listener.** There is no admin endpoint on
`search-service` that edits a document, because a document is a projection of a row in
catalog-service's Postgres. If the two disagree, Postgres is right by definition — there is no
reconciliation logic to write, and no question about which copy wins.

**The reindex goes through the event path, not around it.** `POST /api/products/reindex` lives on
**catalog-service**, not on search-service, and all it does is append one outbox row per product. It
is tempting to give the search service a bulk loader that reads the catalogue database directly —
it would be perhaps twenty times faster. It would also be a second code path that can drift from
the first, and the first is the one that runs in production every day. A rebuild that exercises the
same pipeline as a normal write is a rehearsal; one that bypasses it is a different program that
happens to produce a similar result. The 263 s is what that costs, and it is worth it.

**`ProductUpdated` carries the whole product, not a delta.** A consumer must be able to build a
complete document from one message, without reading back to the source. That makes the event bigger
and makes replay trivial: process the last event for each key, in any order, and the index is
correct. A delta event would require ordered, gapless delivery of the entire history — which Kafka
gives you per partition and nothing gives you across a topic reassignment.

The cost is that the index is eventually consistent, by a median of 942 ms. For a product catalogue
that is invisible. For inventory it would not be, which is why stock levels are read from
inventory-service at checkout and never from the search index — a searchable "in stock" is a hint,
and the reservation is the truth.

---

## D17 — The storefront runs without a dev proxy, and the token lives in localStorage

Two frontend decisions worth arguing, because both have a comfortable default that is wrong here.

### No dev proxy

Vite will proxy `/api` to `localhost:8080` in three lines. Every request then leaves the browser as
same-origin, no preflight is sent, and CORS stops applying in development entirely — you find out
whether the gateway's origin list is right when you deploy.

The roadmap wrote the reason down before the code existed: *"an API test is not a frontend test —
curl sends no `Origin` and runs no component."* So there is no proxy. `http://localhost:3000` is one
of the three origins named in `api-gateway.yml`, the browser sends a real `OPTIONS` before every
authenticated call, and a missing `allowed-headers` entry fails on the first page load instead of in
a staging environment. The measured cost is one extra round trip per distinct preflight, cached for
an hour by `max-age`.

### The JWT in localStorage, with the trade stated

`localStorage` is readable by any script on the origin, so an XSS reads the token. The reflexive
answer is an httpOnly cookie — and that is not available here, for a reason that is in the gateway
config rather than the frontend: `allow-credentials: false`, and authentication is a bearer header.
A cookie the browser attaches automatically is exactly what makes CSRF possible, so switching would
mean a CSRF token on every write, and it would turn the CORS origin list from a convenience into a
security boundary.

The honest production answer is neither: a backend-for-frontend holding the token server-side and
handing the browser a session cookie scoped to it. That is a component this project does not have and
would not learn much from building. So the trade is recorded in `frontend/src/api.js` next to the
code rather than left implicit, the token is short-lived, and nothing else is put in `localStorage`
except a cart that contains no personal data.

### What the browser found that curl had not

Worth recording as a result rather than a decision. Two bugs surfaced within minutes of the first
page render — a Redis serializer that made every cache hit throw, and a search index reporting 10,000
matches out of 32,951 — and neither was reachable by the API tests that had been running for four
steps. One needed *two identical requests* (React StrictMode double-invokes effects; curl asks once),
the other needed a query so simple nobody had measured it. The frontend was scheduled as a `[PORT]`
step, meaning "move fast, it is not the point". It found more than the two steps before it.

---

## D18 — Brave and Zipkin now, OpenTelemetry and X-Ray later, and the swap is a dependency change

Micrometer Tracing is a facade with two bridges: Brave (Zipkin's tracer) and OpenTelemetry. The
application code is identical either way — `Tracer`, `Span`, `@Observed`, and Spring's own
instrumentation all sit above the facade.

**Brave, for now.** It is the bridge Spring Boot documents first, Zipkin is one 512 MB container with
no collector in front of it, and on a laptop that difference is real. An OTel setup that is honest
about production needs the Collector as a separate process, which is a third component to run and
configure before the first span appears.

**W3C `traceparent`, not B3.** This is the decision that makes the above reversible, and it is
`management.tracing.propagation.type: w3c`, one line. Brave defaults to its own B3 headers, which work
perfectly between services that all use Brave — exactly the assumption that breaks at the first
component someone else wrote. This platform already has one: the Python analytics agent. `traceparent`
is what every current tracer speaks.

**What an AWS move actually costs.** Swap `micrometer-tracing-bridge-brave` +
`zipkin-reporter-brave` for `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` in nine
`pom.xml` files, point `OTEL_EXPORTER_OTLP_ENDPOINT` at an ADOT Collector sidecar, and configure that
collector to export to X-Ray. No application code changes, because none of it names Brave.

Prometheus and Grafana map to Amazon Managed Service for Prometheus and Amazon Managed Grafana with
no code change at all — `/actuator/prometheus` is a scrape target regardless of who is scraping it.

**The honest caveat, same as D15's:** none of this has been run against X-Ray, because no AWS
resource is created by this repository. X-Ray's trace-id format differs from W3C's, and the ADOT
Collector translates it — that translation is the part that would need verifying rather than
assuming.

---

## D19 — The trace is carried across the outbox in the database row

The outbox exists to decouple committing an order from publishing its event: the request thread
writes a row, the response returns, and a scheduled relay publishes up to 500 ms later on a different
thread in a different transaction. Everything good about the pattern follows from that separation.

**A trace context lives on a thread.** So the separation severs it. The relay's own scheduled tick
starts a fresh trace, and every consumer downstream attaches its work to *that* — meaning the
inventory reservation and the payment authorisation for an order are traced under "the outbox relay
woke up" rather than under the order.

The failure mode is worth naming precisely, because it is not an absence: **four disconnected traces,
each of which looks completely healthy on its own.** Nothing is missing, nothing errors, and the
question "what happened to this order" simply has no answer. That is a strictly worse state than
having no tracing, which at least does not mislead.

Three options were available:

1. **Accept it.** The synchronous half is traced; the async half is not. Cheap, and it gives up
   tracing on the only part of this platform where the call graph is not obvious from the code.
2. **Copy the `traceparent` header onto the Kafka record at publish time.** Nearly free, and wrong in
   a way that takes a while to find: Spring Kafka's own observation *also* injects a `traceparent`
   during `send()`, so the record carries two, and which one a consumer honours depends on which
   library it happens to use.
3. **Store the traceparent and restore the context before sending.** One nullable column, and the
   relay opens a span parented to the stored context. Spring's instrumentation then injects exactly
   one header, by the same code path used everywhere else.

Option 3. What tipped it beyond correctness is that restoring the context produces an `outbox publish`
span sitting between the request and the consumer, whose duration and *position* show the outbox
delay directly — in the measured trace, ~250 ms of a 608 ms saga is waiting for a relay poll. The cost
of the pattern stops being an argument and becomes a bar on a screen.

The column is nullable and a null is not an error: an event appended outside any request — the
catalogue reindex, a seed — legitimately has no trace to carry.
