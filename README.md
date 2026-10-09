# dbt Workbench

A local dbt editor and query lab built with Clojure, ClojureScript, HoneySQL, and JDBC.
Native dbt 2.0.5 handles project parsing, compilation, builds, and tests. The application requires no Python.

## Start

Install Java 21+ and the [Clojure CLI](https://clojure.org/guides/install_clojure).
Install the pinned native dbt binary, then start the server:

```sh
./scripts/install-dbt.sh
./scripts/run.sh
```

Open **http://127.0.0.1:8787**. Compiled browser assets ship in the repository.
The installer supports macOS and Linux on ARM64 and x86_64. It verifies the release checksum.
Choose **Create Jaffle demo**, then **Build**. The first demo build prepares baseline and development databases.
**Test** runs tests alone. **Build options** controls model scope, deferral, and baseline capture.

Use **Shift+Enter** or **F5** to run selected SQL or the statement around the cursor.
Results stay below the query editor. The global status bar remains visible across workspace views.
Collapse the left navigation to icons when you need more workspace.
Project views open between navigation and Query lab.
Results, Lineage, Activity, Changes, and Runs & tests occupy tabs below the query editor.
Query lab opens project files in tabs beside a scratch query.
The right Files pane contains the explorer.
**Models / All files** filters one tree. File navigation replaces the duplicate left file list.

| Workflow | Where |
| --- | --- |
| Edit multiple project files with conflict checks | Query lab > File tabs |
| Run SQL or HoneySQL EDN queries | Query lab |
| Inspect rows, charts, SQL, and provenance | Results |
| Compare the current result with a saved result | Results > Compare |
| Save, reopen, or export query drafts | Query lab > Saved queries |
| Explore dependencies and downstream impact | Below Query lab > Lineage |
| Browse models, sources, macros, tests, and other manifest resources | Project view > Documentation |
| Inspect dbt outcomes, timing, artifacts, and previous-run summaries | Below Query lab > Runs & tests |
| Stage, commit, switch, rebase, or cherry-pick locally | Project view > Version control |
| Inspect draft fields, references, configuration, and recorded dependencies | Project view > Metadata |
| Read GitHub pull requests and checks, or explicitly create a draft | Version control > Pull requests |
| Register connections and override model destinations | Project views > Connections and Environment routing |
| Inspect executed inputs or reload saved edits | Below Query lab > Activity and Changes |

## Query lab

CodeMirror provides highlighting, completion, bracket matching, line numbers, and undo.
**Run all** executes statements in order and stops at the first error.
Semicolons inside strings and comments do not split statements.
Query lab stays mounted when you switch project views or lower tabs.
Drag pane dividers or use their arrow keys. Double-click a divider to restore its default size.
Widths, vertical splits, navigation collapse, and explorer visibility survive reload in the same browser.
**Reset layout** restores all pane preferences. Query execution preserves editor focus.

**SQL / EDN** selects highlighting and execution mode. Switching modes preserves document text and undo history.
**Visual** is disabled. Text files also offer **Text** mode.
Each file tab retains its draft, selection, and editor mode until closed.
**Query 1** is a scratch document. Loading saved queries or Activity inputs opens it while preserving file drafts.
Use **Save file** to write a project file. **Preview model** compiles a saved dbt model before executing its SELECT.

**Metadata**, below Version control, follows the active file.
It infers static SQL/Jinja references, configuration, and outer SELECT field names from the draft.
JSON files expose top-level value types. Other files show path, type, and size details.
Recorded dbt artifacts supply columns, types, tests, tags, configuration, upstream resources, and downstream resources.
Draft inference and recorded artifacts appear separately. Dynamic templates and wildcards leave schema details unknown.

EDN represents queries as HoneySQL data:

```clojure
{:select [:category [[:sum :amount] :total]]
 :from [:orders]
 :where [:= :status [:param :status]]
 :group-by [:category]}
```

Enter parameter values separately as JSON. Multiple EDN statements use a vector of query maps.
**Format** formats SQL or EDN. **Style** saves formatting preferences in browser storage.
Saved queries also live in browser storage, separately for each project.
Export downloads query content, parameters, target, and dialect as JSON.

Results provide a captured row grid, chart controls, CSV export, and SQL with provenance.
Charts support multiple metrics, breakdown categories, Sum, Average, Count rows, and Count distinct.
Bar and line plots include legend and point controls.
Charts sample the first 1,000 saved rows, with up to 40 groups and eight rendered series.
**Compare** uses the current result as its left input. Choose another saved result, including a captured baseline preview.
Comparisons preserve duplicate counts. Truncated inputs cannot establish complete equality.
Activity retains executed statements, draft text, scope, parameters, targets, timings, and statement result IDs.
**Replay** restores inputs; changed source data can change the output.

## Projects and connections

**Open local project** accepts a directory containing `dbt_project.yml` and an optional profiles directory.
Files are edited in place. Revision checks prevent conflicting saves.
Multiple file tabs in the right pane retain drafts. Changes stores before/after text; loading a version creates a draft.

Documentation reads real manifest and catalog artifacts. Source freshness appears only when `sources.json` contains a result.
Overview shows resource counts, recent jobs, recorded failed tests, local source changes, and selected-model dependency impact.
Runs exposes recorded outcomes and downloadable artifacts, including failed dbt runs.
Run comparison compares model/test outcomes and timing. Result comparison compares captured rows.

Interactive connections support DuckDB and Snowflake JDBC.
Saved Snowflake credentials reference environment variable names; password, OAuth, and private-key authentication are supported.
Connections can also import a locally configured Snow CLI connection by name without copying credentials.
The CLI adapter runs bounded SELECT/WITH queries. Its types are inferred from returned JSON.

**Federated** queries copy registered DuckDB and Snowflake JDBC relations into isolated DuckDB, then run the query locally.
Use `connection.schema.table`, or saved model routes. Captures allow 50,000 rows per relation and 16 relations per query.
Snow CLI connections cannot supply federation inputs. See [execution limits](docs/limitations.md) before relying on cross-engine results.

Native dbt builds use profile targets and can modify their databases.
Interactive saved connections do not create dbt profile targets.
Set `DBT_WORKBENCH_DBT=/absolute/path/to/dbt` to choose another executable.
Deferral requires saved artifacts and access to their referenced relations.
Baseline artifacts preserve compilation state; they do not snapshot database rows.

Git operations act on the local project repository. Resolve rebase or cherry-pick conflicts in your terminal.
GitHub integration requires a github.com origin and authenticated GitHub CLI.
Remote reads happen when requested. Draft creation is explicit and requires a branch you have already published.
The workbench does not push branches automatically.

## Persistence

Projects, jobs, events, changes, connection references, routing, and captured JSON rows live under
`~/.local/share/dbt-workbench`, or `$XDG_DATA_HOME/dbt-workbench`.
Saved queries, layout, theme, project selection, and formatting preferences remain in browser storage.

```sh
./scripts/run.sh --port 8787 --data-dir /path/to/workbench-data
```

`DBT_WORKBENCH_DATA_DIR` also selects storage. Stop with Ctrl+C.
Existing registries and job metadata are retained.
Bundled demo YAML compatibility upgrades appear in Changes. SQL edits are preserved.
Legacy Arrow rows require rerunning their saved queries through JDBC.

## Development

Frontend rebuilds require Bun 1.4.2 and Java:

```sh
cd frontend
bun ci
bun run build
bun test src
bun run test:browser
```

The build writes to `resources/static/js`. Browser tests use an isolated temporary server.
Install Chromium with `bun node_modules/playwright/cli.js install chromium` when needed.
Run backend checks from the repository root with `clojure -M:test`.

See [architecture](docs/architecture.md), [API](docs/api.md), [limits](docs/limitations.md),
[extension points](docs/roadmap.md), and [verification](docs/verification.md).
External-service support and verified external execution are separate claims. Consult the verification record before relying on either.
Historical [workbench spikes](workbench-spikes/README.md) remain separate from the application runtime.

The application is [MIT licensed](LICENSE). Browser licenses appear in [third-party notices](THIRD_PARTY_NOTICES.md).
The demo retains its [Apache-2.0 license](resources/demo/LICENSE) and [source attribution](resources/demo/PROVENANCE.json).
