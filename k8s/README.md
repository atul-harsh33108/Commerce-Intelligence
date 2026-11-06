# Kubernetes manifests

**These have never been applied to a cluster.** They are schema-checked offline — `kubeconform
-strict` reports **44 resources across 11 files, 0 invalid, 0 errors** — which says the schema is
right and nothing at all about whether the platform would come up. Treat them as a
design document that happens to be executable, not as a deployment.

```bash
kubeconform -strict -summary k8s/base/
```

> **This line used to say `kubectl apply --dry-run=client`, and that was wrong.** `--dry-run=client`
> is not an offline operation: the client still fetches the OpenAPI schema from the API server to
> validate against, so on a machine with no cluster every file fails with
> `failed to download openapi: ... connection refused`. It passed locally only because a kubeconfig
> here points at Docker Desktop's Kubernetes — the check was quietly depending on the author's
> laptop. CI is what found it, on the first run. `kubeconform` validates against bundled JSON schemas
> with no cluster at all, and `-strict` additionally rejects unknown fields, which the kubectl dry
> run accepts.

That is a deliberate line rather than an unfinished task: the project's ground rule is that no cloud
resource is created, and standing up kind or minikube to run nine JVMs alongside the five databases,
Kafka, Redis and Elasticsearch that are already running is not a test of the manifests so much as a
test of this laptop.



## What is here

| | |
|---|---|
| `00-namespace-and-config.yaml` | namespace, config-repo ConfigMap, **NetworkPolicies**, the gateway Ingress |
| `<service>.yaml` × 9 | Deployment + Service (+ HPA where it makes sense) + PodDisruptionBudget |
| `secrets.example.yaml` | fake values; the real ones are created out of band |

## The decisions worth reading

**Three probes, not one.** `startupProbe` buys the JVM 150 s without making liveness lenient forever;
`livenessProbe` hits `/actuator/health/liveness` and deliberately ignores dependencies;
`readinessProbe` hits `/actuator/health/readiness` and deliberately consults them. Pointing all three
at `/actuator/health` is the classic mistake — that endpoint aggregates the datasource, so a database
outage marks every replica unhealthy and Kubernetes restarts all of them, repeatedly, for a problem
no restart can fix. The distinction is why `management.endpoint.health.probes.enabled: true` is set
platform-wide in `config-repo/application.yml`.

**Memory limits, no CPU limits.** A CPU limit throttles at the cgroup period, and a JVM throttled
mid-GC produces latency spikes indistinguishable from a slow dependency. Memory is not compressible,
so that limit stays — and `MaxRAMPercentage` in the image sizes the heap from the same number, which
is why nothing needs re-tuning when the limit changes.

**`maxUnavailable: 0` on the rolling update.** At two replicas the default of 25% rounds to 1, so
half the service is gone while the replacement spends thirty seconds starting.

**`terminationGracePeriodSeconds: 45`.** These services have real shutdown work — drain requests,
commit consumer offsets, leave the consumer group. Killed at the default 30 s a consumer leaves by
session timeout instead, stalling its partitions for every other consumer in the group.

**The NetworkPolicies are load-bearing.** Every service's `SecurityConfig` permits
`/actuator/prometheus` without a token and justifies it with "the boundary is the Service definition,
not this filter chain". This is that boundary. Without these two objects, that comment describes
something that does not exist.

**No HPA on config-server or discovery-server.** Scaling a service-discovery server on CPU is a way
to get a split registry under load. They are singletons here and would be a StatefulSet with peer
replication in a real cluster — which is a different design, not a bigger `replicas:`.

## AWS

| Local | AWS | Change required |
|---|---|---|
| kind/minikube | **EKS** | none to the manifests |
| Postgres containers | **RDS** | `DB_URL` in a ConfigMap |
| Kafka container | **MSK** | `KAFKA_BOOTSTRAP_SERVERS` + IAM auth on the client |
| Redis container | **ElastiCache** | `REDIS_HOST` |
| Elasticsearch container | **OpenSearch** | `ELASTICSEARCH_URIS` |
| Prometheus / Grafana | **AMP / Amazon Managed Grafana** | scrape config only |
| Zipkin | **X-Ray** | swap the tracing bridge — `docs/decisions.md` D18 |

Every row is an environment variable, because every address in `config-repo` is already
`${VAR:localhost-default}`. **None of it is provisioned.** That is the ground rule, and this table is
a mapping rather than a deployment.
