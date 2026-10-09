# PostgreSQL → DuckDB dbt workbench spike

This is an executable vertical slice against classic dbt Jaffle Shop at commit
`fd7bfacae4f497ff044a6a0275268676bf1b64c3`, with a disposable local PostgreSQL
16 server. It uses no remote warehouse credentials.

Run `./reproduce.sh`. The script pins six Python dependencies and the PostgreSQL
Docker image digest, starts PostgreSQL on loopback port 55432, and cleans up its
own container on exit. `bridge.py` saves compiled native/transformed queries,
dbt logs, baseline manifest/run results, typed Parquet inputs with hashes and
transaction provenance, the DuckDB preview database, and JSON results.
The complete transitive Python package lock is `requirements.lock.txt`.
The final experiment was revalidated on a newly created clean server;
`artifacts/revalidation.json` records identical check outcomes and snapshot hashes.

## Observed results

- The unmodified classic project passes 28 dbt results: 3 seeds, 5 models and
  20 data tests.
- Native dbt development orders/customers with `--defer --favor-state` reference
  the baseline staging relations and match their baseline outputs.
- Exporting all three staging relations in a single repeatable-read transaction,
  then rewriting only physical relation nodes with SQLGlot scope analysis,
  yields DuckDB outputs equal to PostgreSQL logical values: 99 orders and 100
  customers, including 152 NULL customer cells and real dates.
- Output physical types differ: PostgreSQL SUM(integer) returns BIGINT,
  DuckDB returns HUGEINT. These are recorded explicitly; the value comparison
  normalizes numeric values and does not claim exact physical schema equality.
- Typed Arrow/Parquet explicitly preserves DECIMAL(18,4), dates and nulls in a
  separate adversarial relation. No float conversion is used for decimals.
- Naively translating classic stg_payments returns matching values on the
  original fixture because every cents value is divisible by 100. The inferred
  amount type changes INTEGER → DOUBLE: that is a hidden portability gap.
- Adversarial 105, -105, 199 and -199 cents expose incorrect untyped translation.
  Schema annotation without qualification still lacks column types. After
  qualification SQLGlot emits CAST(cents / 100 AS BIGINT), which rounds 1.99 to 2
  in DuckDB; PostgreSQL integer division yields 1.
- An explicit typed source-semantic lowering to integer division (`//`) matches
  the cents probes. This is a narrow compatibility rule. Division by zero,
  integer overflow and decimal promotion remain separate contracts to test.

## Implications

The basic native dbt + frozen boundary snapshot + compiled SQL bridge path works
on two distinct actual engines. Data bindings and coherent typed snapshots are
useful whether authoring SQL, EDN, MBQL or a bespoke query representation.

Passing Jaffle Shop alone is insufficient evidence for universal SQL portability.
The product needs adversarial fixtures and per-operator semantic policies. A
typed IR helps choose such policies; it does not automatically supply them.

The query bindings currently use the three-part physical relations from the dbt
manifest's compilation context. Production implementation must associate them
with dbt unique IDs, immutable run context and environment-specific bindings.
CTEs are preserved by SQLGlot selected-source scope analysis, rather than global
string replacement. Python dbt models, materializations, incremental state,
macros requiring remote introspection and custom adapter behavior are outside
this spike.
