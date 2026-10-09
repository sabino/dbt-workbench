# Metabase compiler reuse spike

Pinned release: v0.64.1, commit 7010f2797355cb04f2bc2e82e747eaa86357a315.
Official JAR: https://downloads.metabase.com/v0.64.1/metabase.jar
SHA256: ffe600f532875b4d3c738effbc0f42f8a1ad7f8255cb875569f73b36645ef11d.
Runtime: Java 21.0.12.1, bundled Clojure 1.12.3.

## Reproduce

Keep compiler_spike.clj, bootstrap_no_appdb.clj, and run_spike.py together.
Run `python run_spike.py`. It downloads/verifies the pinned JAR if absent,
runs the JVM directly through clojure.main, and validates the emitted SQLite SQL
against an independently specified in-memory fixture. Python uses only its stdlib.
Internet downloads require the inherited session proxy; compilation needs no network.

## Results

42 query/mode/driver attempts: 38 SQL emissions, 4 expected unknown-field rejections.
For both PostgreSQL and SQLite, five structured cases compiled in each of three modes:
explicit projection/filter/limit; month aggregate; implicit projection; parameterized
string filter; left join. Both full preprocessing modes passed all five SQLite fixture
comparisons (10 executions), including null preservation through the left join.
PostgreSQL compilation was tested; PostgreSQL execution was not.

The bare driver compile path omitted a requested joined field; result execution
confirmed the missing column. It also emitted an invalid Unknown Field placeholder
instead of rejecting unknown metadata. Full QP preprocessing+compile preserved joined
projection and rejected the unknown field. This makes the full preprocessing boundary
material to correctness, rather than optional query cleanup.

Native SQL is passed through by both routes. Supplying PostgreSQL DATE_TRUNC SQL
under SQLite still emitted DATE_TRUNC and failed when executed. Metabase compilation
of MBQL therefore does not replace SQLGlot's existing-SQL translation path.

The full compiler adds a default 1048575-row limit to ordinary unbounded queries.
The full_compile_no_limit mode explicitly uses lib/disable-default-limit and still
passed the same fixture checks. Explicit user limits remained in force.

## Runtime coupling

Unmodified broad namespace loading eagerly constructs the application's main and
Quartz connection pools. With an unreachable PostgreSQL app DB those pools attempted
connections, logged warnings, and compilation still succeeded. Under default H2 config
that earlier exploratory run created an empty 12KiB app-db file.

The clean probe temporarily overrides the two internal pool factories *during namespace
load*, supplying a sentinel DataSource. Any getConnection call increments a counter
and throws. Both factories were intercepted; connection attempts were exactly zero.
The app-db metadata provider is separately replaced with a function that throws, while
a custom MetadataProvider supplies database/table/column/settings data in memory.
No Metabase server, migrations, tables, saved questions, users, permissions records,
or warehouse connection are used by the tested compile path.

This shows a viable compile-only extraction seam, and also shows that packaging the
unmodified app namespaces requires a bootstrap shim or upstream lifecycle refactor.
It does not establish a trimmed standalone library or public API compatibility promise.

Official JAR size: 711823361 bytes (678.85 MiB).
Measured complete cold process incl 42 attempts: 12.204 seconds.
Measured peak child RSS: 751548 KiB (733.9 MiB), with Xmx2g.
Per-case timing is in results.json; it is indicative only, not a controlled benchmark.

## Artifacts

- compiler_spike.clj: in-memory provider, synthetic IDs, cases, three compiler routes.
- bootstrap_no_appdb.clj: narrowly scoped lifecycle interception + forbidden DataSource.
- run_spike.py: pinned download, child runtime measurement, independent SQLite validation.
- results.json: full measured evidence and limitations.
- results.strict.json / compiler.strict.log: earlier successful run against deliberately
  unreachable app DB, with connection-pool startup still attempted.
- source/: selected upstream files at the pinned commit for audit.

Portable external name-based representation conversion and a real PostgreSQL server
were not tested. Initial harness-variable-shadowing failures in results.initial.json
are excluded from viability conclusions.
