"""Full local workflows against the HTTP API, real dbt, DuckDB and SQLite.

No mocks or warehouse credentials are used. Run with pytest -m integration.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
from pathlib import Path
import time

import pytest
from fastapi.testclient import TestClient

from dbt_workbench.app import create_app


pytestmark = pytest.mark.integration
TERMINAL = {"succeeded", "failed", "cancelled", "interrupted"}


def get_json(client: TestClient, path: str, **kwargs):
    response = client.get(path, **kwargs)
    assert response.status_code == 200, response.text
    return response.json()


def submit(client: TestClient, project_id: str, kind: str, **options):
    response = client.post(
        f"/api/projects/{project_id}/jobs", json={"kind": kind, "options": options}
    )
    assert response.status_code == 202, response.text
    return response.json()


def wait_job(client: TestClient, job_id: str, *, timeout: float = 120, expected="succeeded"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = get_json(client, f"/api/jobs/{job_id}")
        if job["status"] in TERMINAL:
            events = get_json(client, f"/api/jobs/{job_id}/events")["events"]
            assert job["status"] == expected, {"job": job, "events": events[-15:]}
            return job
        time.sleep(0.1)
    client.post(f"/api/jobs/{job_id}/cancel")
    pytest.fail(f"Job {job_id} exceeded {timeout}s: {job}")


def run_job(client: TestClient, project_id: str, kind: str, **options):
    return wait_job(client, submit(client, project_id, kind, **options)["id"])


def page(client: TestClient, job: dict):
    assert job["result_id"], job
    return get_json(client, f"/api/results/{job['result_id']}/rows", params={"limit": 1000})


def make_demo(client: TestClient):
    response = client.post("/api/projects/demo")
    assert response.status_code == 201, response.text
    project = response.json()
    assert client.post("/api/projects/demo").json()["id"] == project["id"]
    return project


EDN = """{:query/version 1
 :from {:model "model.jaffle_shop.orders" :as "o" :binding :baseline}
 :select [{:as "customer_id" :expr [:field "o" "customer_id"]}
          {:as "revenue" :expr [:sum [:field "o" "amount"]]}]
 :where [:eq [:field "o" "status"] [:param "status" :text]]
 :group-by [[:field "o" "customer_id"]]
 :order-by [[:field "o" "customer_id"]]}"""


def test_complete_local_iteration_survives_restart(tmp_path: Path):
    """Import → baseline → edit → defer → preview → portable EDN → compare."""
    data_dir = tmp_path / "workbench"
    with TestClient(create_app(data_dir)) as client:
        assert get_json(client, "/api/health")["status"] == "ok"
        assert client.get("/").status_code == 200
        project = make_demo(client)
        project_id = project["id"]
        detail = get_json(client, f"/api/projects/{project_id}")
        assert set(detail["targets"]) == {"baseline", "dev"}
        assert detail["baseline"] is None
        files = get_json(client, f"/api/projects/{project_id}/files")["files"]
        assert "models/orders.sql" in {f["path"] for f in files}
        assert all(f["path"] != "profiles.yml" for f in files)

        baseline_job = run_job(client, project_id, "baseline", target="baseline")
        baseline = get_json(client, f"/api/projects/{project_id}")["baseline"]
        baseline_manifest = Path(baseline["manifest_path"])
        immutable_hash = hashlib.sha256(baseline_manifest.read_bytes()).hexdigest()
        native_results = json.loads((Path(baseline["artifact_dir"]) / "run_results.json").read_text())
        assert len(native_results["results"]) == 28
        assert all(r["status"] in {"success", "pass"} for r in native_results["results"])
        models = get_json(client, f"/api/projects/{project_id}/models")["models"]
        assert {m["name"] for m in models} == {
            "orders", "customers", "stg_orders", "stg_customers", "stg_payments"
        }
        orders = next(m for m in models if m["name"] == "orders")
        assert "model.jaffle_shop.stg_orders" in orders["depends_on"]

        before = run_job(client, project_id, "preview", model_id="model.jaffle_shop.orders",
                         target="baseline", baseline=True, dialect="duckdb")
        before_page = page(client, before)
        assert before_page["row_count"] == 99
        columns = [c["name"] for c in before_page["columns"]]
        status_index = columns.index("status")
        completed_rows = [row for row in before_page["rows"] if row[status_index] == "completed"]
        assert 0 < len(completed_rows) < 99

        source = get_json(client, f"/api/projects/{project_id}/file", params={"path": "models/orders.sql"})
        assert source["content"].count("select * from final") == 1
        edited = source["content"].replace("select * from final", "select * from final where status = 'completed'")
        saved_response = client.put(f"/api/projects/{project_id}/file", json={
            "path": source["path"], "content": edited, "expected_revision": source["revision"]
        })
        assert saved_response.status_code == 200, saved_response.text
        saved = saved_response.json()
        assert saved["revision"] != source["revision"]
        stale = client.put(f"/api/projects/{project_id}/file", json={
            "path": source["path"], "content": source["content"], "expected_revision": source["revision"]
        })
        assert stale.status_code == 409
        assert get_json(client, f"/api/projects/{project_id}/file", params={"path": source["path"]})["content"] == edited

        development = run_job(client, project_id, "build", target="dev", select="orders",
                              defer=True, favor_state=True)
        after = run_job(client, project_id, "preview", model_id="model.jaffle_shop.orders",
                        target="dev", dialect="duckdb", defer=True, favor_state=True)
        after_page = page(client, after)
        assert after_page["row_count"] == len(completed_rows)
        assert sorted(after_page["rows"], key=repr) == sorted(completed_rows, key=repr)
        assert hashlib.sha256(baseline_manifest.read_bytes()).hexdigest() == immutable_hash
        assert get_json(client, f"/api/projects/{project_id}")["baseline"]["id"] == baseline["id"]

        # Baseline mode must read the immutable SQL artifact despite the edited file.
        frozen = run_job(client, project_id, "preview", model_id="model.jaffle_shop.orders",
                         target="baseline", baseline=True, dialect="duckdb")
        assert page(client, frozen)["rows"] == before_page["rows"]

        difference = run_job(client, project_id, "compare", left_result_id=before["result_id"],
                             right_result_id=after["result_id"], key_columns=["order_id"])
        difference_page = page(client, difference)
        assert difference["summary"]["equal"] is False
        assert difference_page["row_count"] > 0
        equal = run_job(client, project_id, "compare", left_result_id=before["result_id"],
                        right_result_id=frozen["result_id"], key_columns=["order_id"])
        assert equal["summary"]["equal"] is True
        assert page(client, equal)["row_count"] == 0

        native_query = run_job(client, project_id, "query", content=EDN, format="edn",
                               target="baseline", dialect="duckdb", parameters={"status": "completed"})
        sqlite_query = run_job(client, project_id, "query", content=EDN, format="edn",
                               target="baseline", dialect="sqlite", parameters={"status": "completed"})
        native_page, sqlite_page = page(client, native_query), page(client, sqlite_query)
        assert native_page["row_count"] > 0
        assert native_page["rows"] == sqlite_page["rows"]
        sqlite_metadata = get_json(client, f"/api/results/{sqlite_query['result_id']}")
        assert sqlite_metadata["dialect"] == "sqlite"
        assert sqlite_metadata["sql"]
        assert sqlite_metadata["provenance"]

        # Paging and download must present the same persisted values.
        first_page = get_json(client, f"/api/results/{before['result_id']}/rows", params={"offset": 1, "limit": 2})
        assert first_page["rows"] == before_page["rows"][1:3]
        assert first_page["row_count"] == 99
        download = client.get(f"/api/results/{before['result_id']}/download")
        assert download.status_code == 200
        csv_rows = list(csv.reader(io.StringIO(download.text)))
        assert csv_rows[0] == columns
        assert len(csv_rows) == 100

        # Durable event cursor contains no duplicates on subsequent polling.
        events = get_json(client, f"/api/jobs/{development['id']}/events")["events"]
        assert events
        sequences = [event["seq"] for event in events]
        assert sequences == sorted(set(sequences))
        assert get_json(client, f"/api/jobs/{development['id']}/events", params={"after": sequences[-1]})["events"] == []
        assert baseline_job["id"] in {j["id"] for j in get_json(client, f"/api/projects/{project_id}/jobs")["jobs"]}

        restored = client.put(f"/api/projects/{project_id}/file", json={
            "path": source["path"], "content": source["content"], "expected_revision": saved["revision"]
        })
        assert restored.status_code == 200

        # Existing production artifacts are imported without building that target.
        import_response = client.post(f"/api/projects/{project_id}/baseline/import", json={
            "artifact_dir": baseline["artifact_dir"], "target": "baseline"
        })
        assert import_response.status_code == 201, import_response.text
        imported_baseline = import_response.json()
        assert imported_baseline["id"] != baseline["id"]
        assert imported_baseline["manifest_revision"] == baseline["manifest_revision"]
        assert Path(imported_baseline["manifest_path"]).is_file()
        baseline = imported_baseline

    # A new process-level runtime must find prior projects, jobs and Arrow data.
    with TestClient(create_app(data_dir)) as restarted:
        assert get_json(restarted, f"/api/projects/{project_id}")["baseline"]["id"] == baseline["id"]
        assert get_json(restarted, f"/api/jobs/{sqlite_query['id']}")["status"] == "succeeded"
        assert get_json(restarted, f"/api/results/{sqlite_query['result_id']}/rows", params={"limit": 1000})["rows"] == sqlite_page["rows"]


def test_rejects_file_escape_and_cross_origin_calls(tmp_path: Path):
    with TestClient(create_app(tmp_path / "workbench")) as client:
        project = make_demo(client)
        project_id = project["id"]
        for path in ("../dbt_project.yml", "/etc/passwd", "profiles.yml", "target/manifest.json"):
            response = client.get(f"/api/projects/{project_id}/file", params={"path": path})
            assert response.status_code in {400, 404}, (path, response.text)
        assert client.get("/api/projects/missing").status_code == 404
        assert client.post("/api/projects/demo", headers={"Origin": "https://external.example"}).status_code == 403
        assert client.post(f"/api/projects/{project_id}/jobs", json={"kind": "shell", "options": {}}).status_code == 422


def test_real_query_cancellation_and_read_only_enforcement(tmp_path: Path):
    with TestClient(create_app(tmp_path / "workbench")) as client:
        project_id = make_demo(client)["id"]
        run_job(client, project_id, "baseline", target="baseline")
        bad = submit(client, project_id, "query", content="DROP TABLE orders", format="sql",
                     target="baseline", dialect="duckdb")
        failed = wait_job(client, bad["id"], expected="failed")
        assert failed["error"]
        preserved = run_job(client, project_id, "query", content="SELECT COUNT(*) AS n FROM orders",
                            format="sql", target="baseline", dialect="duckdb")
        assert page(client, preserved)["rows"] == [[99]]

        long_job = submit(client, project_id, "query", content=(
            "WITH RECURSIVE t(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM t WHERE x < 1000000000) "
            "SELECT SUM(x) AS slow_sum FROM t"
        ), format="sql", target="baseline", dialect="duckdb")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            current = get_json(client, f"/api/jobs/{long_job['id']}")
            assert current["status"] not in TERMINAL, current
            if current["status"] == "running":
                break
            time.sleep(0.02)
        response = client.post(f"/api/jobs/{long_job['id']}/cancel")
        assert response.status_code == 200, response.text
        cancelled = wait_job(client, long_job["id"], timeout=15, expected="cancelled")
        assert cancelled["result_id"] is None
        # Cancelling a connection must not poison the next request.
        recovery = run_job(client, project_id, "query", content="SELECT 42 AS answer",
                           format="sql", target="baseline", dialect="duckdb")
        assert page(client, recovery)["rows"] == [[42]]
