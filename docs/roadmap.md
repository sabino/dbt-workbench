# From v0.1 to a broader cross-engine workbench

The target is a local workbench that can iterate on an existing dbt project, choose upstream data from explicit targets, and validate supported queries on another engine. Expansion should preserve the working native dbt path and add verified capabilities one at a time.

SQLGlot dialect coverage is a translation inventory, not an execution guarantee. A supported path needs a source connector, target execution, relation bindings, data-transfer rules, and evidence that the requested operations preserve their declared meaning.

## Current implementation

v0.1 supplies a Fulcro interface, conflict-aware local file editing, native dbt jobs, imported or locally built baseline artifacts, durable job logs, cancellable work, and persisted query results. Its preview sources are local DuckDB profiles. Preview execution uses DuckDB or a real SQLite database populated with bounded captures. EDN v1 supplies a small, explicit query vocabulary.

Native dbt adapters can execute their own projects through the selected dbt executable. That does not make those adapters preview connectors. Existing manifests preserve reference state; they do not freeze the referenced database data. See [architecture](architecture.md) and [limits](limitations.md) for the implemented contract.

## Evidence informing the sequence

These were isolated feasibility experiments, not additional shipped features. Promote their useful fixtures into the project's regression suite before depending on them.

| Experiment | Observation | Consequence |
| --- | --- | --- |
| Classic Jaffle Shop on native dbt and local engines | A small, real project exercises references, seeds, joins, aggregates, tests, and iterative changes | Keep it as a complete user-flow fixture, supplemented by adversarial cases |
| PostgreSQL 16.15 → typed Arrow → DuckDB | Three staging relations supported matching logical results for 99 orders and 100 customers, including 152 null cells; integer aggregate result types differed | PostgreSQL is the next concrete preview connector; value equality alone is insufficient |
| PostgreSQL cents arithmetic | A straightforward SQLGlot translation differed; adding types still produced a rounded cast; an explicit integer-division rule fixed the tested case | Preserve source semantics through operator/type rules and native-oracle tests |
| SQLMesh 0.236.3 importing classic Jaffle Shop | Model rows and schemas matched native dbt; a changed customer model reused seven unchanged snapshots; an invalid customer key failed its imported audit | Its planner is worth evaluating as an optional backend |
| SQLMesh incremental append with a unique key | The importer warned and used merge: two native dbt runs yielded four rows, while SQLMesh retained two | Do not replace native dbt's execution semantics implicitly |
| Ibis 12.0.0 typed expressions | Several DuckDB/SQLite expressions matched and unsupported operations were refused; unguarded division by zero still differed | Reuse typed compilation where useful, but retain capability checks and semantic tests |

## Gate 0 — Preserve the working release

Before adding connectors, make the current path reproducible from a clean installation.

The acceptance run is: create classic Jaffle Shop → build baseline → inspect a result → edit and save a model → build only that model with deferral → inspect the changed result → compare saved results → restart and recover the result history. Run the same structured query on DuckDB and SQLite and compare its rows. Exercise stale-save conflicts, edits/imports blocked during active work, rejected writes/external scans, cancellation, invalid parameters, input limits, and interrupted-job recovery.

Use the [integration flow](../tests/test_integration.py), [query cases](../tests/test_query.py), and [runtime cases](../tests/test_runtime.py) as the current test entry points. Record the code revision, pinned dependencies, fixture revision, commands, and test outcomes. Keep a browser check for the complete visible flow alongside API and engine tests. A release passes this gate only when its checked-in tests and clean-install workflow pass; a successful isolated spike is not a substitute.

## Gate 1 — Separate metadata, data access, and execution

Introduce a preview-provider boundary while retaining native dbt CLI jobs. A provider should describe relations and types, read an explicitly bound relation into Arrow, execute permitted native reads, cancel work, and report capabilities. It must not reinterpret a dbt materialization.

Represent each query input with a dbt identity, source target, physical relation, schema, and capture identity. Bindings must distinguish a selected development result, a deferred baseline reference, an explicit override, and an unresolved external input. Surface the actual choice in result provenance.

Acceptance evidence:

- Existing DuckDB and SQLite regression cases pass through the provider boundary without changed behavior.
- Multiple schemas, aliases, quoted identifiers, CTE shadowing, self-joins, and ambiguous relation names bind correctly or fail clearly.
- A missing source connection cannot be mistaken for an empty table or a successful deferral.
- Native dbt remains available when a preview provider lacks a required capability.

## Gate 2 — Add PostgreSQL as a complete preview path

Implement a PostgreSQL provider and an automated local PostgreSQL test environment. Start with PostgreSQL inputs captured into DuckDB; retain PostgreSQL native execution as the reference for result and type comparisons. Keep credentials local and use the existing project's target configuration through an explicit connection adapter.

Capture the boundary of the selected model subgraph: materialized upstream relations that the selected computation needs. Inline ephemeral computations through dbt compilation. Do not copy unrelated tables or independently sample joined tables. For PostgreSQL, use a consistent read transaction for a multi-relation capture and record the isolation/capture metadata.

Acceptance evidence:

- Classic Jaffle Shop passes its native PostgreSQL build and tests.
- Capturing its required relations supports the selected DuckDB previews with declared value **and type** comparisons.
- Date/time values, nulls, quoted names, schema overrides, negative integers, 64-bit bounds, decimal precision/scale, empty inputs, and duplicate rows have focused fixtures.
- The cents/integer-division counterexample fails before its semantic rule and passes after it. Adding schema information alone must not be reported as fixing it.
- Timeout, cancellation, missing privileges, and connection failures leave a clear job outcome and allow a subsequent query to succeed.

This gate produces one qualified cross-engine path, not a general PostgreSQL compatibility badge.

## Gate 3 — Make captures durable and comparisons type-aware

Add immutable Arrow or Parquet captures with content hashes, schemas, binding manifests, source-target identities, and capture consistency information. Distinguish a capture's data version from the dbt artifact version. Reusing an old capture must be an explicit choice.

Define conversion rules for each supported Arrow/source/target type combination. Unsupported precision, nested types, timezone behavior, or integer ranges should fail or require an explicit conversion. Preserve decimal values without silently converting them to binary floating point.

Acceptance evidence:

- A saved capture can execute offline after its source connection is unavailable.
- Mutating source tables cannot change an existing capture or saved result.
- Corrupt captures and schema mismatches are detected before execution.
- Comparison reports value, type, nullability, and declared ordering differences separately. Numeric tolerances are explicit per comparison, never an automatic way to hide a failed semantic check.
- Multi-table fixtures demonstrate a coherent capture rather than incompatible independently sampled rows.

## Gate 4 — Expand query semantics and choose compiler reuse

Grow EDN from the current subset only when a user operation needs it. Define each new operator's input/output types, null behavior, error behavior, ordering, timezone rules, and precision. Keep the document versioned and human-readable, independently of any library's internal AST.

Use native-engine output as the oracle for imported SQL. For newly authored structured queries, test the declared operator contract against each supported engine. Include generated edge cases and metamorphic checks where they add evidence, such as preserving results under a harmless alias change or a supported predicate rewrite.

Evaluate compiler alternatives behind the same document and binding interfaces:

- **SQLGlot:** retain it for parsing, SQL analysis, scope-aware rewrites, and dialect generation.
- **Ibis:** consider typed relational lowering if it reduces supported-operator work without hiding target restrictions.
- **Metabase compiler:** require an executable extraction proof using a supplied metadata provider, several native-engine comparisons, a manageable dependency boundary, and a deliberate licensing decision before adoption.
- **HoneySQL:** useful for readable Clojure SQL construction; it still needs the workbench's semantic contracts and capability checks.
- **Pathom:** add it only if composing independent metadata sources measurably simplifies the application. It should resolve metadata, not become the model-execution scheduler.
- **SQLMesh:** evaluate as an optional planner for qualified projects. Its incremental/materialization differences must remain explicit.

Admission requires a reproducible benefit on the same fixture corpus and no regression in diagnostics, cancellation, source mapping, or result provenance. An attractive abstraction or successful SQL string generation is insufficient.

Unsupported arbitrary SQL should offer execution on its native engine when that provider can enforce the read policy. Otherwise show the unsupported operation and retain the native dbt workflow. Do not translate it into an approximate successful preview.

## Gate 5 — Add warehouse connectors with actual credentials

After PostgreSQL is stable, choose a warehouse connector based on real use. Snowflake and BigQuery are candidates, not implemented promises. Validate one source/target path at a time using authorized test accounts, known fixtures, explicit query/data budgets, and native result oracles.

Require tests for warehouse-specific casts, timestamp/timezone behavior, numeric ranges, identifier rules, JSON/nested types, and supported UDF handling. Test dbt compile-time queries, dispatch macros, incremental state, and source mappings separately from SELECT translation. Record which combinations require native validation.

No connector is accepted on generated SQL alone. If credentials or a service are unavailable, its status remains unverified. Core local tests should continue to run without warehouse accounts; connector tests should report their prerequisites and results separately.

## Ongoing support rule

Track capabilities by provider version, target engine/version, operation, and relevant types. Each entry should state whether it is implemented and tested, refused with a diagnostic, or awaiting validation. Re-run the applicable corpus before updating dbt, SQLGlot, a database driver, or an execution engine.

This capability record gives users concrete evidence for choosing local execution, a translated preview, or native validation.
