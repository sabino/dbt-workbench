# Verification

Validated on macOS ARM64 on 2026-10-09, with Java, Clojure, Bun 1.4.2, native dbt 2.0.5, and headless Chromium.
The application runtime uses no Python. Dependency versions are pinned in `deps.edn` and `frontend/bun.lock`.

## Backend

`clojure -M:test` passed **16 tests and 96 assertions** against real dbt, DuckDB JDBC, SQLite, and HTTP requests.
The demo builds three seeds and five models and passes 20 dbt tests.

Coverage includes revision conflicts, durable edit history, path restrictions, HTTP validation and same-origin enforcement,
HoneySQL parameters, SQL/EDN execution roundtrips, CTEs, ordered joins, grouping, DISTINCT, CASE,
sequential queries, partial failures, cancellation, row limits, preview compilation, baseline deferral,
duplicate-preserving comparison, lexical parameter boundaries, demo YAML upgrades, catalog columns,
shared database attachments, nested staging-model previews and selected builds, selector fallback before parsing,
and interrupted-job recovery after restart.
Unsupported conversion nodes are rejected while the browser retains its draft.

## Browser

`bun run test:browser` passed **15 job workflows with zero uncaught browser errors**.
It runs an isolated temporary server and checks Build/Test, highlighting and line numbers,
Shift+Enter/F5, selection and cursor execution, editor focus, sequential results,
Visual filters, aggregation, and qualified joins, SQL/HoneySQL EDN conversion, parameter roundtrips, formatting preferences,
invalid draft retention, cross-engine comparison,
Load/Replay, saved-file Changes history, cancellation through the editor status bar,
query recovery, and history after reload.
The selected project survives refresh. **Choose project** clears that selection and opens the project chooser.
The header theme selector offers System, Light, and Dark, and preserves its setting across refresh.
Comparison appears only in Results, with baseline and candidate selectors, optional keys, and identical-result prevention.
The [comparison screen](workbench-results-compare.png) and [dark autocomplete](workbench-autocomplete-dark.png) show these controls.
Browser checks verify popup colors, theme overrides, system changes, project restoration, and explicit project switching.

The [IDE reference](workbench-ide-reference.png) guides dark surfaces, mint controls, compact tool groups, and workspace hierarchy.
The [light reference](workbench-design-reference.png) guides the light palette and workspace proportions.
The [rendered query workspace](workbench-query-lab.png) uses real query results with ten rows per page.
Parameters and execution dialect are under **Query settings**. Statement results appear as tabs above the grid.

Browser checks cover pagination, model search, Activity filtering, visible desktop pagination,
and horizontal overflow at 390, 768, 1024, 1280, and 1536 pixels.
The [dark theme](workbench-query-dark.png) and [mobile layout](workbench-query-mobile.png) use the same controls.
Body, secondary text, and primary button colors pass WCAG AA in both themes.

The [EDN editor](workbench-query-edn.png), [Visual builder](workbench-query-visual.png),
and [Changes history](workbench-changes.png) are captured from browser verification.
The frontend build compiles individual Phosphor icons through package ESM exports.
Frontend lint uses explicit Fulcro macro handling; compilation reports zero warnings.

## Commands

```sh
./scripts/install-dbt.sh
clojure -M:test
clj-kondo --lint src test
cd frontend
bun ci
bun run build
bun test src
bun run test:browser
```

Five frontend tests passed, covering statement boundaries, UTF-16 offsets, selections, empty input,
named-parameter and literal preservation during formatting, keyword case, and tab indentation.
The optimized frontend build completed without compilation warnings. Backend lint completed with zero errors and zero warnings.
Browser evidence is written to `/tmp/workbench-clojure-browser`.
CI is configured with the same build and test commands; a hosted CI run is separate evidence.

Legacy Arrow result rows require rerunning their stored queries after migration.
Baseline artifacts preserve dbt state rather than immutable source data.
