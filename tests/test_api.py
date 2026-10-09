"""HTTP boundary checks around the local file/database service."""

from fastapi.testclient import TestClient
import threading
import time

import pyarrow as pa

from dbt_workbench.app import create_app


def test_http_local_origin_and_error_contract(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        health = client.get("/api/health")
        assert health.status_code == 200
        assert health.json()["status"] == "ok"
        assert health.headers["cache-control"] == "no-store"
        assert client.get("/api/projects/missing").status_code == 404
        assert client.get("/api/health", headers={"Host": "attacker.invalid"}).status_code == 400
        assert client.post("/api/projects/demo", headers={"Origin": "https://attacker.invalid"}).status_code == 403
        assert client.post("/api/projects/demo", headers={"Origin": "null"}).status_code == 403
        assert client.post("/api/projects/demo", headers={"Sec-Fetch-Site": "cross-site"}).status_code == 403
        assert client.get("/api/projects").json() == {"projects": []}
        created = client.post("/api/projects/demo", headers={"Origin": "http://testserver"})
        assert created.status_code == 201
        project_id = created.json()["id"]
        assert client.post(f"/api/projects/{project_id}/jobs", json={"kind": "shell", "options": {}}).status_code == 422
        assert client.get(f"/api/projects/{project_id}/file", params={"path": "../../etc/passwd"}).status_code == 400
        assert client.get("/api/results/missing/rows?limit=1001").status_code == 422


def test_api_file_save_requires_current_revision(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        project_id = client.post("/api/projects/demo").json()["id"]
        endpoint = f"/api/projects/{project_id}/file"
        original = client.get(endpoint, params={"path": "models/customers.sql"}).json()
        changed = {"path": original["path"], "content": original["content"] + "\n-- local edit\n", "expected_revision": original["revision"]}
        assert client.put(endpoint, json=changed).status_code == 200
        conflict = client.put(endpoint, json={**changed, "content": "select 1"})
        assert conflict.status_code == 409
        assert "detail" in conflict.json()
        assert client.get(endpoint, params={"path": original["path"]}).json()["content"] == changed["content"]


def test_active_job_preserves_its_project_inputs(tmp_path):
    app = create_app(tmp_path)
    release = threading.Event()
    with TestClient(app) as client:
        project_id = client.post("/api/projects/demo").json()["id"]
        endpoint = f"/api/projects/{project_id}/file"
        original = client.get(endpoint, params={"path": "models/orders.sql"}).json()

        def running_query(*args):
            release.wait(5)
            return {"table": pa.table({"value": [1]}), "sql": "SELECT 1"}

        app.state.runtime.engine.query = running_query
        job = client.post(f"/api/projects/{project_id}/jobs", json={"kind": "query"}).json()
        changed = {"path": original["path"], "content": original["content"] + "\n-- next revision\n", "expected_revision": original["revision"]}
        try:
            response = client.put(endpoint, json=changed)
            assert response.status_code == 409
            assert "active jobs" in response.json()["detail"]
            assert client.get(endpoint, params={"path": original["path"]}).json()["revision"] == original["revision"]
            assert client.post(f"/api/projects/{project_id}/baseline/import", json={"artifact_dir": "/missing", "target": "prod"}).status_code == 409
        finally:
            release.set()
        for _ in range(100):
            if client.get(f"/api/jobs/{job['id']}").json()["status"] == "succeeded":
                break
            time.sleep(0.01)
        else:
            raise AssertionError("Query did not finish")
        assert client.put(endpoint, json=changed).status_code == 200
