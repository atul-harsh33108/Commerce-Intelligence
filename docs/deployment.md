# Deploying to a single host

The platform runs on one machine with Docker. This describes deploying it to an AWS EC2 instance,
which is the only cloud resource this project uses — and it is an instance that already existed, not
one the repository creates. Everything else about AWS in these docs is a mapping, not a deployment
(`docs/decisions.md` D13, `k8s/README.md`).

```bash
git clone https://github.com/atul-harsh33108/Commerce-Intelligence
cd ecommerce-ai-platform
./deploy/deploy.sh
```

That is the whole thing. It discovers the host's public address from instance metadata, builds the
jars, builds the images, starts infrastructure first, waits for it, then starts the platform.

## What the host needs

| | |
|---|---|
| CPU | **4 vCPU minimum.** See below — this is the requirement people get wrong. |
| Memory | 8 GiB for the platform, 16 GiB with room to work |
| Disk | 20 GB free |
| Software | `docker.io`, `docker-compose-v2`, `openjdk-21-jdk-headless` — all in Ubuntu 24.04's own repositories, no third-party install script needed |

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2 openjdk-21-jdk-headless
sudo usermod -aG docker "$USER"   # log out and back in
```

### Why 4 vCPU is a floor and not a suggestion

Nine Spring Boot services starting at once is nine JVMs doing class loading, bean instantiation and
Flyway migrations simultaneously. On 2 vCPUs they do not merely start slowly — they fail to start,
because each one is too slow to answer its own health probe before the probe gives up, so the
supervisor kills and restarts it, which makes the contention worse.

This is not hypothetical. The machine used here previously ran a similar Spring Cloud project on
2 vCPUs under k3s, and it reached **0 of 20 pods ready after 17 minutes with 65+ restarts and a load
average of 21**. Not memory: `MemoryPressure=False`, zero OOM kills. Purely CPU.

`deploy.sh` mitigates what it can by starting infrastructure first and waiting for health before
starting any JVM. That helps; it does not substitute for cores.

## Firewall

The deployment binds exactly four ports:

| Port | Service |
|---|---|
| 30080 | API gateway |
| 30081 | Storefront |
| 30090 | Prometheus |
| 30300 | Grafana |

Everything else — five Postgres instances, MySQL, Kafka, Redis, Elasticsearch, Zipkin, the config
and discovery servers, and the five internal services — is reachable only on the Docker network.

Open those four to **your address, not to the world**. `0.0.0.0/0` on 30080 publishes an API whose
development credentials are in a public git repository.

```bash
MY_IP=$(curl -s https://checkip.amazonaws.com)
for P in 22 30080 30081 30090 30300; do
  aws ec2 authorize-security-group-ingress --group-id sg-xxxx \
    --ip-permissions "IpProtocol=tcp,FromPort=$P,ToPort=$P,IpRanges=[{CidrIp=$MY_IP/32}]"
done
```

**Zipkin is deliberately not exposed.** It has no authentication of any kind and its traces contain
order keys and user ids. Reach it through a tunnel:

```bash
ssh -L 9411:localhost:9411 ubuntu@<host>     # then http://localhost:9411
```

### A note on IP allowlists and mobile networks

A `/32` rule assumes you have one stable address. On a phone hotspot or a carrier's home internet you
do not: carrier-grade NAT gives different connections different source addresses out of a shared
pool, so a rule added for the address a "what is my IP" service reports will not match the address
your SSH connection actually arrives from. The symptom is a connection that times out while every
piece of AWS configuration looks correct.

If that happens, the fix is not to keep adding addresses. Either connect from a network with a real
public IP, or use an **EC2 Instance Connect Endpoint**, which tunnels over the AWS API and does not
care what your address is.

## Data

A fresh deployment has an empty catalogue. Two options.

**Real Olist data** (what every number in `docs/measurements.md` was measured against). The CSVs are
not in the repository — 121 MB, and `docs/data-setup.md` explains where to get them. Copy them up:

```bash
tar -czf - data/olist | ssh ubuntu@<host> 'mkdir -p ~/ecommerce-ai-platform/data && tar -xzf - -C ~/ecommerce-ai-platform'
ssh ubuntu@<host> 'cd ~/ecommerce-ai-platform && python3 scripts/load_olist.py --source data/olist'
```

**Synthetic data**, if you just want a working demo:

```bash
python3 scripts/generate_synthetic_olist.py --out data/olist
python3 scripts/load_olist.py --source data/olist
```

Then, either way:

```bash
python3 scripts/create_admin.py
curl -X POST localhost:30080/api/products/reindex -H "Authorization: Bearer $TOKEN"   # fills the search index
```

## Verifying it actually works

Do not trust the container list. `docker compose ps` showing `healthy` means each process answered
its own health endpoint, which is exactly the check that has been wrong three times in this project's
history (`docs/reflection.md`). Run something that exercises the system:

```bash
python3 scripts/smoke_test.py --base http://localhost:30080   # 18 checks, one real purchase
python3 scripts/saga_demo.py --base http://localhost:30080    # the saga, timed
```

## What is not deployed

**The NL→SQL agent.** It fits in 16 GiB, and it needs a Gemini API key and 1.3 GB of embedding
weights on the host. Putting a personal API credential on an internet-facing machine is a decision to
make deliberately rather than inherit from a deploy script. Add `--profile agent` and set
`GEMINI_API_KEY` if you want it.

**Kafka Connect / Debezium.** Started on demand with `--profile cdc`, because the CDC pipeline is a
thing you run to populate the warehouse rather than a thing that needs to be up continuously.

## Cost

A t3.xlarge is about **$0.166/hour — roughly $120/month if left running.** The instance is the only
charge; nothing here creates managed services.

**Stop it when you are not using it.** Stopped instances cost nothing but their EBS volume (~$2.40/mo
for 30 GiB), and the data survives:

```bash
aws ec2 stop-instances --instance-ids i-xxxx
```

### Stopping a service does not undo what it did to the host

This host previously ran k3s. `systemctl disable --now k3s` stopped it and left **510 `KUBE-`,
`CNI-` and `FLANNEL` iptables rules** plus the `cni0` and `flannel.1` interfaces in place. They
intercepted Docker's IPv4 loopback publishing and produced a genuinely confusing split:

```
curl -6 [::1]:30080      -> 200          # works from outside the host too
curl -4 127.0.0.1:30080  -> refused      # and from inside it, nothing
```

Every container reported healthy, the public URL served traffic, and the smoke test could not reach
the gateway. If you are deploying onto a host that ran Kubernetes:

```bash
sudo /usr/local/bin/k3s-killall.sh    # removes the rules and interfaces k3s left
sudo systemctl restart docker         # so Docker reinstalls its own
```

One warning learned the hard way: **stopping an instance does not stop what runs on it.** If a
container runtime or k3s is enabled as a systemd service, starting the instance again brings every
workload back automatically. To actually keep something down across a reboot, disable the service
(`sudo systemctl disable --now k3s`) rather than relying on the instance being stopped.
