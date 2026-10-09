# Local JSON API

The Clojure server serves the UI and API at `http://127.0.0.1:8787`.
Requests require a loopback Host and same-origin browser context.
Request bodies are limited to 2 MB and validated with Malli.

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Backend and engine capabilities |
| GET | `/api/projects` | Registered projects |
| POST | `/api/projects/demo` | Create or reuse the demo |
| POST | `/api/projects/open` | Register `{path, profiles_dir?}` |
| GET | `/api/projects/{id}` | Project, targets, baseline |
| GET | `/api/projects/{id}/models` | Models |
| GET | `/api/projects/{id}/catalog?target=dev` | Relations, columns, and types for Visual mode |
| GET | `/api/projects/{id}/files` | Editable paths |
| GET | `/api/projects/{id}/file?path=...` | Text and revision |
| PUT | `/api/projects/{id}/file` | Save `{path, content, expected_revision}` |
| GET | `/api/projects/{id}/changes` | Before/after edit history |
| POST | `/api/projects/{id}/query/convert` | Convert `{content, format, parameters?, target?, dialect?}` |
| POST | `/api/projects/{id}/baseline/import` | Import `{artifact_dir, target}` |
| GET/POST | `/api/projects/{id}/jobs` | List or submit jobs |
| GET | `/api/projects/{id}/results` | Saved results |
| GET | `/api/jobs/{id}` | Inputs, status, summary, errors |
| GET | `/api/jobs/{id}/events?after=0` | Ordered log events |
| POST | `/api/jobs/{id}/cancel` | Cancel queued or running work |
| GET | `/api/results/{id}` | Result metadata |
| GET | `/api/results/{id}/rows?offset=0&limit=100` | Captured row page |
| GET | `/api/results/{id}/download` | CSV download |

Submit jobs as `{kind, options}`. Kinds: `parse`, `build`, `test`, `baseline`, `preview`, `query`, `compare`.
Common options include `target`, `defer`, and `favor_state`.
Query options include `content`, `statements`, `format`, `dialect`, `parameters`, `editor_content`,
`selection`, `execution_scope`, `editor_mode`, `max_rows`, and `timeout_seconds`.
Visual jobs store HoneySQL EDN with `editor_mode: "visual"` and `format: "edn"`.
Preview uses `model_id`. Compare uses `left_result_id`, `right_result_id`, and optional `key_columns`.
Accepted jobs return HTTP 202. Poll status until `succeeded`, `failed`, `cancelled`, or `interrupted`.
Errors return `{detail}`. Conflicting saves return 409; invalid request fields return 422.
