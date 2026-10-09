# Fulcro client

The browser application uses Fulcro 3.10.0, React 18.3.1, and shadow-cljs 3.5.5.
Models and jobs use Fulcro normalized entity tables; local mutations manage editor,
selection, and result state. The client calls the same-origin JSON API directly.
Job status and durable log cursors are polled every 750 ms. Pathom is not required.

## Build

Install Node.js and Java 21 or newer, then run:

```sh
cd frontend
npm ci
npm run build
```

The release is written to `../src/dbt_workbench/static/js/main.js`. Released assets
are committed, so Python users do not need Node or Java to run the workbench.
Styles and the HTML entry point are in `src/dbt_workbench/static/`. The running UI
does not fetch fonts, scripts, or styles from external services.

In a network environment that requires an HTTP proxy, Java may need its own proxy
properties in addition to npm's proxy configuration. For example, set
`JAVA_TOOL_OPTIONS` with `-Dhttps.proxyHost=HOST -Dhttps.proxyPort=PORT` (and the HTTP
equivalents) before the first build. Maven downloads are cached in `~/.m2`.

`npm run dev` watches ClojureScript sources and rebuilds assets. Start the Python
server separately and open its URL so API calls remain same-origin.

## Workflow

Create the Jaffle Shop demo or open a local project directory. Build a baseline or
import an existing manifest directory, select a development target, and use
**Use baseline refs** to defer model references. Select a model to edit and save
its source, build it, or preview it. Source revisions prevent silent overwrites;
unsaved editor changes prompt before switching files or projects.

The **Query lab** has separate SQL and EDN source/parameter buffers. Query results
open in **Results**, with 100-row pages, SQL/input provenance, CSV downloads, and
saved-result comparison. The activity panel shows durable job logs and cancellation.
