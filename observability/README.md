# Observability

```bash
docker compose --profile observability up -d
```

| | | |
|---|---|---|
| Grafana | http://localhost:3001 | anonymous admin, dashboard pre-provisioned |
| Prometheus | http://localhost:9091 | 9090 is taken — that is the gateway's management port |
| Zipkin | http://localhost:9411 | |

Then produce something to look at:

```bash
python scripts/show_trace.py
```

It places one real order, waits for the saga, and prints the trace as a waterfall.

## What is here

- `prometheus.yml` — a static target list, one job, nine services. No Eureka-based discovery, and
  the reason is in the file: a service that deregisters would vanish from monitoring at exactly the
  moment you want its metrics.
- `grafana/provisioning/` — datasources and the dashboard provider, so nothing is clicked into
  existence. A dashboard that only exists in someone's browser is not in the repository.
- `grafana/dashboards/platform.json` — nine panels. Each carries the caveat that applies to it;
  the cache panel in particular says out loud that it counts lookups rather than usable values,
  because that exact panel was green throughout the four steps in which every cache hit threw.

## The two things worth knowing

**The trace survives the outbox.** The outbox pattern deliberately separates "the order is committed"
from "the event is published" — different thread, different transaction, up to 500 ms later. A trace
context lives on a thread, so by default the relay starts a fresh trace and the consumer's work is
attached to a scheduled tick rather than to the order that caused it. Not "no tracing": four
disconnected traces, each of which looks healthy alone. The W3C `traceparent` is therefore stored in
the outbox row (`V3__outbox_traceparent.sql`) and restored on the relay thread before the send.

The visible result is that the outbox delay becomes a measurable gap in the waterfall rather than an
argument in a document.

**`outbox_pending` is the number to alert on.** If the relay stops, nothing degrades: HTTP stays
fast, orders keep committing, every other panel stays green, and no event ever reaches Kafka.

## AWS

Same instrumentation, different back ends, and no application code changes:

| Local | AWS |
|---|---|
| Prometheus | Amazon Managed Service for Prometheus (AMP) |
| Grafana | Amazon Managed Grafana |
| Zipkin | AWS X-Ray, via the OTel Collector's X-Ray exporter |

The tracer would swap from `micrometer-tracing-bridge-brave` to `-bridge-otel`, which is two lines in
each `pom.xml`. Nothing else moves, because the wire format is already W3C `traceparent` rather than
Brave's B3 — see `docs/decisions.md` D18. **None of this is provisioned; it is a mapping, not a
deployment.**
