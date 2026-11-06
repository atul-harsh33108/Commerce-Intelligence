-- Reference dimensions: the ones that come from the world rather than from the OLTP databases.
--
-- dim_date and dim_geography are NOT fed by CDC, because nothing in the service databases contains
-- them. A calendar is not a business event and Brazil's state-to-region mapping is not a row anybody
-- inserted. Loading them here, once, at database creation, is the honest place for reference data -
-- and it means the warehouse can answer "revenue by region by quarter" the moment the first fact
-- arrives, rather than after somebody remembers to run a script.

USE dw;

-- --------------------------------------------------------------------------------------------
-- dim_geography: the 27 Brazilian federative units and their official IBGE regions.
--
-- Real reference data. "Which region is Minas Gerais in" has one correct answer (Southeast), and
-- getting it from a table beats getting it from an LLM's recollection - which is the actual reason
-- this dimension exists in a project whose queries are model-generated.
-- --------------------------------------------------------------------------------------------
INSERT INTO dim_geography (state_code, state_name, region) VALUES
  ('AC', 'Acre',                'North'),
  ('AL', 'Alagoas',             'Northeast'),
  ('AP', 'Amapa',               'North'),
  ('AM', 'Amazonas',            'North'),
  ('BA', 'Bahia',               'Northeast'),
  ('CE', 'Ceara',               'Northeast'),
  ('DF', 'Distrito Federal',    'Central-West'),
  ('ES', 'Espirito Santo',      'Southeast'),
  ('GO', 'Goias',               'Central-West'),
  ('MA', 'Maranhao',            'Northeast'),
  ('MT', 'Mato Grosso',         'Central-West'),
  ('MS', 'Mato Grosso do Sul',  'Central-West'),
  ('MG', 'Minas Gerais',        'Southeast'),
  ('PA', 'Para',                'North'),
  ('PB', 'Paraiba',             'Northeast'),
  ('PR', 'Parana',              'South'),
  ('PE', 'Pernambuco',          'Northeast'),
  ('PI', 'Piaui',               'Northeast'),
  ('RJ', 'Rio de Janeiro',      'Southeast'),
  ('RN', 'Rio Grande do Norte', 'Northeast'),
  ('RS', 'Rio Grande do Sul',   'South'),
  ('RO', 'Rondonia',            'North'),
  ('RR', 'Roraima',             'North'),
  ('SC', 'Santa Catarina',      'South'),
  ('SP', 'Sao Paulo',           'Southeast'),
  ('SE', 'Sergipe',             'Northeast'),
  ('TO', 'Tocantins',           'North');

-- An "unknown" member, at a fixed id.
--
-- EVERY DIMENSION NEEDS ONE. A fact whose customer has no recorded state must still join to
-- something, and the alternatives are both worse: a NULL foreign key silently drops the row from
-- every inner-join query (revenue quietly goes missing), or the fact is rejected (data loss to avoid
-- a display problem). An explicit "Unknown" member makes the gap visible in the results instead.
INSERT INTO dim_geography (geography_id, state_code, state_name, region)
VALUES (999, '??', 'Unknown', 'Unknown');


-- --------------------------------------------------------------------------------------------
-- dim_date: 2015-01-01 .. 2027-12-31.
--
-- Generated with a recursive CTE rather than by a script, so the warehouse is complete the moment
-- the container starts. The range starts before the Olist window (Sept 2016) and ends well after it,
-- because a fact whose date has no dimension row is a fact that disappears from every date-grouped
-- query - and running out of calendar is a classic, embarrassing, once-a-decade outage.
--
-- WEEKDAY() is 0=Monday in MySQL; the dimension stores 1=Monday to match ISO-8601, because that is
-- what anyone reading `day_of_week = 1` will assume.
-- --------------------------------------------------------------------------------------------
-- cte_max_recursion_depth defaults to 1000, and thirteen years of days is 4,748 - so the recursive
-- CTE below aborts one thousand rows in with "Recursive query aborted after 1001 iterations". Raised
-- for this SESSION only rather than globally: an unbounded recursion limit is a way to turn a typo in
-- a CTE into a server that allocates until it dies, and this is the one statement that needs it.
SET SESSION cte_max_recursion_depth = 20000;

INSERT INTO dim_date (date_id, full_date, year, quarter, month, month_name, day,
                      day_of_week, day_name, week_of_year, is_weekend, month_key)
WITH RECURSIVE calendar (d) AS (
    SELECT DATE('2015-01-01')
    UNION ALL
    SELECT d + INTERVAL 1 DAY FROM calendar WHERE d < DATE('2027-12-31')
)
SELECT
    CAST(DATE_FORMAT(d, '%Y%m%d') AS UNSIGNED),
    d,
    YEAR(d),
    QUARTER(d),
    MONTH(d),
    MONTHNAME(d),
    DAYOFMONTH(d),
    WEEKDAY(d) + 1,
    DAYNAME(d),
    WEEK(d, 3),
    CASE WHEN WEEKDAY(d) >= 5 THEN 1 ELSE 0 END,
    DATE_FORMAT(d, '%Y-%m')
FROM calendar;
