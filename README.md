# dbt Workbench

A local application for editing dbt projects, building against named targets, reusing upstream relations through dbt deferral, and inspecting SQL and query results. The interface uses Fulcro; Python runs dbt, SQLGlot, DuckDB, and SQLite.

This first version includes a runnable classic Jaffle Shop demo. Native dbt jobs use the adapters installed in your dbt environment. Interactive previews currently read local DuckDB targets and execute in DuckDB or SQLite. SQLGlot dialect support does not imply that every database combination is executable or equivalent.

![Local workbench](docs/workbench.png)

## Start

Requires Python 3.11–3.13 and [uv](https://docs.astral.sh/uv/).

```sh
uv sync
uv run dbt-workbench
```

Open **http://127.0.0.1:8787**. Compiled frontend assets are included; Node.js and Java are only needed to rebuild the interface.

The server binds to loopback. Projects, jobs, events, results, and baseline artifacts persist under `~/.local/share/dbt-workbench`, or `$XDG_DATA_HOME/dbt-workbench` when configured.

```sh
uv run dbt-workbench --host 127.0.0.1 --port 8787 --data-dir /path/to/workbench-data
```

`DBT_WORKBENCH_DATA_DIR` also selects the data directory. Stop the server with Ctrl+C; restarting with the same directory restores saved workbench state.

## Try Jaffle Shop

1. Choose **Create Jaffle demo**, then **Build baseline**. This builds the demo's seeds and models and runs its dbt tests.
2. Select `customers`, open its SQL, and change `customer_payments.total_amount as customer_lifetime_value` to `customer_payments.total_amount * 2 as customer_lifetime_value`. Save the file.
3. Keep target **dev**, enable **Use baseline refs**, and choose **Build selected**. **Prefer baseline relations** adds dbt's `--favor-state` behavior when a development relation already exists.
4. Choose **Preview**. Inspect its rows, compiled SQL, and relation bindings. The query lab also accepts SQL or an EDN query; choose DuckDB or SQLite for execution. Its default query uses `orders`: switch the target to **baseline** to query the built baseline, or build `orders` in **dev** before running it there. Building only `customers` does not create the separate `orders` model in development.
5. Use job history to inspect logs or cancel running work. Compare saved results using the comparison selectors and optional key columns.

The demo is copied into the application data directory. Its `baseline` and `dev` targets use separate DuckDB files; development attaches the baseline read-only. Baseline **artifacts** are immutable. Building the baseline again can change its database tables; an artifact manifest is not a data snapshot.

## Open a project

Choose **Open local project** and supply a directory containing `dbt_project.yml`. You can also specify its profiles directory. Otherwise the workbench uses a project-local `profiles.yml`, then `~/.dbt`.

Files are edited in place, with revision checks that reject conflicting saves. Finish or cancel active project jobs before saving files or importing baseline artifacts through the workbench. Install the project's dbt packages and required adapter in your dbt environment. Set `DBT_WORKBENCH_DBT` to an alternate dbt executable when needed:

```sh
DBT_WORKBENCH_DBT=/path/to/dbt-env/bin/dbt uv run dbt-workbench
```

Native dbt builds can contact and modify the selected target. Use your development target for iteration. Deferral requires a successful saved baseline and database access to its referenced relations. Cross-database data transfer is not supplied automatically.

You can register existing dbt artifacts without rebuilding their source target. Expand **Import an existing reference manifest**, enter its local artifact directory and **Reference target** name, and choose **Import state**. Your selected development target stays selected. The API supports the same operation:

```sh
curl -X POST http://127.0.0.1:8787/api/projects/PROJECT_ID/baseline/import \
  -H 'Content-Type: application/json' \
  --data '{"artifact_dir":"/absolute/path/to/dbt/target","target":"prod"}'
```

Use artifacts from a trusted, successful build. The directory must contain `manifest.json`; baseline SQL previews also need compiled model SQL. This copies artifacts into the workbench, not database rows. The local API schema is available at `http://127.0.0.1:8787/docs`.

## Queries as readable data

The EDN query format identifies dbt models rather than embedding physical relation names. For example:

```clojure
{:query/version 1
 :from {:model "model.jaffle_shop.orders" :as "o"}
 :select [{:as "customer_id" :expr [:field "o" "customer_id"]}
          {:as "revenue" :expr [:sum [:field "o" "amount"]]}]
 :where [:eq [:field "o" "status"] [:param "status" :text]]
 :group-by [[:field "o" "customer_id"]]
 :order-by [[:field "o" "customer_id"]]
 :limit 100}
```

Enter parameters separately as JSON:

```json
{"status": "completed"}
```

This example reads `orders` from the selected target. Use target **baseline** after the demo baseline build, or first build `orders` in **dev**. You can also add `:binding :baseline` inside `:from` to select the baseline relation explicitly.

The supported operator set is deliberately bounded. Structured division has floating-point, NULL-on-zero semantics. Raw SQL and cross-engine previews have stricter checks; unsupported queries fail with a diagnostic instead of an unchecked approximation. See [architecture](docs/architecture.md), [API](docs/api.md), [limits](docs/limitations.md), and the [continuation roadmap](docs/roadmap.md).

## Development

```sh
uv sync --extra test
uv run pytest
```

The browser test starts an isolated temporary workbench and drives the real UI:

```sh
uv run playwright install chromium
uv run python tests/browser_smoke.py --output /tmp/workbench-browser
```

See [verification notes](docs/verification.md) for the checked workflows and
their scope.

Build an installable wheel and source archive with `uv build`. GitHub Actions
runs the API/integration suite, browser workflow, and packaging checks when this
repository is pushed to GitHub.

To rebuild the Fulcro interface, install Node.js and a JDK, then run:

```sh
cd frontend
npm ci
npm run build
```

The frontend build writes assets into `src/dbt_workbench/static/js`. Core engine dependencies are pinned in `pyproject.toml` and resolved in `uv.lock`: dbt Core 1.12.5, dbt-duckdb 1.11.0, SQLGlot 30.21.0, and DuckDB 1.5.6.

The application is [MIT licensed](LICENSE). Bundled browser dependencies retain their licenses in [third-party notices](THIRD_PARTY_NOTICES.md). The vendored classic Jaffle Shop demo retains its [Apache-2.0 license](src/dbt_workbench/demo/LICENSE) and [source attribution](src/dbt_workbench/demo/PROVENANCE.json).

## Feasibility evidence

The original experiments and recorded results are preserved in
[`workbench-spikes/`](workbench-spikes/README.md), with a SHA-256 evidence index.
They document the choices and limits behind this application; their historical
results are separate from the application verification above.

See [import provenance](docs/import-provenance.md) for the supplied archive
checksums and the repository layout.
