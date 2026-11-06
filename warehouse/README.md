# The analytics warehouse

MySQL 8, star schema, fed by change data capture from the PostgreSQL service databases.

```
catalogdb ─┐
userdb    ─┼─ Debezium (logical replication) ─► Kafka ─► cdc_sink.py ─► MySQL dw
orderdb   ─┘         Kafka Connect                        (the transform)
```

## Run it

```bash
docker compose up -d warehouse-db kafka
docker compose --profile cdc up -d kafka-connect
python scripts/register_connectors.py
python warehouse/etl/cdc_sink.py --drain      # drains the snapshot, then exits
python warehouse/etl/cdc_sink.py              # stays running, streams changes
```

`--drain` exits once the topics are caught up; without it the sink runs until interrupted.

## What is where

| Path | What |
|---|---|
| `ddl/01_dw.sql` | The star schema, with the reasoning for the shape |
| `ddl/02_seed_dimensions.sql` | `dim_date` (4,748 days) and `dim_geography` (27 states + Unknown) |
| `ddl/03_meta_and_grants.sql` | The agent's metadata tables and the read-only warehouse credential |
| `etl/cdc_sink.py` | The transform: change events → star schema |
| `../scripts/register_connectors.py` | Debezium connector definitions |
| `../scripts/measure_cdc_lag.py` | End-to-end freshness measurement |

## The two decisions worth defending

**Why a hand-written sink instead of Kafka Connect's JDBC sink.** A JDBC sink writes a *table copy* —
give it `order.public.orders` and you get a MySQL table called `orders`. That is replication, not a
warehouse. A fact row here joins an order to a line that arrive on different topics, resolves
surrogate keys against dimensions that may not exist yet, and computes measures no source column
contains. Kafka Connect's single-message transforms are, by definition, single-message. The real
alternatives were a stream processor (Flink, Kafka Streams) or a consumer that does the join in the
database it writes to; for 118K facts on one laptop the second is smaller and makes the dimensional
modelling visible rather than hiding it in connector JSON. **The cost:** one process, no horizontal
scaling story. A Flink job would have both.

**Why the fact table has no foreign keys.** The CDC streams are independent topics with independent
consumer lag, so a fact can legitimately arrive before its dimension. Enforcing referential integrity
would make the sink fail and retry until the dimension caught up — a millisecond of skew becoming a
stalled partition. The sink implements the standard *late-arriving dimension* pattern instead: insert
a stub row, let the real event fill it in. The integrity guarantee lives in the sink, and the check
for when the sink is wrong is below.

## Health checks

Orphan facts — a fact pointing at a dimension row that does not exist. Should be 0:

```sql
SELECT COUNT(*) FROM fact_order_items f
  LEFT JOIN dim_product p ON p.product_id = f.product_id
 WHERE f.product_id IS NOT NULL AND p.product_id IS NULL;
```

Unresolved stubs — dimension rows the real change event never filled in. A small number during a
backlog is normal; a number that does not fall is a bug:

```sql
SELECT COUNT(*) FROM dim_product WHERE product_name LIKE '(unresolved)%';
```

Facts with no geography — these land on the `Unknown` member (999) rather than NULL, so they stay
visible in grouped results instead of dropping out of inner joins:

```sql
SELECT COUNT(*) FROM fact_order_items WHERE geography_id = 999;
```

Replication slot health. **An inactive slot makes Postgres retain WAL forever**, which presents as a
disk-full incident rather than a CDC one:

```bash
docker exec ecommerce-order-db psql -U orderdb -d orderdb \
  -c "SELECT slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained FROM pg_replication_slots;"
```

`python scripts/register_connectors.py --delete` drops the connectors **and** their slots. Deleting
only the connectors is the mistake that fills a disk a week later.

## The query the whole step exists for

```sql
SELECT p.category, d.month_key, ROUND(SUM(f.line_revenue), 2) AS revenue
  FROM fact_order_items f
  JOIN dim_product p ON p.product_id = f.product_id
  JOIN dim_date    d ON d.date_id    = f.date_id
 WHERE f.order_status = 'DELIVERED'
 GROUP BY p.category, d.month_key
 ORDER BY revenue DESC;
```

Two joins, one GROUP BY, no subqueries. Against the normalised OLTP databases the same question needs
a join across three *services* — which is to say it cannot be written at all, because there is no
connection between the databases. That is the argument for a warehouse, stated concretely rather than
as "analytics is different".

## AWS mapping

| Local | AWS | What changes |
|---|---|---|
| `warehouse-db` (MySQL 8) | RDS for MySQL, or Redshift for real scale | Connection string |
| `kafka-connect` + Debezium | MSK Connect | Same connector JSON; the plugin is uploaded to S3 |
| `cdc_sink.py` | ECS/Fargate task, or a Flink job on Kinesis Data Analytics | Nothing, if it stays a consumer |
| `wal_level=logical` | `rds.logical_replication=1` in an RDS parameter group | A parameter group and a reboot |
