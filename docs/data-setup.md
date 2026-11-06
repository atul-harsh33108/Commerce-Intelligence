# Data setup

The platform is loaded from the **Brazilian E-Commerce Public Dataset by Olist** — ~100K real orders
placed on a real Brazilian marketplace between September 2016 and October 2018.

Using real data is the point. Every volume claim this project makes ("100K orders", "32K products",
"71 categories") is then a fact about a published dataset that anyone can download and check, rather
than a number somebody chose.

---

> **Loaded and verified.** Every row count below was reproduced exactly from the real download on
> 2026-08-10 — see [`measurements.md`](measurements.md). The generator described further down is what
> made Steps 3–9 buildable before the download arrived; it is kept because it still makes the
> pipeline runnable without a Kaggle account.

## The dataset is not in this repository

It is a ~45 MB Kaggle download under **CC BY-NC-SA 4.0**, which permits sharing with attribution but
not commercial use — and redistributing it inside a portfolio repository is both legally murky and
bad practice. The whole `data/` tree is in `.gitignore` - see below for why the narrow pattern was not enough.

### What to download

<https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce>

A Kaggle account is required (free). Either download the ZIP from the browser, or use the CLI:

```bash
pip install kaggle
kaggle datasets download -d olistbr/brazilian-ecommerce -p data/olist --unzip
```

### Where to put it

Unzip so that the nine CSVs sit **directly** in `data/olist/`, with their published names:

```
data/olist/
├── olist_customers_dataset.csv               99,441 rows
├── olist_geolocation_dataset.csv          1,000,163 rows   (downloaded, not loaded — see below)
├── olist_order_items_dataset.csv            112,650 rows
├── olist_order_payments_dataset.csv         103,886 rows
├── olist_order_reviews_dataset.csv           99,224 rows   (downloaded, not loaded — see below)
├── olist_orders_dataset.csv                  99,441 rows
├── olist_products_dataset.csv                32,951 rows
├── olist_sellers_dataset.csv                  3,095 rows
└── product_category_name_translation.csv         71 rows
```

Those row counts are the dataset's published figures — and **the loader reproduced every one of them
exactly**, which is the check that it neither dropped nor duplicated a row.

`data/` as a whole is gitignored, not just `data/olist/*.csv`. That is deliberate and was learned the
hard way: the download was unzipped one directory higher than these instructions say, the narrow
pattern did not match, and 164 MB of third-party CSVs went into a commit. **An ignore rule has to
cover where files actually land, not where the instructions said to put them.**

Two files are downloaded but deliberately **not ingested**, and they are listed rather than silently
skipped because "we chose not to" and "we forgot" look identical in a loader that never mentions a
file:

- **geolocation** — a million rows of zip-code centroids. `dim_geography` is derived from the
  customer's state and city instead, which is the grain every analytical question actually asks at.
- **reviews** — no service owns them yet. They are the obvious next vertical slice (sentiment on the
  warehouse, a `dim_review_score`), which is why the download instruction keeps them.

### Then load it

Infrastructure first, then the loader:

```bash
docker compose up -d
python scripts/load_olist.py --source data/olist
```

The loader is idempotent — it can be interrupted and re-run, and running it twice inserts nothing the
second time. Re-run it after adding a service; it skips any target whose table does not exist yet and
says so.

Check what is loaded at any time:

```bash
python scripts/load_olist.py --counts-only
```

---

## If you do not have the download yet

There is a generator that writes CSVs with the **same nine schemas**:

```bash
python scripts/generate_synthetic_olist.py            # ~6 s, writes data/generated/
python scripts/load_olist.py --source data/generated
```

**This exists so the rest of the system can be built and measured before the download happens, and
for no other reason.** The loader path is byte-identical for both sources — same schema module, same
`COPY`, same idempotency — so switching to the real data is one flag and replaces the numbers rather
than the code.

Every measurement taken against generated data is tagged **SYNTHETIC** in
[`measurements.md`](measurements.md), and the loader prints a warning on every run against it.

What is real in the generated files, and what is not:

| Real | Fabricated |
|---|---|
| The 71 category names and their English translations, taken from the published translation file | Every id, timestamp, price, customer and join |
| The 27 Brazilian states, weighted roughly by population | |
| The order-status vocabulary and its proportions (97% `delivered`) | |
| The payment-type vocabulary and its proportions (74% `credit_card`) | |
| Column names *including the published misspellings* (`product_name_lenght`) | |
| The shape of the data: 1.13 items per order, ~2% of products with a null category, ~8% delivered after the estimate | |

The misspellings matter more than they look: a loader that quietly renames `product_name_lenght` to
`product_name_length` works perfectly against generated files and fails against the real download.

---

## What Olist does not contain, and what the loader derives

Real anonymised commercial data has holes. Every one is filled explicitly, in one place
(`scripts/load_olist.py`), so the derivation is reviewable rather than folded into the data:

| Field | Status | How it is filled |
|---|---|---|
| Product **name** | **Absent** | Derived: `"<english category> #<first 8 of product_id>"`. Deterministic, reproducible, never treated as authoritative. It exists so a UI has something to render. |
| Product **price** | **Absent** from the product table | **Observed**: the median unit price the product actually sold at across all order items. Median, not mean — the real data contains listing-error outliers that drag a mean far enough to make a catalog page look broken. |
| Product with **no sales** | — | Has no observed price, so it is loaded `active = false`. This is why the active-product count is lower than the row count. |
| **Stock level** | **Absent entirely** | **Arbitrary, and labelled arbitrary**: `max(20, units sold in the dataset)` — "the warehouse restocked exactly what it shipped". Olist has no inventory data and no derivation from it would be less arbitrary. |
| **Order total** | **Absent** as a column | Summed from the order's items (`price + freight_value`), the only definition the data supports. |
| User **name / email / password** | **Absent** (the data is anonymised) | Username derived from the customer key; a **single shared bcrypt hash** for every seeded account. Fine for accounts nobody can be; a catastrophe in anything real. Users created through `POST /api/auth/register` get a per-user hash as normal. |

### The `customer_id` trap

Olist has two customer identifiers and they mean different things:

- `customer_id` — unique **per order**. 99,441 of them.
- `customer_unique_id` — identifies the actual **person** across orders. ~96,096 of them.

Joining `dim_customer` on the wrong one turns 96K real people into 99K fake ones and makes every
"repeat purchase rate" answer exactly zero. Both are carried through to the warehouse.

---

## Loading into the containerised stack

The loader defaults to the host ports in `docker-compose.yml` (5433–5437). Inside the Compose
network the hosts are service names and the port is always 5432:

```bash
docker compose run --rm -v "$PWD/data:/data" loader \
  python scripts/load_olist.py --source /data/olist
```

---

## Attribution

> Olist. *Brazilian E-Commerce Public Dataset by Olist*. Kaggle, 2018.
> Licensed CC BY-NC-SA 4.0. <https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce>
