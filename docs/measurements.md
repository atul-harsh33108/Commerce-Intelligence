# Measured numbers

Every number this project claims, with what produced it and when. Nothing here is estimated,
projected, or copied from documentation.

**Tag legend**
- `REAL` — measured against the real Olist Kaggle download.
- `SYNTHETIC` — measured against `scripts/generate_synthetic_olist.py` output.

**Everything below is now `REAL`** unless a row says otherwise. The synthetic run happened first,
before the download was available; where a number changed materially, both are shown, because the
comparison is itself a result — the throughput and latency figures barely moved, which is what you
would expect if they are properties of the pipeline rather than of the data.

**Machine:** Windows 11, Docker Desktop 29.2.1, OpenJDK 21.0.11, Python 3.12.10.
Every service and database is a container or a local JVM on the same laptop, so absolute latencies
are worse than a real deployment and the *relative* numbers (cache hit vs miss, before vs after) are
the ones worth reading.

---

## Step 2 — Data ingestion  `REAL`

Loaded from the real Kaggle download into empty volumes: `python scripts/load_olist.py --source data/olist`.

| Target | Table | Rows loaded | Published Olist figure | Match |
|---|---|---:|---:|:--:|
| catalog-service | `products` | **32,951** | 32,951 | ✔ |
| user-service | `users` | **99,441** | 99,441 | ✔ |
| user-service | distinct people (`customer_unique_id`) | **96,096** | 96,096 | ✔ |
| inventory-service | `stock_items` | 32,951 (679,080 units) | — | derived |
| order-service | `orders` | **99,441** | 99,441 | ✔ |
| order-service | `order_items` | **112,650** | 112,650 | ✔ |
| payment-service | `payments` | **103,886** | 103,886 | ✔ |
| | **Total** | **~481,000 rows** | | |

**Every count matches the dataset's published figures exactly**, which is the check that the loader
neither dropped nor duplicated a row. 0 orphan order items.

| | Value |
|---|---:|
| Full load, five databases, from empty volumes | **24.9 s** |
| Re-run against populated databases | **0 rows inserted** (idempotent) |
| Distinct categories | 71 |
| Order date range | 2016-09-04 … 2018-10-17 |

Order status distribution: 96,478 DELIVERED · 1,107 SHIPPED · 625 CANCELED · 609 UNAVAILABLE ·
314 INVOICED · 301 PROCESSING · 5 CREATED · 2 APPROVED.
Payment methods: 76,795 CREDIT_CARD · 19,784 BOLETO · 5,775 VOUCHER · 1,529 DEBIT_CARD ·
3 NOT_DEFINED. Total payment value R$ 16,008,872.12.

*(The earlier `SYNTHETIC` run loaded ~490,000 rows in 30.3 s — the same order of magnitude, which is
the point: load time is a property of Postgres and COPY, not of the content.)*


---

## Deployed to AWS  `REAL`

An EC2 t3.xlarge (4 vCPU, 16 GiB, Ubuntu 24.04) running `./deploy/deploy.sh`. The instance already
existed; **nothing in this repository creates a cloud resource**, and every managed-service mapping in
`docs/decisions.md` and `k8s/README.md` remains a mapping.

### Verified on the deployed system, not locally

| | |
|---|---|
| Containers | **22 up**, 20 with healthchecks reporting healthy |
| Memory | **7.0 GiB used of 15** |
| Data | 99,441 orders · 112,650 order items · 103,886 payments · **R$ 16,008,872.12** — identical to the local figures, because it is the same loader against the same CSVs |
| Search index | **32,951 documents** |
| `scripts/smoke_test.py` | **18/18** |
| `scripts/saga_demo.py` | PASS — stock removed, payment captured |
| Typo search | `beaty` → 2,444 `health_beauty`; `computr acessorie` → 2,867; `beleza saude` → 2,444 |
| **Browser purchase** | order `a4d127c1…`, `PENDING` → `CONFIRMED` in **1,513 ms**, freight applied, tracker rendered |
| Search round trip, from a browser over the internet | **216 ms** (31 ms on the laptop — the difference is the Atlantic, not the code) |

The four exposed ports are 30080 gateway, 30081 storefront, 30090 Prometheus, 30300 Grafana, each
restricted to a single source address. Everything else — five Postgres, MySQL, Kafka, Redis,
Elasticsearch, Zipkin, config and discovery servers, five internal services — is reachable only on the
Docker network.

### Four bugs that only a real deployment could find

Every one of these passed locally and CI, and every one is a claim that was false off the author's
machine.

**1. CORS origins were hardcoded to `localhost`.** The gateway's allowed-origin list named three
localhost ports. A storefront served from `http://<host>:30081` fails every preflight, and the symptom
is a browser console message that mentions neither the gateway nor the deployment. Now
`${CORS_ALLOWED_ORIGINS:...}`, so a new host is a variable rather than an edit.

**2. A required-variable guard made every other profile undeployable.** `analytics-agent` declared
`GEMINI_API_KEY` with `:?`, and Compose interpolates the whole file before it looks at profiles — so
`--profile apps`, which involves no agent, refused to start without a Gemini key. The evidence had
been sitting in the repository for weeks: `ci.yml` passed `dummy-for-validation` purely to make
`docker compose config` run, a workaround for our own guard that nobody questioned.

**3. Compose resolves override paths against the project directory.** The storefront's build context
was `..` relative to `deploy/docker-compose.ec2.yml`, which reads correctly and is wrong — relative
paths in an override resolve against the *first* `-f` file's directory. It failed with
`lstat /home/ubuntu/deploy: no such file or directory`, after four minutes spent building the nine
service images that came first.

**4. `depends_on: service_started` is not readiness.** api-gateway launched the moment
config-server's *container* existed, could not fetch configuration, exhausted ~21 s of retry backoff,
and exited 1 with `ConfigClientFailFastException`. Compose then refused to start everything that
depended on it, so the visible symptom was five missing containers and a gateway that "would not
start" — pointing at the last thing to fail rather than the first. All fifteen conditions are now
`service_healthy`. **This is the same defect as three earlier ones in this project: a check that
confirmed a component was up rather than that it could do its job.**

### And one that had nothing to do with this project

The host previously ran k3s. `systemctl disable --now k3s` stops the service and **leaves its
netfilter state behind** — 510 `KUBE-`/`CNI-`/`FLANNEL` iptables rules and the `cni0` and `flannel.1`
interfaces were still live. They intercepted Docker's IPv4 loopback publishing, producing a genuinely
confusing split:

```
curl -6 [::1]:30080      -> 200
curl -4 127.0.0.1:30080  -> connection refused
python urllib 127.0.0.1  -> connection refused
```

The service worked perfectly from outside the host and was unreachable from inside it, so the smoke
test failed while every container reported healthy and the public URL served traffic. Fixed with the
`k3s-killall.sh` that k3s ships for exactly this, plus a Docker restart so it reinstalls its own
rules.

The general lesson is in `docs/deployment.md`: **stopping a service does not undo what it did to the
host.**

Measured 2026-08-20.

---

## Step 12 — Containers, deploy, CI  `REAL`

### The documented one-command start did not exist

`docker compose --profile apps up -d` appeared in the README and in `docker-compose.yml`'s own header
comment. **There was no `apps` profile.** Running it starts the databases and silently no Java
services — Compose does not warn about a profile nothing declares, so the command appears to succeed.

Both places had said it since Step 0. It survived because the way the platform was actually started
throughout was `scripts/dev-stack.ps1`, which runs the services as host JVMs — so the documented
command was never the one being run.

Now it is true, and was verified by stopping every host JVM first:

| | |
|---|---|
| Images built | **9 of 9** |
| Containers healthy | **9 of 9**, in ~90 s from `up -d` |
| `scripts/smoke_test.py` | **18/18**, entirely against containers |
| Search | `beaty` → 2,444 hits, `health_beauty` |
| Distributed trace | **20 spans, 4 services** — tracing works container-to-container, unchanged |

### One code base, two environments

The roadmap's constraint for this step was *"Spring profiles and env vars only, never a second copy
of the code"*, and it held without any work, because every external address in `config-repo` was
already `${VAR:localhost-default}`:

```
DB_URL  ELASTICSEARCH_URIS  EUREKA_URL  KAFKA_BOOTSTRAP_SERVERS
REDIS_HOST  REDIS_PORT  ZIPKIN_ENDPOINT  CONFIG_SERVER_URL  AGENT_URL  CONFIG_REPO
```

Host JVM and container differ only in what those resolve to. Nothing was forked, nothing was
duplicated, and the same property is what makes the Kubernetes manifests a ConfigMap rather than a
second copy of the configuration.

### Images

One `Dockerfile` for all nine services, selected by `--build-arg MODULE=`. Two-stage, with
`jarmode=tools extract` splitting the jar so `lib/` and the application land in separate layers —
dependencies first, application last, because reversing those two lines makes the split worthless.

| | |
|---|---|
| Image size | **403 MB** (≈180 MB JRE base, ≈200 MB dependencies, ≈2 MB application) |
| Base | `eclipse-temurin:21-jre-alpine` |
| Runs as | non-root (`app`), `readOnlyRootFilesystem`, all capabilities dropped |
| Heap | `-XX:MaxRAMPercentage=75` — sized from the cgroup limit, so nothing needs re-tuning |

Verified rather than assumed: `docker run` on the discovery-server image answered
`/actuator/health` → **200**.

### Kubernetes

44 objects across 11 files. `kubeconform -strict -summary k8s/base/` — **44 resources, 0 invalid,
0 errors**, validated offline against bundled JSON schemas.

| | |
|---|---|
| Deployments + Services | 9 |
| HorizontalPodAutoscalers | 7 (not on config-server or discovery-server — scaling a registry on CPU is how you get a split one) |
| PodDisruptionBudgets | 9 |
| NetworkPolicies | 2 — default-deny plus an explicit allow |
| Ingress | **1**, on the gateway only |

**These have never been applied to a cluster**, and `k8s/README.md` says so in its first line. A
client-side dry run validates schema and nothing else.

### CI

Six jobs: `java`, `python`, `frontend`, `images` (needs `java`), `manifests`, `compose`.

Three of them were verified by running their steps locally, which is the most that can be said:

| Job | Locally |
|---|---|
| `frontend` | `npm ci` + `npm run build` → 49 modules, **0 vulnerabilities**, 950 ms |
| `images` | all nine `docker build` succeeded |
| `manifests` | `kubeconform -strict` → 44 resources, 0 invalid |

### The pipeline has now run, and the first run failed

The repository went public on 2026-08-19 and the workflow executed for the first time. **Two of six
jobs failed**, and both failures were the kind that only a real runner can find:

| Job | Result | Cause |
|---|---|---|
| `Compose and SQL sanity` | pass | |
| `Storefront build` | pass | |
| `Python checks` | pass | |
| **`Java build and tests`** | **fail** | `./mvnw: Permission denied` (exit 126) |
| **`Kubernetes manifests`** | **fail** | `failed to download openapi: ... connection refused` |
| `Container images` | skipped | `needs: java` |

**`mvnw` had no executable bit.** Git stores the mode, Windows filesystems do not track it, so a
wrapper that runs perfectly on the machine it was written on is not executable anywhere else. Fixed
with `git update-index --chmod=+x`, which is the only way to set it from Windows.

**`kubectl apply --dry-run=client` contacts the cluster.** This one is a correction to a claim made
in three files. `--dry-run=client` still downloads the OpenAPI schema from the API server to validate
against; it passed locally only because a kubeconfig here points at Docker Desktop's Kubernetes. The
check was silently depending on the author's laptop, which is precisely the class of thing CI exists
to catch, and it could not be caught until CI actually ran. Replaced with `kubeconform -strict`,
which validates offline against bundled schemas and additionally rejects unknown fields that the
kubectl dry run accepts.

Neither bug was in the platform. Both were in the claims made *about* the platform — the same pattern
as the `--profile apps` that did not exist.

Measured 2026-08-19.

---

## Step 13 — Observability  `REAL`

`docker compose --profile observability up -d` → Prometheus (host 9091), Grafana (3001), Zipkin (9411).
Brave + Zipkin via Micrometer Tracing, sampling at 1.0, propagating W3C `traceparent`.

### The Definition of Done: one trace, the whole order path, per-hop timings

`python scripts/show_trace.py` — places one real order and renders the trace it produced. Warm run:

```
trace 6a8637882bbdf9dc3b9dd4c4487e278e
  20 spans across 4 services: api-gateway, inventory-service, order-service, payment-service
  total 608 ms

service            span                                 start      dur
api-gateway        http post                               0ms    54.6ms
api-gateway        http post                               2ms    51.8ms
order-service      http post /api/orders                   4ms    49.1ms
order-service      security filterchain before             5ms     1.7ms
order-service      authorize request                       6ms     0.3ms
order-service      secured request                         6ms    46.3ms
order-service      security filterchain after             53ms     0.2ms
order-service      outbox publish                          91ms    2.8ms   <- the outbox gap
order-service      ecommerce.order.placed send             91ms    2.8ms
inventory-service  ecommerce.order.placed receive          95ms   28.1ms
inventory-service  outbox publish                         146ms    3.1ms
inventory-service  ecommerce.inventory.reserved send      146ms    3.1ms
order-service      ecommerce.inventory.reserved receive   150ms   10.7ms
payment-service    ecommerce.inventory.reserved receive   150ms   71.2ms
payment-service    outbox publish                         415ms    3.3ms
payment-service    ecommerce.payment.completed send       415ms    3.4ms
inventory-service  ecommerce.payment.completed receive    419ms   19.6ms
order-service      ecommerce.payment.completed receive    419ms   14.5ms
order-service      outbox publish                         606ms    2.2ms
order-service      ecommerce.order.confirmed send         606ms    2.2ms

span kinds: CLIENT x1, CONSUMER x5, INTERNAL x8, PRODUCER x4, SERVER x2
```

**One trace, four services, five Kafka hops.** Reading it as the saga:

| Interval | What it is |
|---|---|
| 0 → 53 ms | the synchronous part: authenticate at the edge, validate, price, commit the order *and* the outbox row in one transaction, return `PENDING` |
| **53 → 91 ms** | **the outbox delay** — the request has already returned; the relay has not yet polled |
| 95 → 123 ms | inventory reserves the stock |
| 150 → 221 ms | payment authorises (71 ms, the slowest single hop) |
| **221 → 415 ms** | **the outbox delay again**, this time payment-service's |
| 419 → 608 ms | order and inventory both consume `PaymentCompleted`; the order confirms |

The two long gaps are not latency in any service. They are the relay's poll interval, and the trace
turns the outbox pattern's cost from a claim into a measurement: **~250 ms of the 608 ms is waiting
for a poll.** That is the price paid for the guarantee that no order can be committed without its
event eventually being published.

### The trace only spans the saga because it is carried across the outbox

Left alone, it does not. A trace context lives on a thread; the relay publishes on a different thread
in a different transaction, so it starts a fresh trace and the consumer's spans attach to a scheduled
tick. The result is not "no tracing" — it is **four disconnected traces that each look healthy**.

Fixed by storing the W3C `traceparent` on the outbox row when the event is appended
(`V3__outbox_traceparent.sql`) and restoring it in the relay before the send. That is what produces
the `outbox publish` spans above, whose parent is the HTTP request four hops back.

Verified rather than assumed — before the fix, the same order produced:

| | Traces | Widest trace |
|---|---|---|
| before | 4 separate | 1 service |
| after | **1** | **4 services, 20 spans** |

### Metrics

All nine Java processes scraped, every panel query returning data:

| Query | Series |
|---|---:|
| `rate(http_server_requests_seconds_count[1m])` by service | 9 |
| `histogram_quantile(0.99, …http_server_requests_seconds_bucket…)` | 7 |
| `cache_gets_total{result="hit"}` | 1 |
| `resilience4j_circuitbreaker_state{state="open"}` | 2 |
| `kafka_consumer_fetch_manager_records_lag` | 5 |
| `jvm_memory_used_bytes{area="heap"}` | 9 |
| `hikaricp_connections_active` | 5 |
| **`outbox_pending`** | **4** (all 0 — correct steady state) |

`outbox_pending` was added because the panel that needed it did not exist and the first version of
the dashboard used a proxy metric with a note admitting it. It is the one number worth alerting on:
if the relay stops, HTTP stays fast, orders keep committing, every other panel stays green, and no
event ever reaches Kafka.

### Zipkin ran out of memory after three hours, and why it filled so fast

`Terminating due to java.lang.OutOfMemoryError: Java heap space`, from a container configured with
`MEM_MAX_SPANS: 100000` and a 512 MB limit. The arithmetic was never done: an in-memory span with
tags and endpoints is on the order of 1–3 KB, so 100k of them is 100–300 MB of live data before
Armeria's buffers and the JVM's own overhead. Now 50k spans in 1 GB, sitting at **298 MB** in steady
state.

The reason it filled at all is the more useful half. Sampling is 1.0, and on an idle platform the
busiest traced traffic is not orders — it is **Prometheus scraping nine `/actuator/prometheus`
endpoints every ten seconds** and nine Eureka clients heartbeating every thirty. The observability
stack is its own heaviest user, which is the same effect that made the order trace impossible to find
by "most recent".

It failed the right way, at least: the platform did not notice. Every service kept running and kept
reporting spans into a socket that had gone away.

### Two smaller things, recorded because both cost time

- **Prometheus could not bind :9090** — that is the api-gateway's management port, which exists
  precisely so no route through the edge can reach actuator. Moved to host 9091.
- **The observability stack hid the thing it was installed to observe.** The first version of
  `show_trace.py` asked Zipkin for recent `order-service` traces and got 40 Eureka heartbeats and
  Prometheus scrapes of `/actuator/prometheus` — monitoring traffic pushing the order path off the
  end of the page. Querying by the span name of the write itself is exact. An earlier version was
  worse: it searched for the order key and matched the *polling* `GET /api/orders/key/{orderKey}`,
  printing a tidy 7-span trace of a read while claiming to show the write.

Measured 2026-08-19.

---

## Step 11 — Storefront  `REAL`

A React 19 SPA on `:3000`, talking to the gateway on `:8080` with **no dev proxy**, so every request
is genuinely cross-origin.

### The Definition of Done, executed in a browser

The DoD is *"a real browser completes a purchase and gets an agent answer"*. Both halves, in one
session, driven through the rendered page rather than through its API:

| | Observed |
|---|---|
| Typo search | typed `beaty` → **2,444 hits**, all `health_beauty`, **26 ms** round trip |
| Facets | 12 category chips with counts, from the same response as the hits |
| Product page | catalogue read + inventory read, **20 available (0 reserved of 20 on hand)** |
| Cart | 2 × R$ 49,99 = R$ 99,98, held in `localStorage` across a sign-in |
| Sign-in | `POST /api/auth/login` → 200, `GET /api/users/me` → 200 |
| **Purchase** | `POST /api/orders` → **201**, order `3f0b8ebd…` |
| **Saga, watched live** | `PENDING` → `CONFIRMED` in **1,289 ms**, over **4 polls** |
| Freight | R$ 15,00 added server-side; total R$ 114,98, not the R$ 99,98 the cart showed |
| Stock afterwards | 20 → **18 on hand**, 0 reserved |
| **Agent answer** | "Which five states generated the most revenue?" → correct SQL, **5 rows in 17.3 s** |

The agent's answer, in the browser, from the star schema:

| state | total_revenue |
|---|---:|
| SP | 5,202,955.05 |
| RJ | 1,824,092.67 |
| MG | 1,585,308.03 |
| RS | 750,304.02 |
| PR | 683,083.76 |

### SSE streaming, observed for the first time

`docs/reflection.md` has said since Step 9 that the streaming had never been seen in a browser — only
by `eval/run_eval.py`, and a Python client reading frames cannot tell you *when* they arrived. The
storefront timestamps each frame on arrival. Eighteen progress events over one question:

```
 1130 ms  Extract Keywords · running        12109 ms  Merge Retrieved Info · running
 1140 ms  Extract Keywords · success        12159 ms  Merge Retrieved Info · success
 1209 ms  Recall Columns · running          12176 ms  Filter Metrics · running
 1211 ms  Recall Metrics · running          12176 ms  Filter Tables · running
 1350 ms  Recall Values · running           14070 ms  Filter Tables · success
 4549 ms  Recall Values · success           14375 ms  Filter Metrics · success
 7783 ms  Recall Metrics · success          14396 ms  Generate SQL · running
12088 ms  Recall Columns · success          16546 ms  Generate SQL · success
                                            16571 ms  Execute SQL · running
                                            17293 ms  Execute SQL · success
```

Spread across seventeen seconds, not delivered at the end. That is the evidence that the gateway's
`RemoveResponseHeader=Content-Length` on the analytics route is doing its job; with buffering, all
eighteen would carry near-identical timestamps. It is also the first direct observation that the
three recall branches (`Recall Columns` / `Recall Metrics` / `Recall Values`) run **concurrently** —
all three start within 141 ms of each other and finish 8 s apart.

### CORS, exercised rather than configured

With no dev proxy, the browser sent a real preflight before every authenticated call. All passed:

```
OPTIONS /api/auth/login       -> 200
OPTIONS /api/users/me         -> 200
OPTIONS /api/orders           -> 200
OPTIONS /api/orders/key/{k}   -> 200
OPTIONS /api/analytics/query  -> 200
```

### Build

| | |
|---|---|
| `npm run build` | 49 modules, **1.22 s** |
| Bundle | 252.5 kB JS (**80.5 kB gzipped**), 5.6 kB CSS |
| Dependencies | 5: react, react-dom, react-router-dom, vite, @vitejs/plugin-react |

### What building it found

Two bugs that four steps of `curl` had not, both now fixed and both written up in
`docs/reflection.md`:

1. **Every Redis cache hit in catalog-service was throwing** a `ClassCastException`, reported as a
   401. It needs two requests for the same resource to see; React StrictMode's double-invoked effect
   made two.
2. **Search reported 10,000 matches against a 32,951-document index.** Elasticsearch stops counting
   at 10,000 unless asked not to. Every query measured in Step 7 returned fewer than that; browsing
   with no query at all is what exposed it.

Measured 2026-08-19.

---

## Step 7 — Elasticsearch product search  `REAL`

The whole 32,951-product Olist catalogue, indexed through the real event path — no bulk loader, no
direct write to Elasticsearch. `POST /api/products/reindex` pages catalog-service's own table and
appends one outbox row per product; everything after that is the ordinary pipeline.

### Indexing the catalogue

| Measurement | Value |
|---|---:|
| Products indexed | **32,951** |
| Elapsed, `reindex` call → last document searchable | **263 s** |
| Sustained rate | ~125 docs/s |
| Documents lost or duplicated | **0** (index count == catalogue count) |

125/s is slow for Elasticsearch and deliberately so: the path is one outbox row per product, drained
at `batch-size: 500` every 500 ms, one Kafka message each, one `save()` each. Batching the
Elasticsearch writes would multiply that figure several times over. It is not batched because the
steady-state load this exists for is *one product changing*, and the reindex is a cold start that
runs twice a year.

### Query behaviour — measured, not asserted

All against the live index. `top` is the top-scoring hit's category; `facets` come from the same
round trip as the hits, not a second query.

| Query | Intent | Hits | Score | Top category |
|---|---|---:|---:|---|
| `health beauty` | two words, underscore in the data | **2,444** | 15.07 | `health_beauty` |
| `beaty` | one missing letter | **2,444** | 6.03 | `health_beauty` |
| `computr acessorie` | two typos in two words | **2,867** | 11.70 | `computers_accessories` |
| `beleza saude` | the Portuguese source category | **2,444** | 5.12 | `health_beauty` |
| `beleza saud` | Portuguese *and* a typo | **2,444** | 4.48 | `health_beauty` |

`beaty` and `health beauty` return **the same 2,444 documents** — the typo costs relevance score
(15.07 → 6.03), not recall. That is the intended shape: fuzziness widens what matches, and scoring,
not filtering, decides what a user sees first.

`computr acessorie` returning *more* hits than either exact term is also correct and worth reading
twice: two fuzzy terms OR'd together match a union of three categories, and the facets say so —

```
computers_accessories 1,639   fashion_bags_accessories 849   luggage_accessories 349
```

The facet counts are what makes that recoverable by the user rather than confusing.

### Filters and facets

| Request | Result |
|---|---|
| `q=beauty&category=health_beauty&maxPrice=50` | 765 hits, all `health_beauty`, all ≤ R$50 |
| `GET /api/search/suggest?q=fashio` | `fashion_bags_accessories`, `fashion_shoes`, `fashion_male_clothing`, `fashion_underwear_beach`, `fashio_female_clothing` |

The last suggestion is not a typo in this document. `fashio_female_clothing` is spelled that way in
the Olist dataset, and the suggester returns what is in the data rather than what ought to be.

### Freshness — catalogue write to searchable

`python scripts/measure_search_lag.py --samples 6`. Timed from the `PUT` returning to the changed
product being returned by a **search**, not by a document fetch — Elasticsearch makes a write
visible to `GET /_doc/{id}` immediately but not to a query until the index refreshes, so timing the
fetch would report a lag no user experiences.

| | ms |
|---|---:|
| min | 621 |
| **median** | **942** |
| max | 1,031 |

Two floors are stacked in that: the outbox relay's 500 ms poll and Elasticsearch's 1 s refresh
interval. Neither is a defect — both are the price of not blocking a catalogue write on a downstream
index. The DoD asked for "within seconds"; the measured answer is under one.

### The mapping actually in effect

Verified against the live index rather than the annotations, because the two disagreed once already
(see `IndexInitializer` and reflection.md):

```
GET /products/_analyze  {"analyzer":"product_analyzer","text":"health_beauty"}
  -> ["health", "beauty"]
GET /products/_mapping
  -> category: text (product_analyzer) + category.keyword: keyword
  -> price:    scaled_float, scaling_factor 100
```

Measured 2026-08-19.

---

## Step 10 — Evaluation  `REAL`

`python eval/run_eval.py` — 33 questions, execution accuracy (run the gold SQL, run the generated
SQL, compare result sets), end to end through the gateway. Full write-up in
[`evaluation.md`](evaluation.md).

| Run | Accuracy | |
|---|---:|---|
| baseline | **78.8%** | 26/33 |
| after fixing two systematic causes | **97.0%** | 32/33 |

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

~12.7 s per question, ~7 min per full run.

### Root causes of the 7 baseline failures

| Cause | Failures | Whose fault |
|---|--:|---|
| "Percentage" produced a ratio (0.0791 not 7.91) | 2 | **My metric description**, which literally said `SUM(is_late) / COUNT(is_late)` |
| "State" resolved to `dim_geography` (28 rows, incl. Unknown) not `dim_customer` (27) | 2 | **My metadata** — nothing said which dimension owns "state" |
| Gold SQL selected a column the question did not ask for | 2 | **My benchmark** |
| Agent added an unrequested `WHERE order_status = 'DELIVERED'` | 1 | Ambiguous question — **left failing on purpose** |

**Five of seven were mine, not the model's.**

### Two silent reasons a metadata fix did not take effect

After the metadata fix, accuracy reached only 87.9% and the percentage failures persisted, generating
the *old* formula verbatim. Two causes, both reporting success:

| Cause | Evidence | Fix |
|---|---|---|
| `conf/` is `COPY`ed into the image, so host edits were invisible to the builder | `SELECT description FROM meta.metric_info` still returned the old text after a "successful" rebuild | Mount `./agent/backend/conf` read-only |
| The agent's Redis retrieval cache (db 1) was warm — **717 keys** | Rebuilt index, unchanged behaviour | `scripts/rebuild_agent_knowledge.py` flushes db 1 (never db 0) |

### Self-correction loop — observed

Fired on **0 of 33** evaluation questions; every one produced valid SQL first time. Provoked with a
question the schema cannot answer (*"What is the average age of our customers?"*):

| Attempt | SQL | Outcome |
|--:|---|---|
| 1 | *(empty)* | rejected |
| 2 | `SELECT AVG(age) FROM customers` | hallucinated table + column, validation failed |
| 3 | `SELECT NULL AS average_age` | accepted — answers `null` rather than inventing a number |

Two correction cycles. The guardrail is `EXPLAIN` against the real schema, so a hallucinated column
cannot reach the user as a confident wrong answer.

`REAL` Olist data — measured 2026-08-19.

---

## Step 9 — NL→SQL agent integration  `REAL`

### Metadata validation

`python scripts/validate_agent_metadata.py` - **31/31 checks passed**, 2026-08-10.

| Group | Result |
|---|---|
| Tables/columns described that exist in `dw` | 5 tables, 42 columns, all present |
| Warehouse columns not described to the agent | none (housekeeping columns excluded deliberately) |
| Metrics referencing real columns | 8 metrics, 39 aliases |
| `sync: true` cardinality | 6-71 distinct values each - workable to index |

### The read-only boundary (D12), tested rather than asserted

| Statement as `warehouse_ro` | Result |
|---|---|
| `SELECT COUNT(*) FROM fact_order_items` | 112,652 |
| `DELETE FROM fact_order_items` | **refused, MySQL 1142 command denied** |
| `UPDATE dim_product SET ...` | **refused, 1142** |
| `CREATE TABLE probe_should_fail` | **refused, 1142** |
| `SELECT ... FROM meta.table_info` | **refused** |

### The seam, end to end

| Request | Result |
|---|---|
| `GET :8080/api/analytics/health` with **no token** | **401 at the gateway**, before the agent is touched |
| `GET :8080/api/analytics/health` with a valid platform JWT | `{"status":"ok"}` |
| `GET :8000/health` (direct, for comparison) | `{"status":"ok"}` |

The agent connects at startup to MySQL (`meta` and `dw`), Elasticsearch, Redis, Qdrant and its own
PostgreSQL log database. Image size 351 MB.

### Live, end to end - three questions, verified against hand-written SQL

Knowledge base build: **~15 s** for 5 tables / 42 columns / 8 metrics into MySQL, column and metric
vectors into Qdrant, and **211 distinct values** into Elasticsearch.

| Question | Generated SQL | Answer | Independent check |
|---|---|---|---|
| Total revenue by category in 2018, top 5 | 3-table join, `WHERE dd.year = 2018`, GROUP BY, ORDER BY, LIMIT | `health_beauty` 772,238.15 · `watches_gifts` 708,850.94 · `bed_bath_table` 538,069.26 · `sports_leisure` 532,566.49 · `computers_accessories` 505,476.31 | **Exact match on all 5 rows** vs hand-written SQL |
| Which region generated the most revenue | Join to `dim_geography`, GROUP BY region, ORDER BY SUM DESC, LIMIT 1 | **Southeast** | Matches (R$ 8,887,393.06, ahead of South at 1,953,941.12) |
| How many distinct customers | `COUNT(DISTINCT customer_unique_key)` | **96,096** | **Exactly the published Olist figure** for `customer_unique_id` |

Earlier, on synthetic data, the same harness also got *"what percentage of delivered order lines
arrived later than the estimated date"* right — **7.96%** against 8.0% measured independently — by
writing `is_late IS NOT NULL`, i.e. treating NULL as *not yet delivered* rather than *on time*.

The customer question is the trap the metadata exists to prevent: `customer_key` (one per ORDER)
would have returned **99,441** instead of 96,096 — inflating every per-customer metric by 3.5%. The
model picked the right column because the description says which is which.

Embedding service: **ready in 24 s**, 1024-dimension vectors.

### Still not measured

- The **self-correction loop** never fired - all three questions produced valid SQL first time, which
  is the good outcome and leaves that path unobserved.
- **SSE in a browser.** Frames arrive through the gateway in order (progress -> sql -> result), but
  no browser has consumed them because there is no frontend.
- **Accuracy on a public benchmark** - that is Step 10, still not built.

---

## Step 8 - CDC to the star-schema warehouse

### The pipeline, end to end

`PostgreSQL WAL -> Debezium (logical replication) -> Kafka -> cdc_sink.py -> MySQL 8 star schema`

Cross-engine on purpose (D1): a Postgres-to-Postgres pipeline can be faked with `postgres_fdw` or a
logical replication subscription and needs no Kafka at all. Postgres to MySQL cannot be - the change
stream has to be decoded, transformed and applied.

### Initial snapshot  `REAL`

| | Records |
|---|---:|
| Consumed by the sink | **344,491 in 460 s (749 records/s)** |

Landed in the warehouse:

| Table | Rows |
|---|---:|
| `fact_order_items` | **112,652** (112,650 Olist + 2 placed through the API during testing) |
| `dim_customer` | 99,443 |
| `dim_product` | **32,951** — exactly the source count |
| `dim_date` | 4,748 (pre-generated, 2015–2027) |
| `dim_geography` | 28 |

9,520 product stubs and 38,569 customer stubs were created during the snapshot — facts arriving
before their dimension event — and **all were resolved**: `dim_product` ends at exactly 32,951, and
`unresolved_product_stubs` and `orphan_facts` are both **0**.

### Warehouse totals

| | Value |
|---|---:|
| Fact rows | 112,652 |
| Distinct orders | 98,668 |
| Total revenue | **R$ 13,591,687.34** |
| Late deliveries | 8,715 lines, **7.91%** of delivered lines |
| Facts on the Unknown geography member | **2** (the two API-placed test orders) |
| Orphan facts / unresolved stubs | **0 / 0** |

Revenue by region: Southeast R$ 8,887,393 (77,413 lines) · South 1,953,941 · Northeast 1,545,494 ·
Central-West 870,462 · North 334,354. The Southeast concentration is a real property of Brazilian
e-commerce, not an artefact.

### A bug the real data exposed: 27% of facts on "Unknown"

The first real run left **30,635 facts** pointing at the Unknown geography member, and
`dim_customer.state` was populated for 99,441 of 99,443 rows. So the dimension was complete and the
facts still said "Unknown".

Cause: `geography_id` is **derived** from `dim_customer.state`, resolved once when the fact is
written. During the snapshot the customer was often still a stub with `state IS NULL`, so the lookup
fell to 999 — and filling the customer in afterwards does nothing to facts already written.

The stub pattern solves a missing **row**; it does not solve a value **derived** from that row's
attributes. `reconcile_geography()` now re-resolves them when the stream goes quiet and at the end of
a drain:

| | Facts on Unknown |
|---|---:|
| Before reconciliation | 30,635 |
| After | **2** |

30,633 rows corrected — about a quarter of the warehouse. Every "revenue by region" answer would
have been wrong, silently, with no orphan row and no constraint violation to notice.

### Freshness: OLTP commit to warehouse visibility

`python scripts/measure_cdc_lag.py --samples 6` - a real `UPDATE` on `catalogdb.products`, timed from
the moment the Postgres transaction commits until the new value is readable in MySQL.

| | Lag |
|---|---:|
| min | **391 ms** |
| median | **495 ms** |
| max | 1,005 ms |

*(`SYNTHETIC` run: min 483 ms, median 510 ms, max 739 ms — the same, because lag is a property of the
poll interval rather than of the data.)*

The sink's Kafka poll timeout dominates it - the same trade the outbox relay makes, and the same
lesson: a poll interval is a floor on freshness. Debezium's own WAL-to-Kafka hop is a small fraction
of this.

### The Definition of Done query

```sql
SELECT p.category, d.month_key, ROUND(SUM(f.line_revenue), 2) AS revenue
  FROM fact_order_items f
  JOIN dim_product p ON p.product_id = f.product_id
  JOIN dim_date    d ON d.date_id    = f.date_id
 WHERE f.order_status = 'DELIVERED'
 GROUP BY p.category, d.month_key
 ORDER BY revenue DESC;
```

Two joins, one GROUP BY, no subqueries, answered in one statement. Against the OLTP databases the same
question is a join across three separate *services* - which is to say it cannot be written at all.

`REAL` Olist data - measured 2026-08-10.

---

## Step 6 - Redis caching  `REAL`  — **re-measured 2026-08-19, and the first numbers were wrong**

> **The numbers previously in this section described a cache in which every hit failed.**
>
> `GenericJackson2JsonRedisSerializer(mapper)` — the constructor that takes your own `ObjectMapper` —
> does not enable the default typing that writes the `@class` property. Nothing recorded what type
> had been stored, so every read came back a `LinkedHashMap` and the cast to `ProductResponse` threw.
> A cache *miss* worked. A cache *hit* returned an error, for the whole 10-minute TTL.
>
> It survived measurement because both instruments were pointed slightly to the left of the thing
> that was broken. `scripts/cache_benchmark.py` caught `HTTPError` and returned the elapsed time
> anyway — and an error response is *fast*, so the latency improvement was real and was the latency
> of failing. The hit rate came from Redis's own counters, which count a lookup, not what happened to
> the value afterwards; Redis genuinely did return the bytes. And the 500 was rendered as a **401** on
> a public endpoint, because `/error` was not permitted in the filter chain, so even reading the
> response by hand pointed at authentication rather than at the cache.
>
> It was found by opening the storefront in a browser. React StrictMode double-invokes an effect in
> development, so the product page requested the same product twice — the first 200, the second not.
> Four steps of curl never did, because curl asked once.
>
> Both instruments are fixed: the benchmark now aborts unless every response is a 200, and every
> service permits `/error`. The numbers below are from the fixed system.

`python scripts/cache_benchmark.py` — 500 requests over 50 distinct products, concurrency 8, straight
at catalog-service (the gateway adds ~4 ms of fixed cost to hit and miss alike, which shrinks the
measured difference without changing it — established by running it both ways first).

**Run 1**

| | p50 | p95 | p99 | mean | Redis hits/misses | hit rate | Postgres transactions |
|---|---:|---:|---:|---:|---:|---:|---:|
| **no cache** (flushed before every batch) | 11.56 ms | 15.53 ms | 22.97 ms | 11.93 ms | – | – | **578** |
| cold → warm (filling as it goes) | 8.88 ms | 13.62 ms | 17.59 ms | 9.50 ms | 450/70 | 86.5% | 17 |
| **warm** | **7.84 ms** | 17.30 ms | 28.97 ms | 9.36 ms | **500/0** | **100.0%** | **7** |

**Run 2**, immediately after, same command:

| | p50 | p95 | p99 | mean | Redis hits/misses | hit rate | Postgres transactions |
|---|---:|---:|---:|---:|---:|---:|---:|
| **no cache** | 10.45 ms | 15.82 ms | 27.40 ms | 11.01 ms | – | – | **552** |
| cold → warm | 6.63 ms | 16.48 ms | 27.81 ms | 8.11 ms | 450/70 | 86.5% | 20 |
| **warm** | **5.81 ms** | 8.02 ms | **11.33 ms** | 6.02 ms | **500/0** | **100.0%** | **6** |

Two runs are shown because the first one's p99 got *worse* with the cache (22.97 → 28.97 ms) and the
second one's improved sharply (27.40 → 11.33 ms). On a laptop running five JVMs, six databases, Kafka
and Elasticsearch, a single p99 is a measurement of what else was scheduled. The p50 and the
transaction count are stable across both; the p99 is not, and one run of it is not evidence.

**The headline is the last column.**

| | Result |
|---|---|
| Cache hit rate, warm | **100.0%** — 500 hits, 0 misses, from Redis's own `INFO stats` counters, and now with all 500 responses verified to be 200 |
| Database transactions | **578 → 7** and **552 → 6**: **98.8%** removed |
| p50 latency | 11.56 → 7.84 ms (**−32%**) and 10.45 → 5.81 ms (**−44%**) |
| p99 latency | inconsistent between runs — see above |

Why the latency moves less than the transaction count, said plainly rather than hidden: on this
machine a primary-key lookup against a warm Postgres holding 33K rows already costs about a
millisecond, so **the cache is not removing a bottleneck, because there is not one here.** What it
removes is database *work* — the column that runs out first under real traffic. Quoting the p50 as
"the cache made it 32% faster" would be true and would be the wrong claim; the defensible one is
"98.8% of the reads never reached the database".

The hit rate is taken from Redis rather than from Spring's cache statistics on purpose: an
application-reported hit rate is the application marking its own homework. As the correction above
shows, that was necessary and not sufficient — Redis's counters were honest about Redis and had
nothing to say about what the application did with the bytes.

### The value actually round-trips

Added because the previous version of this section had no check of this kind, which is why it was
wrong for four steps:

```
FLUSHDB
GET /api/products/7   (miss) -> {"id":7,...,"price":963.00,...}
GET /api/products/7   (hit)  -> {"id":7,...,"price":963.00,...}   byte-identical
redis GET catalog::product:id::7 -> the same JSON, with no @class field
```

### Invalidation, demonstrated

| Step | Observed |
|---|---|
| Read product 1 | `catalog::product:id::1` appears in Redis |
| `PUT` a new price (127.16 → 999.99) | The key is **gone** — `[]` |
| Read again | `price = 999.99`, and the key is repopulated |

Both caches are evicted on a write, not just the one the write happened to use. One product lives
under two keys (database id, Olist key) because two callers ask two different questions, and evicting
only one leaves the other serving a stale price for the whole TTL. That is the most common
cache-invalidation bug there is.

*(This table was measured before the serializer was fixed and is still valid: every read in it
follows an eviction, so every one was a miss. That is also exactly why it passed.)*

### Rate limiting

25 rapid `POST /api/auth/login` requests from one IP through the gateway: **23 x 200, 2 x 429**.

The bucket is `replenishRate 5/s, burstCapacity 10`, and the loop is slow enough that tokens
replenish between requests - which is why 23 got through rather than 10. A tighter loop trips it
harder; the point being demonstrated is that the limit exists, is shared (the token bucket is a Lua
script inside Redis, not a per-instance counter), and returns 429 rather than failing open.

`SYNTHETIC` data, real services - measured 2026-08-10. Re-verified on `REAL` data: smoke test 18/18, saga CONFIRMED in 1,498 ms.

---

## Step 5 — Outbox saga

### The saga, timed end to end

`python scripts/saga_demo.py` places an order through the gateway and polls until it settles.

**Happy path** (payment succeeds):

| Moment | t+ | What happened |
|---|---:|---|
| `POST /api/orders` -> 201 | **39-321 ms** | Order row + outbox row committed. Status `PENDING`. Nothing reserved, nothing charged. |
| `PENDING -> AWAITING_PAYMENT` | 1,066 ms | Relay published `OrderPlaced`; inventory reserved and emitted `InventoryReserved`. |
| `AWAITING_PAYMENT -> CONFIRMED` | 1,621 ms | Payment captured; `PaymentCompleted` consumed by order and by inventory. |
| Stock settled | ~1,700 ms | `on_hand 16 -> 14`, reservation cleared. |

**Compensation path** (`PAYMENT_FAILURE_RATE=1.0`):

| Moment | t+ | What happened |
|---|---:|---|
| `POST /api/orders` -> 201 | **39 ms** | Same as above. |
| `PENDING -> AWAITING_PAYMENT` | 1,074 ms | Stock reserved. |
| `AWAITING_PAYMENT -> CANCELED` | 1,136 ms | Payment declined -> `PaymentFailed` -> order compensates. |
| Stock released | shortly after the status flip | `reserved 14 -> 12`; `on_hand` **never moved**. Nothing left the warehouse and nothing was charged. |

The payment row is `DECLINED`, not absent - the attempt is a fact worth keeping.

The end-to-end saga floor is the **500 ms outbox poll interval**, paid up to twice (order ->
inventory, inventory -> payment). That is the price of the polling relay over Debezium's WAL tailing
(D9), and it is the number to quote when explaining the trade rather than hand-waving about "slightly
slower".

### The request got faster, not slower

| Order placement | Latency |
|---|---:|
| Step 3, synchronous (price + write + **reserve over HTTP**, all in one request) | 60-77 ms |
| Step 5, outbox (price + write + **append event**, one transaction) | **39 ms** |

Removing a network call from inside a database transaction is the whole difference. The customer's
request no longer waits for inventory at all - and the honest counterpart is that it now returns
`PENDING` rather than `AWAITING_PAYMENT`, i.e. the response reports what has actually finished.

### End-to-end smoke test, re-run after the flow became asynchronous

`python scripts/smoke_test.py` - **18/18 checks in 1.6 s**, against the event-driven saga.

| What was checked | Result |
|---|---|
| `POST /api/orders` returns before anything is reserved | `PENDING`, 201 |
| The saga drives it to completion | `CONFIRMED` |
| Stock left the warehouse | `on_hand 20 -> 18`, `reserved 0 -> 0` |
| The saga captured exactly one payment | one `CAPTURED` row |
| Replaying the charge | **same row id (105924)**, still one payment row |

### A measured consequence of the dead-letter topic

After the double-encoding defect (since fixed), the cancellation events that could not be
deserialised were dead-lettered. The correspondence is exact:

| | Count |
|---|---:|
| Reservations still `RESERVED` against cancelled orders | **12** |
| Messages in `ecommerce.order.cancelled.inventory-service.DLT` | **12** |

Nothing re-reads a DLT, so that stock is held permanently. The saga and the outbox behaved as
designed; what is missing is a replay tool and a reservation sweeper - see `reflection.md`.

### Atomicity, proven by breaking it

`./mvnw -pl order-service test` - **8 tests, 0 failures, 0 errors** against a real PostgreSQL 17
(Testcontainers) with a mocked broker, so the fault lands on an exact instruction.

| Test | What it proves |
|---|---|
| `orderAndEventCommitTogether` | The order row and the `OrderPlaced` row exist together, and the event is unpublished - a durable intermediate state, not a hope. |
| `rollbackLosesBoth` | A crash **after** the append and **before** the commit leaves *neither*. The ruled-out pairing is the other one: an order with no event, which is unrecoverable because nothing knows it needs recovering. |
| `publishFailureLeavesTheEventRecoverable` | Broker unreachable -> 0 published, row still `published_at IS NULL`, `attempts = 1`, `last_error` recorded, order still `PENDING`. **No divergence.** |
| `theEventIsPublishedOnRecovery` | The same event publishes on the next pass once the broker returns. No replay tool, no operator step - the unpublished row *is* the queue. |
| `publishedEventsAreNotResent` | A second pass finds nothing; the partial index drops published rows from the working set. |
| `appendOutsideATransactionIsRefused` | `Propagation.MANDATORY` throws rather than silently opening a transaction for the event alone. |
| `unserialisablePayloadFailsAtWriteTime` | A bad payload fails the business transaction, not the relay an hour later. |
| `eventsAreKeyedByAggregate` | Events for one order carry the same key, which keeps them on one partition and therefore in order. |

`SYNTHETIC` data, real services - measured 2026-08-10. Re-verified on `REAL` data: smoke test 18/18, saga CONFIRMED in 1,498 ms.

---

## Step 3–4 — Domain services, Spring Cloud plumbing

### Full data load (all five targets)  `SYNTHETIC`

**Superseded by the `REAL` load in the Step 2 section above.** Kept because the comparison is a
result in itself: the real run moved ~481,000 rows in 24.9 s against ~490,000 in 30.3 s here, so load
time tracks row count and not content.

One command, `python scripts/load_olist.py --source data/generated`, against empty databases.

| Target | Table | Rows | Time |
|---|---|---:|---:|
| catalog-service | `products` | 33,100 (32,171 active, 71 categories) | 1.4 s |
| user-service | `users` | 100,000 (96,772 distinct people, 27 states) | 7.6 s |
| inventory-service | `stock_items` | 33,100 (662,000 units on hand) | 1.6 s |
| order-service | `orders` | 100,000 | 14.3 s |
| order-service | `order_items` | 117,944 (0 orphans) | (included above) |
| payment-service | `payments` | 105,916 (R$ 11,686,911.29) | 5.1 s |
| **Total** | | **~490,000 rows** | **30.3 s** |

Order status distribution: 97,078 DELIVERED · 1,055 SHIPPED · 619 CANCELED · 599 UNAVAILABLE ·
334 INVOICED · 305 PROCESSING · 9 CREATED · 1 APPROVED.
Payment methods: 73,786 CREDIT_CARD · 19,030 BOLETO · 11,610 VOUCHER · 1,490 DEBIT_CARD.
Order date range: 2016-09-04 … 2018-09-24.

`SYNTHETIC` — measured 2026-08-10. The distributions above are the generator's, drawn from the real
dataset's published proportions; the counts are what actually landed in Postgres.

### End-to-end purchase through the gateway

`python scripts/smoke_test.py` — one real purchase across five services, asserting on database state
rather than only on responses.

**19/19 checks passed in 2.5 s**, including:

| What was checked | Result |
|---|---|
| `POST /api/orders` with no token | 401 **at the gateway**, before any service is touched |
| Server-side pricing | Client sent only a product key and a quantity; line priced at 127.16, matching the catalogue |
| Reservation semantics | `reserved 0 → 2`, `on_hand 20 → 20` — stock is held, not removed |
| Cross-user access | Another user reading the order → **403** |
| Payment idempotency | Identical request repeated → **same row id (105917), one payment row total** |
| Confirmation | `on_hand 20 → 18`, `reserved 2 → 0` — the reservation became a real removal |

### Circuit breaker: measured, not asserted

catalog-service was stopped, then 15 orders were placed through the gateway.

| Calls | HTTP | Latency | What is happening |
|---|---|---:|---|
| 1–9 | 503 | **6.42 – 6.88 s** | Breaker CLOSED. Each call is 3 Feign attempts × (2 s connect timeout + backoff). |
| 10–15 | 503 | **0.012 – 0.015 s** | Breaker **OPEN** after `minimum-number-of-calls: 10`. Refusal is immediate. |

**~470× faster refusal once the breaker opens** (6.55 s → 0.014 s median), and the customer's
connection is released instead of being held for six seconds per attempt. That is the entire point of
the pattern, and it is the number to quote: not "we added Resilience4j" but "one dead dependency
stopped costing 6.5 s per request".

Recovery, after restarting catalog-service (`wait-duration-in-open-state: 10s`,
`automatic-transition-from-open-to-half-open-enabled: true`):

| Calls | HTTP | Latency |
|---|---|---:|
| 1 | 201 | 0.356 s (half-open trial + JIT warm-up) |
| 2–12 | 201 | **0.060 – 0.077 s** |

No manual intervention: the breaker closed itself. Note that the failure response was a **503 with
`Retry-After`, never a 500 and never a zero-priced order** — the fallback in `CatalogGateway` throws
rather than inventing a price.

`SYNTHETIC` data, real services — measured 2026-08-10.

---

## Step 1 — Walking skeleton

| Measurement | Value | Date |
|---|---|---|
| `./mvnw -pl catalog-service test` | **13 tests, 0 failures, 0 errors** | 2026-08-10, **and broken from Step 7 to Step 12 — see below** |
| Test infrastructure | Real PostgreSQL 17-alpine via Testcontainers, same image tag as docker-compose | 2026-08-10 |
| Gateway → catalog round trip, verified by hand | `POST /api/products` 201, `GET /api/products/key/{k}` 200 anonymous, `POST` without a token 401 at the edge | 2026-08-10 |

---

### These tests were broken for five steps and the row above did not say so

Step 7 added `@Value("${app.kafka.topics.product-updated}")` to `ProductServiceImpl` and put the
value in `config-repo/catalog-service.yml`. The test profile deliberately never reads the config
server — `spring.cloud.config.enabled: false`, which is the entire point of `application-test.yml`
— so the placeholder was unresolvable and **all 13 tests failed to load their context** from Step 7
until Step 12.

Nothing said so, because every build from Step 7 onward was `./mvnw -DskipTests package`. The row
above kept its 2026-08-10 date and its "0 failures" and was quietly describing a build five steps
old. **GitHub Actions is what found it**, on the second run, which is a fair argument for CI existing
at all.

Fixed by declaring the property in the test profile rather than defaulting it in the `@Value`: a
default would let the real service start with a topic name nobody chose, which is the failure the
property exists to prevent.

Whole reactor, after the fix: **21 tests, 0 failures, 0 errors** (13 catalog + 8 outbox atomicity),
`BUILD SUCCESS`.

---

*Sections for Steps 3–13 are appended as each step is measured. A step with no row here is a step
whose numbers were not taken.*
