# Verification

Validated on macOS ARM64 on 2026-10-09 with Java, Clojure, Bun 1.4.2, native dbt 2.0.5, and headless Chromium.
The application runtime requires no Python. Dependency versions are pinned in `deps.edn` and `frontend/bun.lock`.

## Backend

`clojure -M:test` passed **52 tests and 411 assertions**, with zero failures or errors.
The suite uses real native dbt, DuckDB JDBC, SQLite, temporary Git repositories, and HTTP requests.
The demo builds three seeds and five models and passes 20 dbt tests.

Coverage includes file revision conflicts, durable edit history, path restrictions, and HTTP input validation.
Execution checks cover SQL/EDN conversion, parameters, ordered batches, partial failures, cancellation, row limits, preview compilation, and baseline deferral.
Result comparisons preserve duplicate counts and report schema differences and truncated inputs.

Inspection checks cover manifest resources, documentation, dependency graphs, recorded tests, run comparisons, and downloadable artifacts.
Connection checks cover registration, credential references, CLI import, read-only routing validation, and local schema discovery.
Federation checks exercise typed captures, decimal and timestamp handling, nulls, duplicates, aliases, unsupported types, and bounded inputs.
Git checks exercise local staging, commits, branches, diffs, safe arguments, repository-root restrictions, and disabled hooks.

Snow CLI unit tests cover read-only SQL validation, literal parameter rendering, bounded subprocess output, timeout, and cancellation.
A live check through the workbench adapter returned **one row with value 42** from a bounded, parameterized SELECT.
The check used existing CLI authentication and a temporary project. Account details and connection metadata were excluded from repository artifacts.

Live Snowflake JDBC, remote federation, GitHub publication, and server-side CLI cancellation remain unverified.
The CLI check establishes SELECT execution through that adapter, not those separate capabilities.

## Frontend

`bun test src` passed **9 tests and 23 assertions**.
Tests cover statement boundaries, cursor and selection execution, empty input, formatting, chart aggregation, query-library validation, and lineage layout.
The optimized frontend build completed with **zero compilation warnings**.
The build tool emitted its JVM dependency's `sun.misc.Unsafe` deprecation notice.
Backend lint completed with zero errors and warnings.
Frontend lint completed with zero errors and warnings using explicit Fulcro macro handling.

## Browser

`bun run test:browser` passed **17 job workflows with zero uncaught browser errors**.
It starts an isolated temporary server and removes its project data afterward.

Query checks cover Build/Test, highlighting, completion, line numbers, Shift+Enter/F5, selection, cursor execution, and editor focus.
They also cover sequential results, pagination, visual filters and joins, SQL/EDN conversions, parameters, formatting, failed drafts, and cancellation.
A local federated join across development and baseline connections returned the expected 100 matching customers.
Saved queries were reopened and exported through the UI.
A delayed file response check verifies that the last selection wins.
File editing pauses during loading while Query lab remains editable.

Comparison uses the current executed result as its left input. The user chooses one other saved result.
Compare controls appear only under Results. The browser verifies matched rows and prevents comparing a result with itself.
Activity retains query inputs for Load/Replay. Changes retains before/after file content for draft restoration.

Layout checks verify the following:

- Query lab remains mounted while project tools open between left navigation and the query pane.
- Files use one right-hand explorer with Models/All files views, file tabs, preserved drafts, and undo.
- Results, Lineage, Activity, Changes, and Runs & tests remain available below Query lab.

Resizing checks exercise pointer dragging and arrow keys.
Navigation collapse, explorer visibility, file views, pane widths, and vertical splits survive reload.
Reset layout restores defaults. The fixed global status bar remains at the viewport bottom while scrolling.
Project and theme choices persist. Choose project clears the stored selection and reopens the chooser.

Browser workflows also inspect documentation, lineage dependencies, dbt artifacts, local connection health, schema discovery, and routing overrides.
A temporary Git repository exercises side-by-side diffs, staging, unstaging, committing, branch creation, and branch switching.
Overview displays recorded tests, local source changes, recent jobs, and model impact.
Chart checks cover multiple metrics, Count distinct, line plots, and legend controls.

Screenshots and geometry checks cover widths of 390, 768, 1024, 1280, and 1536 pixels.
They verify horizontal overflow, desktop pagination, autocomplete colors, and saved-result popovers staying inside the viewport.
Popover checks also verify hit testing, Escape dismissal, and closed menus being hidden.

## Visual evidence

The supplied [IDE reference](workbench-ide-reference.png) guides dark surfaces, mint controls, and compact tool groups.
The [light reference](workbench-design-reference.png) guides the light palette.
Fresh browser captures include the [dark workspace](workbench-query-dark.png), [mobile layout](workbench-query-mobile.png),
[Visual builder](workbench-query-visual.png), [comparison](workbench-results-compare.png),
[autocomplete](workbench-autocomplete-dark.png), and [Changes history](workbench-changes.png).
The [file viewer](workbench-files.png), [lineage](workbench-lineage.png), [Overview](workbench-overview.png),
[local Git](workbench-git.png), [charts](workbench-charts.png), and [history popover](workbench-history-menu.png) show the pane arrangement.

## Commands

```sh
clojure -M:test
clj-kondo --lint src test
cd frontend
bun ci
bun run build
bun test src
bun run test:browser
```

Frontend lint uses:

```sh
clj-kondo --lint frontend/src/workbench/core.cljs frontend/src/workbench/builder.cljs --config '{:lint-as {com.fulcrologic.fulcro.components/defsc clojure.core/defn com.fulcrologic.fulcro.mutations/defmutation clojure.core/defn} :config-in-call {com.fulcrologic.fulcro.mutations/defmutation {:linters {:unresolved-symbol {:level :off} :unused-binding {:level :off}}}}}'
```

Browser evidence is written to `/tmp/workbench-clojure-browser`.
CI uses the same build and test commands. A hosted CI run remains separate evidence.
Baseline artifacts preserve compilation state, not immutable source rows.
Legacy Arrow result rows require rerunning their stored queries through JDBC.
