# Local API

The browser and Python API share one loopback server, normally
`http://127.0.0.1:8787`. Requests and responses use JSON. The OpenAPI document is
available at `/openapi.json`; `/docs` exposes FastAPI's interactive documentation.
Cross-origin browser requests are rejected. Profile contents are not exposed.

## Projects and files

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Version and available preview engines |
| GET | `/api/projects` | `{projects: [...]}` |
| POST | `/api/projects/demo` | Create or reopen the local Jaffle Shop demo |
| POST | `/api/projects/open` | Open `{path, profiles_dir?}` |
| GET | `/api/projects/{id}` | Project, target names, current baseline |
| GET | `/api/projects/{id}/files` | Editable SQL, YAML and EDN files |
| GET | `/api/projects/{id}/file?path=...` | File content and SHA-256 revision |
| PUT | `/api/projects/{id}/file` | Save `{path, content, expected_revision}` |
| GET | `/api/projects/{id}/models` | dbt model identities and dependencies |
| POST | `/api/projects/{id}/baseline/import` | Import `{artifact_dir, target}` |

Saves reject stale revisions with HTTP 409. Save and reference-import requests
also return 409 while the project has active jobs. Files stay inside the opened
project; profiles are excluded from the editor. Baseline imports copy manifest
artifacts, not database contents, and do not run dbt against the source target.

## Jobs

Submit `POST /api/projects/{id}/jobs` with a job kind and options:

```json
{
  "kind": "build",
  "options": {
    "target": "dev",
    "select": "orders",
    "defer": true,
    "favor_state": true
  }
}
```

The response is HTTP 202 with a durable job record. Supported kinds are `parse`,
`build`, `test`, `baseline`, `preview`, `query`, and `compare`. `baseline` performs
a full native build before registering its artifacts. `preview` takes `model_id`,
`target`, `dialect` (`duckdb` or `sqlite`), and optional `baseline`. Development
previews also accept the build's deferral options.

`query` accepts `content`, `format` (`sql` or `edn`), `parameters`, `target`, and
`dialect`. `max_rows` defaults to 10,000 and is limited to 100,000;
`timeout_seconds` defaults to 30 and is limited to 120. `compare` takes
`left_result_id`, `right_result_id`, and optional `key_columns`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{id}/jobs` | Project job history |
| GET | `/api/jobs/{id}` | Status, summary, error, result ID |
| GET | `/api/jobs/{id}/events?after=0` | Events after a durable sequence cursor |
| POST | `/api/jobs/{id}/cancel` | Request cancellation |

Statuses are `queued`, `running`, `cancelling`, `succeeded`, `failed`, `cancelled`,
or `interrupted`. Jobs in one project execute serially. A cancelled local query
is interrupted; cancellation of warehouse-side work is adapter-specific.

## Results

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/projects/{id}/results` | Immutable saved result metadata |
| GET | `/api/results/{id}` | Columns, SQL, provenance and summary |
| GET | `/api/results/{id}/rows?offset=0&limit=100` | A page of row arrays |
| GET | `/api/results/{id}/download` | CSV download |

Pages are limited to 1,000 rows. JSON encodes decimals as strings and dates as ISO
text. The full captured result is stored locally as Arrow IPC. Comparisons reject
truncated inputs; `summary.equal` reports captured row equality and
`summary.schema_equal` reports whether column names and types also agree.

Errors use `{detail: ...}`. Unknown objects return 404, invalid values return 400
or 422, and revision/busy conflicts return 409. Execution failures are persisted
on the job so they remain inspectable after restart.
