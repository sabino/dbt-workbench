# Verification

Validated on macOS ARM64 on 2026-10-09 with Java, Clojure, Bun 1.4.2, native dbt 2.0.5, and isolated Linux Chromium.
The application runtime requires no Python. Dependency versions are pinned in `deps.edn` and `frontend/bun.lock`.

## Backend

`clojure -M:test` passed **83 tests and 644 assertions**, with zero failures or errors.
The suite uses real native dbt, DuckDB JDBC, SQLite, temporary Git repositories, and HTTP requests.
The demo builds three seeds and five models and passes 20 dbt tests.

Coverage includes file revision conflicts, durable edit history, path restrictions, and HTTP input validation.
Text previews include CSV, scripts, and extensionless files. Binary, invalid UTF-8, and oversized files remain read-only.
Execution checks cover SQL/EDN conversion, parameters, ordered batches, partial failures, cancellation, row limits, preview compilation, and baseline deferral.
Result comparisons preserve duplicate counts and report schema differences and truncated inputs.

Inspection checks cover manifest resources, documentation, dependency graphs, recorded tests, run comparisons, and downloadable artifacts.
Connection checks cover registration, credential references, CLI import, read-only routing validation, and local schema discovery.
Editor-context tests cover local declaration fallback, manifest/catalog merging, resource paths, and known field metadata.
Federation checks exercise typed captures, decimal and timestamp handling, nulls, duplicates, aliases, unsupported types, and bounded inputs.
Git checks exercise local staging, commits, branches, diffs, safe arguments, repository-root restrictions, and disabled hooks.

Snow CLI unit tests cover read-only SQL validation, literal parameter rendering, bounded subprocess output, timeout, and cancellation.
A live check through the workbench adapter returned **one row with value 42** from a bounded, parameterized SELECT.
The check used existing CLI authentication and a temporary project. Account details and connection metadata were excluded from repository artifacts.

Live Snowflake JDBC, remote federation, GitHub publication, and server-side CLI cancellation remain unverified.
The CLI check establishes SELECT execution through that adapter, not those separate capabilities.

## Frontend

`bun test src` passed **78 tests and 497 assertions**.
Tests cover statement boundaries, cursor and selection execution, empty input, formatting, chart aggregation, query-library validation, and lineage layout.
Metadata tests cover SQL/Jinja lexical boundaries, outer projections, literal configuration, dynamic expressions, incomplete drafts, and JSON value types.
Editor tests cover exact SQL restoration, conversion caches, stale responses, native file formats, and dirty-state calculation.
Command tests cover search, enabled actions, key assignments, conflicts, reserved keys, and persisted overrides.
Editor assistance tests cover SQL aliases, literal dbt references, macros, HoneySQL fields, comments, ambiguity, and conservative CTE handling.
Structural tests cover navigation, selection, wrapping, slurp/barf, per-action undo, read-only boundaries, and Parinfer transaction filters.
The optimized frontend build completed with **zero compilation warnings**.
The build tool emitted its JVM dependency's `sun.misc.Unsafe` deprecation notice.
Backend lint completed with zero errors and warnings.
Frontend lint completed with zero errors and warnings using explicit Fulcro macro handling.

## Browser

`bun run test:browser` passed **21 job workflows** plus `verifyEditorIntelligence`, with **zero uncaught browser errors**.
It starts an isolated temporary server and removes its project data afterward.
The browser ran through CDP in a hidden Linux workspace. Scoped loopback relays connected it to the temporary macOS server.

Query checks cover Build/Test, highlighting, completion, line numbers, Shift+Enter/F5, selection, cursor execution, and editor focus.
They also cover sequential results, pagination, parameters, formatting, failed drafts, and cancellation.
SQL and EDN execute directly from file tabs. SQL-to-EDN shows real HoneySQL data.
Returning from untouched EDN restores exact SQL, parameters, dirty state, and undo history.
Editor workflows cover new tabs, dirty marks, path validation, native-format saves, and execution after reopening.
They check precise EDN formatting, invalid drafts, delayed conversions, optional automatic formatting, and retained file focus.
Project-aware editor checks accept SQL alias-field completion and inspect its documentation.
They cover local macro completion, typed argument documentation, F12, literal `ref`/`source` arguments, Ctrl-click references, and official dbt help links.
EDN checks accept alias fields and inspect documentation. Rainbow delimiters render in both themes.
Structural checks cover selection, slurp/barf, individual undo, and Parinfer typing with undo.
Changing editor settings preserves text and history. Options and source text persist across reload.
Documentation remains within the viewport at 390 and 1536 pixels. Editor popovers dismiss on a second trigger click.

Keyboard checks cover file and command search, modal buttons, Tab navigation, shortcut assignment, and persistence after reload.
Closing the command center preserves focus moved to the editor before its queued close event.
Visual mode remains disabled. Markdown and JSON files open alongside SQL files in the central editor.
CSV, Python, and extensionless files retain text across mode switches. Binary files open in read-only tabs with metadata.
A local federated join across development and baseline connections returned the expected 100 matching customers.
Saved queries were reopened and exported through the UI.
A delayed file response check verifies that the last selection wins.
The active editor pauses during loading. Switching back to a loaded tab cancels the pending selection.
A delayed result response check verifies that background restoration preserves the selected lower pane.
Selecting restored Activity records retains their details instead of opening Results automatically.

Comparison uses the current executed result as its left input. The user chooses one other saved result.
Compare controls appear only under Results. The browser verifies matched rows and prevents comparing a result with itself.
Activity retains query inputs for Load/Replay. Changes retains before/after file content for draft restoration.

Layout checks verify the following:

- Query lab remains mounted while project tools open between left navigation and the query pane.
- Files use one right-hand explorer with Models/All files views. File tabs, preserved drafts, and undo live in Query lab.
- Results, Lineage, Activity, Changes, and Runs & tests remain available below Query lab.

Resizing checks exercise pointer dragging and arrow keys.
Navigation collapse, explorer visibility, file views, pane widths, and vertical splits survive reload.
Reset layout restores defaults. The fixed global status bar remains at the viewport bottom while scrolling.
Project and theme choices persist. Choose project clears the stored selection and reopens the chooser.

Browser workflows also inspect documentation, lineage dependencies, dbt artifacts, local connection health, schema discovery, and routing overrides.
Metadata follows the active file and separates inferred unsaved fields from recorded columns.
Inspecting a reference preserves the active draft. JSON configuration exposes top-level value types.
A temporary Git repository exercises side-by-side diffs, staging, unstaging, committing, branch creation, and branch switching.
Overview displays recorded tests, local source changes, recent jobs, and model impact.
Chart checks cover multiple metrics, Count distinct, line plots, and legend controls.

Screenshots and geometry checks cover widths of 390, 768, 1024, 1280, and 1536 pixels.
They verify horizontal overflow, desktop pagination, autocomplete colors, and saved-result popovers staying inside the viewport.
Popover checks also verify hit testing, Escape dismissal, and closed menus being hidden.

An isolated Linux Chromium workspace exercised the bundled Jaffle Shop demo against a separate local macOS server.
Manual checks covered file editing and saving, Build/Test, model previews, metadata dependencies, lineage, pane resizing, and result comparison.
Two complete 100-row previews matched. The captures contain demo data and recorded job outcomes.
The tabbed editor capture shows a completed 62-row customer purchase query on the running local demo.

## Visual evidence

The supplied [IDE reference](workbench-ide-reference.png) guides dark surfaces, mint controls, and compact tool groups.
The [light reference](workbench-design-reference.png) guides the light palette.
The [screenshot guide](screenshots.md) explains the Linux browser captures and their workflows.
Browser captures include the [dark workspace](workbench-query-dark.png), [mobile layout](workbench-query-mobile.png),
[metadata parser](workbench-metadata.png), [recorded dependencies](workbench-metadata-dependencies.png), [comparison](workbench-results-compare.png),
[EDN representation](workbench-query-edn.png), [command center](workbench-command-center.png), [shortcut settings](workbench-keyboard-shortcuts.png),
[autocomplete](workbench-autocomplete-dark.png), and [Changes history](workbench-changes.png).
Project-aware editor captures show [SQL reference help](workbench-sql-reference.png), [dark EDN reference help](workbench-edn-reference.png),
and [light EDN reference help](workbench-edn-reference-light.png).
The [central file editor](workbench-files.png), [lineage](workbench-lineage.png), [Overview](workbench-overview.png),
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
clj-kondo --lint frontend/src/workbench/core.cljs --config '{:lint-as {com.fulcrologic.fulcro.components/defsc clojure.core/defn com.fulcrologic.fulcro.mutations/defmutation clojure.core/defn} :config-in-call {com.fulcrologic.fulcro.mutations/defmutation {:linters {:unresolved-symbol {:level :off} :unused-binding {:level :off}}}}}'
```

Browser evidence is written to `/tmp/workbench-clojure-browser`.
CI uses the same build and test commands. A hosted CI run remains separate evidence.
Baseline artifacts preserve compilation state, not immutable source rows.
Legacy Arrow result rows require rerunning their stored queries through JDBC.
