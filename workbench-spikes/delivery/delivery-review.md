# Delivery architecture and acceptance review

This is a delivery review, not a compiler implementation. Experiments ran on Linux, Python 3.12.14, DuckDB 1.5.6, and PyArrow 25.0.1. The source material and executable probes are beside this file.

## Recommended release architecture

Start with a compiled Fulcro browser bundle served by a local Python application. The release does not require a JVM at runtime unless an optional compiler backend needs one. A desktop shell should follow a working installer and end-to-end browser story, rather than precede it. Pathom is useful if composing metadata becomes complicated, but it need not own execution or block release.

The Python supervisor owns workspace metadata in SQLite, invokes dbt CLI in independent subprocesses, and runs SQLGlot-based previews in independent workers. It uses an installed/project dbt executable or a workbench-owned adapter environment; it does not depend on private dbt runtime entry points. Every run has its own target-path and log-path. Baseline manifests live in immutable directories distinct from run output. The dbt docs explicitly warn that pointing --state and --target-path at the same location destroys the comparison baseline.

One writer job at a time owns each DuckDB file. The supervisor and browser never hold a DuckDB connection to a file being written by a dbt worker. Results and extraction snapshots publish immutable Arrow files; the browser fetches bounded batches. Different workspace databases can run independently. Direct preview execution owns its own connection and uses connection.interrupt(). Cancellation of warehouse queries must use their native query IDs and adapter cancel operations. Process termination alone does not prove engine cancellation.

Expose execution target separately from reference baseline and per-model/source overrides. Native dbt mode uses dbt's reference resolution. Preview mode records an explicit binding for each logical dbt node to a snapshot, local override, or accessible native relation. Preserve snapshot IDs, freshness, schemas, type conversion rules, and compilation origin in results.

Keep existing dbt SQL authoritative. Versioned query documents are authoritative for newly authored structured models, with deterministic SQL export and a generated-file checksum. Avoid claiming arbitrary SQL can round-trip into the query builder. A small IR needs explicit operator semantics, type validation, unknown-field rejection, and no silent dropped projections before it needs many operators.

Use Metabase full query processor only as an optional backend, not direct driver compilation. Root experiments showed the direct route loses joins' selected fields and accepts unknown columns, while full processing supplies validation and expansion. Current extraction uses internal bootstrap shims, adds a large JVM/JAR runtime, and raises AGPL distribution decisions. This is viable but should not become an unavoidable dependency before its isolated contract is established.

SQLMesh can be an optional preview planner. Root's classic jaffle import and upstream reuse prove a useful path, not general dbt semantic equivalence. Native dbt remains authoritative for package macros, hooks, custom materializations, incremental builds, snapshots, and all supported installed adapters.

## Concrete acceptance gates

1. **Reproducibility:** one command reruns saved probes from pinned dependencies, and every result records source revision, query/document hash, compilation origin, reference binding, baseline hash, engine/dialect/version, schema/snapshot IDs, and settings that affect semantics. Re-run the same fixtures after any compiler, dbt, adapter, or engine upgrade; invalidate caches on context changes.

2. **First complete user story:** from a clean machine, install, open the unmodified fixture dbt project, import a baseline, edit a model, select descendants, compile with actual dbt, defer upstream models, preview locally, run meaningful dbt tests, compare values with the native target, inspect mapped diagnostics, cancel, reconnect, and recover results after application restart. Assert baseline files and the source project were not silently overwritten. Export the intended SQL/project change and build it with plain dbt outside the workbench.

3. **Representative project behavior:** include a package-dispatched macro, ephemeral parent, a source override, an incremental model with two successive runs, a schema change, a failing data test, and a snapshot/native-only model. For each case, native dbt behaves like standalone CLI. Local preview either produces verified results or blocks with an actionable reason; it never reports equivalent validation for an unsupported behavior.

4. **Structured query contract:** reject unknown fields/operators, preserve explicit projections, resolve scopes in self-joins/CTEs, and define casts, division-by-zero, integer division, decimals, timezones, timestamps, null ordering, nested values, and identifier quoting. Persist and reload documents without losing meaning. Generate stable SQL and compare fixture results on every enabled execution engine. Round-trip testing applies only to the supported structured subset.

5. **Broad engine capability:** list all SQLGlot dialects but distinguish compile support, connector availability, fixture-validated operators, and model-specific blockers. Translation output alone is not a green execution badge. Enable an engine pair after native result comparison on a portable fixture corpus plus engine-specific casts, types, UDFs, and materialization cases. No available warehouse credentials means native compatibility remains unverified, not passed.

6. **Lifecycle and conflict behavior:** browser reconnection replays events without duplication; crash recovery marks interrupted jobs accurately; stale editor saves do not destroy external changes; concurrent jobs do not contend unexpectedly for one DuckDB file; cancellations remain cancelling until the execution engine acknowledges termination. Windows process trees need a real job-object/process-tree implementation and tests; Linux killpg evidence does not cover Windows.

7. **Result bounds and packaging:** stream at least one million rows through Arrow, fetch first/middle/last pages after reconnect, preserve decimals/timestamps/nulls, and keep the frontend responsive without loading all rows into Fulcro state. Test complex queries with deliberate memory/spill budgets separately. Validate installed builds on clean macOS, Windows, and Linux for supported architectures, paths with spaces/unicode, adapter environment discovery, stopped server/restart, and offline execution from captured data. Python frozen binaries need separate per-OS builds; one Linux binary is not a cross-platform installer.

## Executable evidence

`probe.py` passed six scenarios: persisted failed worker (exit 7) plus event replay; process-group cancellation of launcher and child; orphan state reconciliation; DuckDB cross-process lock behavior; typed Arrow file result-page reopening; and stale external-editor save rejection. Cancellation took approximately 122 ms including the heartbeat verification wait. The restart test is deliberately minimal: a real supervisor must verify PID start time/identity to avoid PID reuse.

`stream_probe.py` streamed 1,000,000 synthetic rows into 489 record batches of at most 2,048 rows, produced a 284 MB IPC file, and reopened its last page. Observed process max RSS was 92 MB, with a 5.9 MB increase while streaming. These measurements prove this simple data path can be bounded; they do not guarantee bounded memory for arbitrary SQL or warehouse fetch APIs.

`quack_probe.py` tested an unconventional local-server architecture. Three independent processes each inserted 1,000 disjoint rows into one owner DuckDB through localhost Quack, ending with 3,000 rows and 3,000 distinct values in about 0.53 seconds. This bypasses file-lock conflicts. Current docs call the protocol beta. `quack_cancel_probe.py` then falsified the assumption that killing the client cancels the server query: the query remained active through 1,008 ms after client termination. Server cancellation, dbt attachment, transactions, crash recovery, and installer tests are required before this becomes the release path. `interrupt_probe.py` verified direct DuckDB connection.interrupt() produced InterruptException in 1.5 ms and the connection remained reusable.

## What current compiler spikes do not establish

Root's 99/100-row DuckDB-to-SQLite fixture parity and 48-row structured aggregation parity establish those fixture paths. They do not establish warehouse-to-local fidelity, general nested types, decimal precision, temporal semantics, UDF availability, arbitrary Jinja introspection, or every SQLGlot dialect pair. SQLMesh classic-jaflle import does not establish hook/snapshot/incremental parity. Metabase's full-processor successful emissions and SQLite executions do not prove a supported standalone API, native-SQL translation, or easy redistribution. The frontend, target control model, installers, remote query cancellation, extraction consistency, and full browser→dbt→data→results flow remain unbuilt.

## Primary references

- dbt invocation/process guidance: https://docs.getdbt.com/reference/programmatic-invocations
- dbt immutable-state warning: https://docs.getdbt.com/reference/node-selection/state-comparison-caveats
- dbt per-run artifact paths: https://docs.getdbt.com/reference/global-configs/json-artifacts
- dbt log paths: https://docs.getdbt.com/reference/global-configs/logs
- DuckDB file concurrency: https://duckdb.org/docs/current/connect/concurrency.html
- DuckDB Quack server protocol: https://duckdb.org/docs/current/quack/overview.html
- Fulcro guide: https://book.fulcrologic.com/
- PyInstaller per-platform bundles: https://pyinstaller.org/en/stable/usage.html
