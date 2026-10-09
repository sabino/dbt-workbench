# Scope and limits

This release is a working local dbt workbench with a bounded preview engine. The distinction between native dbt execution and cross-engine previews is part of its behavior.

| Capability | Current support |
| --- | --- |
| Project editing | SQL, YAML, and EDN files inside an opened local project; conflict-aware saves |
| Native dbt jobs | Parse, build, and test through the configured dbt executable and installed adapters |
| Baseline creation | Successful native build plus immutable artifacts; latest baseline used for deferral |
| Native deferral | dbt's own resolution rules and database-access requirements |
| Interactive input databases | Local DuckDB targets |
| Preview execution engines | DuckDB and actual SQLite over captured inputs |
| Structured authoring | EDN v1 with the supported operators; SQL text |
| Saved results | Arrow-backed pages, metadata, exact comparison, CSV download |
| Multi-user hosting | Not supported; server binds to loopback |

## Portability

SQLGlot supports more dialects than the workbench currently executes. There is no claim that arbitrary SQL, dbt models, or database pairs are portable. Unknown functions, unsupported syntax, and operations with unverified cross-engine behavior are rejected where the engine detects them.

A native dbt adapter remains responsible for its own macros, incremental materializations, snapshots, hooks, grants, Python models, and database-specific features. A preview of a compiled SELECT does not reproduce all of those effects. Testing query results locally is not equivalent to validating the complete native build.

SQL query input is currently parsed as DuckDB SQL. Existing SQL is not automatically converted into a reversible visual query-builder document. EDN queries are a supported authoring surface, and existing dbt SQL remains editable as SQL. There is no arbitrary SQL → EDN round trip in this release.

Remote warehouse extraction, general federation, automatic production sampling, and offline reconstruction of every dbt macro are not implemented. Compilation may itself contact a database through project macros; an artifact manifest cannot replace missing data or all compile-time database interactions. Native deferral can reference another database only when the active adapter and its connection can actually reach that relation.

## Bounds and data types

Preview and query output defaults to at most 10,000 rows, with a hard maximum of 100,000. Captured inputs are limited to 100,000 rows per input relation; oversized inputs are rejected rather than silently sampled. Query execution has a default 30-second timeout and a maximum configurable timeout of 120 seconds. Result pages contain at most 1,000 rows; the interface requests 100 at a time.

These bounds make interactive work predictable. They do not estimate the production query's cost or guarantee that a bounded preview matches full-data behavior. Native dbt builds can run against complete datasets.

SQLite input capture rejects DECIMAL columns because storing them as text would change numeric comparisons. Use DuckDB for those inputs, or create a model with an explicit conversion when its precision tradeoff is acceptable. JSON result pages encode decimals as strings and dates/timestamps as ISO text. Saved comparisons are exact and have no configurable numeric tolerance. Duplicate comparison keys are rejected instead of choosing a row arbitrarily. Comparisons reject truncated results: matching prefixes cannot establish query equality. `equal` describes row values; `schema_equal` separately describes Arrow schemas, so equal numeric values with different types need not have equal schemas.

## Baselines, persistence, and cancellation

An immutable baseline manifest does not freeze the database data it names. Rebuilding the same baseline target, or changing it outside the application, changes the data future queries will read. Older artifact directories are retained, but executing against a selected historical baseline is not implemented.

Saved result rows are separate captures and remain available after their sources change. Comparison operates on those captures. The application does not provide database rollback, migrations for arbitrary external projects, or historical table time travel.

Cancelling a job stops its worker or subprocess; it cannot reverse statements that a database already committed. After a server restart, unfinished jobs are marked interrupted and require an explicit retry.

## Local environment

The application includes dbt Core and dbt-duckdb. Other adapters, credentials, dbt packages, and external executables must be available in the configured dbt environment. DuckDB previews accept literal persisted database paths and narrowly rendered `env_var` paths; arbitrary Jinja in a profile path is not supported by the preview reader. A successful native build depends on that project's dbt version constraints and database permissions.

Only one workbench process can use a data directory at a time. The file editor excludes profiles and generated/dependency directories. Saving files or importing a baseline is blocked during queued or active project jobs; external file edits are not frozen by the application. Credentials can still be used by dbt in the local process. The server has no multi-user authentication or remote hosting mode. Use it with trusted local projects and development targets appropriate to the work being performed.

## Validation

The bundled classic Jaffle Shop project is a concrete example and regression fixture. Its five SQL models and three seeds cover useful dbt behavior, but do not represent every materialization, macro package, dialect, or production data type. See the automated tests and the release verification results for the cases actually exercised.
