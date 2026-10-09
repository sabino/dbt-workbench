# Unconventional alternatives: executed feasibility spikes

Tested on Python 3.12. SQLMesh 0.236.3, dbt Core 1.12.5, dbt-duckdb 1.11.0, DuckDB 1.5.6. SQLMesh installed SQLGlot 30.8.0. Ibis 12.0.0 tested separately with SQLGlot 30.21.0, DuckDB 1.5.6, native SQLite 3.53.1.

These are local synthetic and classic Jaffle Shop experiments. No Snowflake, BigQuery, Postgres, or other warehouse was contacted. Their generated SQL is compilation evidence only.

## SQLMesh dbt import and native planning

- `sqlmesh_spike.py` / `sqlmesh-results.json`: 4 passing checks. Imports a dbt seed, model ref, and generic tests, builds a production environment, then changes a child model in development. Unchanged upstream reuses its physical snapshot; changed child gets a new physical snapshot. Production results remain unchanged.
- `sqlmesh_jaffle_spike.py` / `sqlmesh-jaffle-results.json`: classic Jaffle Shop commit fd7bfacae4f497ff044a6a0275268676bf1b64c3. Native dbt builds 3 seeds + 5 models and passes 20 tests. SQLMesh imports 8 models, 19 model-attached audits and 1 standalone relationship audit. Exact sorted row equality for all 5 SQL models (100/99/113/99/100 rows). Editing customers' lifetime value to multiply by 2 changes only that model's physical snapshot, reuses the other 7 snapshots, yields expected 100 development rows, and preserves production rows. 7 checks pass.
- `sqlmesh-jaffle-schema-results.json`: `DESCRIBE` schemas match exactly for all 8 baseline relations. This is additional schema verification, separate from result equality.
- `sqlmesh_audit_spike.py` / `sqlmesh-audit-results.json`: duplicate one raw customer. Planning development is rejected with `unique_stg_customers_customer_id audit error: 1 row failed`, proving imported tests execute. Input seed is restored in `finally`.
- `sqlmesh_incremental_spike.py` / `sqlmesh-incremental-results.json`: concrete incompatibility. A dbt incremental model with `unique_key='id'` and `incremental_strategy='append'` initially produces 2 identical rows in both systems. Second native dbt run appends to 4 rows. SQLMesh warns and falls back to merge, successfully retains 2 rows. Consequently it cannot be treated as a universal dbt-authoritative execution replacement.

**Breakthrough candidate:** a SQLMesh preview plugin can reuse existing snapshot/version/diff/isolation machinery rather than rebuilding it. Keep native dbt execution authoritative. Admit SQLMesh for a project/model class only after differential regression tests show materialization, macro, hook, and test behavior matches or the user explicitly chooses SQLMesh semantics. This spike proves same-engine DuckDB reuse, not cross-engine snapshot deferral/federation.

## Ibis typed relational compiler

- `ibis_spike.py` / `ibis-results.json`: 10 expression tests executed against DuckDB and SQLite. 7 have identical results: aggregation/filter, nonzero true division, explicit NULL-on-zero division, month truncation, window row number, self join, literal escaping.
- Unguarded division by zero executes differently: DuckDB infinity vs SQLite NULL. Explicit `denominator.nullif(0)` makes the tested query equivalent.
- TryCast and array operations work on DuckDB but are explicitly rejected at SQLite compilation, rather than silently approximated.
- Ibis inserts `CAST(numerator AS REAL)` for SQLite true division automatically. It supplies useful typed semantic lowering beyond generic SQL AST generation.
- Four expressions compiled for Postgres, Snowflake, BigQuery, without executing those engines. Snowflake TryCast(string → bigint) unexpectedly renders CAST rather than TRY_CAST under these pinned versions, so even typed IR compilation needs operator/target qualification and native validation.
- `ibis-dialect-results.json`: simple select compiles for 15 of 33 exact SQLGlot dialect registry names. Several misses are naming aliases (e.g. tsql/mssql) and others lack Ibis compilers; this is a registry-routing probe, not a complete database-support score. Ibis alone cannot promise every SQLGlot target.

**Breakthrough candidate:** keep a versioned human-readable EDN/JSON workbench document and lower its supported operators through Ibis, retaining SQLGlot for imported SQL, lineage, and dialect bridges. This can avoid authoring a large relational compiler. Never persist Ibis Python objects as the public document format. Admission requires source mapping, exact supported operator contracts, rejection on unsupported targets, round-trip document editing, parameter handling, dbt reference binding, and native result/schema tests.

## Reproduction

Use separate virtual environments; lock files are included. Source classic Jaffle Shop must exist at `/tmp/workbench-spikes/jaffle-shop-classic` at the recorded commit. Scripts own isolated project paths and recreate those paths before running.

```sh
python -m venv venv
venv/bin/pip install -r sqlmesh-requirements.lock
venv/bin/python sqlmesh_spike.py
venv/bin/python sqlmesh_jaffle_spike.py
venv/bin/python sqlmesh_audit_spike.py
venv/bin/python sqlmesh_incremental_spike.py

python -m venv ibis-venv
ibis-venv/bin/pip install -r ibis-requirements.lock
ibis-venv/bin/python ibis_spike.py
```

The full compiler and planner APIs remain versioned dependencies; isolate optional planners behind worker boundaries and regression-test updates.

References: [SQLMesh dbt integration](https://sqlmesh.readthedocs.io/en/stable/integrations/dbt/), [SQLMesh multi-engine](https://sqlmesh.readthedocs.io/en/stable/guides/multi_engine/), [Ibis documentation](https://ibis-project.org/), [Ibis supported backends](https://ibis-project.org/backends/). SQLMesh and Ibis package metadata report Apache 2.0 licenses.
