# Local JSON API

The Clojure server serves the UI and API at `http://127.0.0.1:8787`.
Requests require a loopback Host and same-origin browser context.
Request bodies are limited to 2 MB and validated with Malli.

## Projects and inspection

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Backend and execution capabilities |
| GET | `/api/projects` | Registered projects |
| POST | `/api/projects/demo` | Create or reuse the demo |
| POST | `/api/projects/open` | Register `{path, profiles_dir?}` |
| GET | `/api/projects/{id}` | Project, targets, baseline |
| GET | `/api/projects/{id}/models` | Project models |
| GET | `/api/projects/{id}/catalog?target=dev` | Query-builder relation schema |
| GET | `/api/projects/{id}/catalog?detail=resources` | Manifest resource inventory and counts |
| GET | `/api/projects/{id}/lineage` | Manifest nodes and dependency edges |
| GET | `/api/projects/{id}/documentation?resource_id=...` | Resource documentation, schema, tests, code, freshness |
| GET | `/api/projects/{id}/runs/{job_id}` | Job, invocation artifacts, outcomes, test summary |
| GET | `/api/jobs/{id}/artifacts?run=...&name=...` | Download an existing artifact |
| GET | `/api/projects/{id}/files` | Editable paths |
| GET | `/api/projects/{id}/file?path=...` | Text and revision |
| PUT | `/api/projects/{id}/file` | Save `{path, content, expected_revision}` |
| GET | `/api/projects/{id}/changes` | Before/after edit history |
| POST | `/api/projects/{id}/baseline/import` | Import `{artifact_dir, target}` |

Documentation accepts `model_id` for compatibility. Missing resource documentation returns 404.
Absent artifacts produce explicit empty states. Source freshness comes from recorded `sources.json` results.

## Connections and routing

| Method | Route | Purpose |
| --- | --- | --- |
| GET/POST | `/api/projects/{id}/connections` | List or save interactive connections |
| GET | `/api/projects/{id}/connections/{connection_id}` | Read relation schema |
| POST | `/api/projects/{id}/connections/test` | Check `{id}` with SELECT |
| DELETE | `/api/projects/{id}/connections/{connection_id}` | Remove a saved connection reference |
| GET | `/api/projects/{id}/connections/cli` | List local Snow CLI configuration |
| POST | `/api/projects/{id}/connections/cli` | Import `{id, cli_connection}` by reference |
| GET/POST | `/api/projects/{id}/routing` | Read or save `{version, routes}` |
| POST | `/api/projects/{id}/routing/validate` | Validate model routes against real relations |

DuckDB saves an existing file path. Snowflake JDBC saves account fields and credential environment variable names.
Secret-reference fields are `password_env`, `token_env`, `private_key_file_env`, and `private_key_file_pwd_env`.
Snow CLI import saves its configured connection name, not credentials.
Profile targets remain managed in `profiles.yml`. Remove dependent model routes before deleting a saved connection.

## Git and GitHub

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{id}/git` | Local changes, commits, branches |
| GET | `/api/projects/{id}/git/diff?path=...&source=working` | Working, staged, or branch diff |
| POST | `/api/projects/{id}/git/stage` | Stage `{path}` |
| POST | `/api/projects/{id}/git/unstage` | Unstage `{path}` |
| POST | `/api/projects/{id}/git/commit` | Commit `{message}` locally |
| POST | `/api/projects/{id}/git/branch` | Create `{branch}` |
| POST | `/api/projects/{id}/git/switch` | Switch to `{branch}` |
| POST | `/api/projects/{id}/git/rebase` | Rebase onto local `{branch}` |
| POST | `/api/projects/{id}/git/cherry-pick` | Apply `{commit}` locally |
| GET | `/api/projects/{id}/github` | Detect github.com origin and CLI availability |
| GET | `/api/projects/{id}/github/pulls` | Read open pull requests |
| GET | `/api/projects/{id}/github/checks?number=...` | Read pull-request head checks |
| POST | `/api/projects/{id}/github` | Create an explicit draft with `{title, body, base}` |

Branch changes require a clean tree. Resolve or abort conflicted Git operations in your terminal.
Draft creation requires a clean named branch matching its published origin branch. No route pushes commits.

## Jobs and captured results

| Method | Route | Purpose |
| --- | --- | --- |
| POST | `/api/projects/{id}/query/convert` | Convert `{content, format, parameters?, target?, dialect?}` |
| GET/POST | `/api/projects/{id}/jobs` | List or submit jobs |
| GET | `/api/projects/{id}/results` | Saved results |
| GET | `/api/jobs/{id}` | Inputs, status, summary, errors |
| GET | `/api/jobs/{id}/events?after=0` | Ordered log events |
| POST | `/api/jobs/{id}/cancel` | Cancel queued or running work |
| GET | `/api/results/{id}` | Result metadata |
| GET | `/api/results/{id}/rows?offset=0&limit=100` | Captured row page |
| GET | `/api/results/{id}/download` | CSV download |

Submit `{kind, options}`. Kinds are `parse`, `build`, `test`, `baseline`, `preview`, `query`, and `compare`.
Common options include `target`, `defer`, and `favor_state`.
Query options include `content`, `statements`, `format`, `dialect`, `parameters`, `editor_content`,
`selection`, `execution_scope`, `editor_mode`, `max_rows`, and `timeout_seconds`.
Dialects are `duckdb`, `sqlite`, `snowflake`, and `federated`.
Historical Visual jobs retain `format: "edn"` and `editor_mode: "visual"`. Browser Visual mode is disabled.
Preview uses `model_id` and optional `baseline`.
Compare uses `left_result_id`, `right_result_id`, and optional `key_columns`.

Accepted jobs return 202. Poll until `succeeded`, `failed`, `cancelled`, or `interrupted`.
Errors return `{detail}`. Conflicting saves return 409; invalid request fields return 422.
Saved query drafts use browser storage and JSON export, not an API endpoint.
