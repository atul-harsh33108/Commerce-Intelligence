#!/usr/bin/env bash
#
# Deploy the platform to a single host. Run it ON that host, from the repository root:
#
#   ./deploy/deploy.sh
#
# Idempotent: safe to re-run. It rebuilds what changed and leaves data volumes alone.
#
# WHAT IT DOES NOT DEPLOY: the NL->SQL agent. Not because it would not fit — a 16 GiB host has room —
# but because it needs a Gemini API key and 1.3 GB of embedding weights on the box, and putting a
# personal API credential on an internet-facing host is a decision the owner should make
# deliberately rather than inherit from a script. `--profile agent` adds it if that is wanted.

set -euo pipefail

cd "$(dirname "$0")/.."

# The address browsers will use. Discovered rather than configured, because a wrong value here fails
# in the least helpful way possible: the page loads, and every API call fails a CORS preflight with
# a browser console message that names neither this script nor the gateway.
PUBLIC_HOST="${PUBLIC_HOST:-$(curl -fsS --max-time 5 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || true)}"
if [[ -z "$PUBLIC_HOST" ]]; then
    # IMDSv2 requires a token; the v1 call above fails silently on hosts that enforce it.
    TOKEN=$(curl -fsS --max-time 5 -X PUT "http://169.254.169.254/latest/api/token" \
        -H "X-aws-ec2-metadata-token-ttl-seconds: 60" 2>/dev/null || true)
    if [[ -n "$TOKEN" ]]; then
        PUBLIC_HOST=$(curl -fsS --max-time 5 -H "X-aws-ec2-metadata-token: $TOKEN" \
            http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || true)
    fi
fi
if [[ -z "$PUBLIC_HOST" ]]; then
    echo "Could not determine the public address. Set it explicitly:"
    echo "    PUBLIC_HOST=1.2.3.4 ./deploy/deploy.sh"
    exit 1
fi
export PUBLIC_HOST
echo "==> public host: $PUBLIC_HOST"

COMPOSE=(docker compose -f docker-compose.yml -f deploy/docker-compose.ec2.yml)
PROFILES=(--profile apps --profile observability)

# ---------------------------------------------------------------------------------------------
echo "==> building the service jars"
# -DskipTests, and the tests are not being skipped so much as run elsewhere. They need Docker for
# Testcontainers and about four minutes; CI runs them on every push, which is the right place for a
# gate that answers "is this commit good" rather than "is this host good".
(cd ecommerce-platform && ./mvnw -B -q -DskipTests package)

echo "==> building images"
"${COMPOSE[@]}" "${PROFILES[@]}" build

# ---------------------------------------------------------------------------------------------
echo "==> starting infrastructure"
# Infrastructure first and separately, because the services' healthchecks depend on it and a
# simultaneous start means nine JVMs competing for CPU with Elasticsearch's own startup. On four
# vCPUs that turns a 40-second boot into several minutes of thrash — the exact failure mode that
# stops the other project on this host from ever becoming ready.
"${COMPOSE[@]}" up -d
echo "==> waiting for infrastructure to report healthy"
for _ in $(seq 1 60); do
    unhealthy=$("${COMPOSE[@]}" ps --format '{{.Name}} {{.Health}}' 2>/dev/null \
        | awk '$2 != "healthy" && $2 != "" {print $1}' | wc -l)
    [[ "$unhealthy" -eq 0 ]] && break
    sleep 5
done

echo "==> starting the platform"
"${COMPOSE[@]}" "${PROFILES[@]}" up -d

# ---------------------------------------------------------------------------------------------
echo "==> waiting for the gateway"
for _ in $(seq 1 90); do
    if curl -fsS --max-time 3 "http://localhost:30080/api/products?size=1" >/dev/null 2>&1; then
        break
    fi
    sleep 5
done

echo
echo "==> deployed"
"${COMPOSE[@]}" "${PROFILES[@]}" ps --format '  {{.Name}}\t{{.Status}}' 2>/dev/null | sort
echo
echo "  storefront   http://$PUBLIC_HOST:30081"
echo "  api          http://$PUBLIC_HOST:30080/api/products?size=1"
echo "  grafana      http://$PUBLIC_HOST:30300"
echo "  prometheus   http://$PUBLIC_HOST:30090"
echo
echo "  zipkin is deliberately not exposed - tunnel to it:"
echo "    ssh -L 9411:localhost:9411 ubuntu@$PUBLIC_HOST"
echo
echo "  the catalogue is empty until data is loaded - see docs/deployment.md"
