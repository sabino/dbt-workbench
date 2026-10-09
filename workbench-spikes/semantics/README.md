# SQLGlot workbench semantic spike

This is an executable boundary experiment, not an application implementation or a promise of dialect-wide equivalence. It executes SQL against real SQLite and DuckDB, records the generated SQL and errors, and tests small explicit semantic corrections.

Pinned environment: Python 3.12.14, SQLite 3.53.1, SQLGlot 30.21.0, DuckDB 1.5.6, NumPy 2.4.3. NumPy is required by the DuckDB Python UDF experiment. SQLite is the Python build's bundled native library and must also be pinned when reproducing exact results.

Run:

```sh
/tmp/dbt-workbench-feasibility-venv/bin/python /tmp/workbench-spikes/semantics/semantic_spike.py --label initial --output results.json
/tmp/dbt-workbench-feasibility-venv/bin/python /tmp/workbench-spikes/semantics/semantic_spike.py --label process-restart --output restart-results.json
```

Both commands execute the suite twice with fresh database connections. `restart-comparison.json` records that all four executions had the same outcomes after excluding labels and illustrative timings. The script asserts that corrected lowering, bounded import, expected rejection, semantic emulation and storage reconnect checks pass.

## Evidence

| Experiment | Result | What it establishes |
|---|---|---|
| 33 adversarial SQLite↔DuckDB queries, strict parse and unsupported errors | 16 produced equal row values | SQLGlot strict flags do not certify equivalent behavior. This deliberately chosen corpus is not a coverage percentage. |
| Same queries after qualification and type annotation | 15 produced equal row values | Typing can improve a translation or introduce a different failure; it is not an equivalence proof. |
| Source-sensitive SQLite lowering | Eight fixture cases match actual SQLite; three known unsupported cases reject | Explicit integer division, float truncation and a SQLite-backed text-to-integer UDF can repair bounded cases. Date modifiers, unspecified case-sensitive LIKE behavior, and the tested integer-overflow case reject. |
| Canonical floating division lowered directly to AST | Five cases match across actual engines | Casting operands to DOUBLE and using NULLIF for the denominator defines fractional division with NULL on zero. Positive, negative, zero and NULL operands are exercised. |
| Source-specific BigQuery/Snowflake correction | Six DuckDB results match documented source expectations | The guard rejects invalid BigQuery boolean strings and an explicit millisecond epoch unit fixes one Snowflake numeric timestamp example. Neither remote source engine was executed. |
| Bounded SQL→logical IR→SQLGlot AST→SQL | Five editable cases retain row behavior in both actual engines | Direct AST generation can transport a small editable query language with named parameters without a SQL text formatter between the IR and SQLGlot. |
| Opaque SQL preservation | Six unsupported inputs retain exact raw source and SHA256 through JSON transport; unknown IR version rejects | Jinja, CTEs, UDFs, JOINs, bare division and multiple statements remain authoritative SQL instead of being partially converted into editable IR. |
| Source-semantic UDF island | Eight edge-case casts agree; 10,000-row sum is correct | DuckDB can call pinned SQLite semantics through a Python UDF. This is a narrow emulation option with a per-row callback cost. |
| Native storage reopen and driver reinitialization | Persisted data survives; UDF is absent until re-registered; result matches after initialization | Connection initialization belongs in the driver contract. Python UDF registrations are not stored in the database file. |
| SQLite dynamic affinity | INTEGER column stores text and real values; identical DuckDB import fails | SQLite declared schema alone cannot certify fixed runtime types. Import must validate actual values or preserve native execution. |

Important concrete regressions include SQLite `3 / 2` producing 1 but typed DuckDB translation producing 2; SQLite CAST('123x' AS INTEGER) producing 123 but DuckDB throwing; typed division by zero throwing although SQLite returns NULL; DuckDB TRY_CAST('abc' AS BIGINT) becoming SQLite CAST and returning 0; and SQLite date modifiers being treated as timezone arguments. Every native comparison and emitted SQL is in `results.json`.

## Bounded support contract

The editable importer accepts one SELECT, a physical FROM relation and optional alias, projections, scalar literals, columns, named parameters, selected comparisons and arithmetic, boolean operators, IS NULL, ORDER BY and integer LIMIT. Unsupported clauses and expressions reject to the opaque SQL envelope. It preserves `= NULL` and `IS NULL` as distinct expressions. A repeated named parameter and a string containing quotes, Unicode and SQL-looking text are bound as data in both engines.

The envelope stores raw SQL, source dialect and its hash. Regenerated SQL is normalized and can lose comments; the authoritative source retains all comment and Jinja bytes. This proves bounded semantic roundtrip plus exact opaque-source preservation, not lossless arbitrary SQL editing. HoneySQL was not run in this spike.

The source-sensitive repair is limited to the concrete fixture and checked operators. Integer division uses DuckDB integer division with a zero-denominator guard; it must not be enabled merely because a SQLite catalog says INTEGER. Runtime values, overflow, mixed numeric representations and type domains need additional certification. Date/time precision, timezone/DST, collations, JSON, floating NaN, decimal overflow, array indexing, session settings, warehouse UDFs and arbitrary joins are not certified by these tests.

The canonical division contract is float64, NULL-propagating and NULL-on-zero. It does not claim exact decimal or rational arithmetic. Native SQL type identity is recorded but equality assertions compare serialized row values, so passing value comparisons do not certify equal output types. Date/time values are normalized to ISO strings for comparison.

## Recommended driver decision

Each driver should classify an operation as supported under an explicit semantic contract, supported after a tested correction or emulation, or unsupported and requiring native execution. Qualification, annotation, strict SQLGlot flags and target execution are useful checks; they cannot produce the classification without a source/target contract and regression evidence. Maintain versioned, engine-pair regression fixtures and re-run them when any engine, compiler or semantic rule changes.

The SQLite-backed UDF is an unconventional but proven small escape hatch: register it on each DuckDB connection, preserve SQL NULL behavior, bind its source-engine query parameter, and constrain its accepted type. Its approximately 0.6–0.8-second 10,000-row example is illustrative rather than a benchmark or a claim of acceptable production performance. Broader SQL materializations should execute natively rather than crossing a Python callback for every scalar.

## Artifacts and source evidence

- `semantic_spike.py`: corpus, direct AST lowering, importer, UDF and reconnect probes.
- `results.json`: first process, full queries and actual native engine results.
- `restart-results.json`: second process, full queries and results.
- `restart-comparison.json`: process/reconnect revalidation and artifact hashes.
- `requirements.txt`: Python package pins.
- `reopen.sqlite` and `reopen.duckdb`: local persisted fixtures, no production data.

Documented-only source checks:

- https://docs.cloud.google.com/bigquery/docs/reference/standard-sql/conversion_functions#cast_as_bool
- https://docs.snowflake.com/en/sql-reference/functions/to_timestamp#usage-notes

SQLGlot references:

- https://sqlglot.com/sqlglot.html
- https://github.com/tobymao/sqlglot/blob/v30.21.0/sqlglot/generator.py
- https://sqlglot.com/sqlglot/serde.html
