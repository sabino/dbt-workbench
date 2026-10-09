# Workbench frontend

ClojureScript/Fulcro provides project workflows and durable job views.
CodeMirror provides the SQL/EDN editor and statement selection.

Requires Java and Bun 1.4.2:

```sh
bun ci
bun run build
bun test src
bun run test:browser
```

Build output goes to `../resources/static/js`. Assets are committed for users running the Clojure server.
`bun run dev` watches sources. Restarting the server is unnecessary for static asset changes.
Browser tests use a temporary Clojure server and save screenshots under `/tmp/workbench-clojure-browser`.
