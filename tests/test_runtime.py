from datetime import date, datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import threading
import time
from types import SimpleNamespace

import pyarrow as pa
import pytest

from dbt_workbench.runtime import Runtime, _redact


class Workspaces:
    def __init__(self, root):
        self.root = root
        self.projects = {}
        self.baselines = {}
        self.manifests = {}
        for project in ("p", "q"):
            path = root / project
            path.mkdir(parents=True)
            self.projects[project] = {"id": project, "name": project, "path": str(path)}

    def get_project(self, project):
        return self.projects[project]

    def project_data(self, project):
        path = self.root / "data" / project
        path.mkdir(parents=True, exist_ok=True)
        return path

    def dbt_args(self, project):
        return ["--project-dir", self.projects[project]["path"]]

    def register_manifest(self, project, path):
        self.manifests[project] = json.loads(path.read_text())

    def baseline(self, project):
        return self.baselines.get(project)

    def register_baseline(self, project, artifact_dir, target):
        baseline = {"id": "baseline-id", "artifact_dir": str(artifact_dir), "target": target}
        self.baselines[project] = baseline
        return baseline


class Engine:
    calls = []
    lock = threading.Lock()
    active = {}
    overlap = False

    def __init__(self, workspaces):
        self.workspaces = workspaces

    def preview(self, project, options, cancel_event, event):
        assert project in self.workspaces.manifests
        assert Path(options["compiled_artifact_dir"], "manifest.json").is_file()
        return self.query(project, options, cancel_event, event)

    def query(self, project, options, cancel_event, event):
        with self.lock:
            self.active[project] = self.active.get(project, 0) + 1
            self.overlap = self.overlap or self.active[project] > 1
        try:
            self.calls.append((project, options))
            event("worker started")
            if options.get("wait"):
                cancel_event.wait(10)
            else:
                cancel_event.wait(options.get("delay", 0))
            rows = options.get("rows", 5000)
            table = pa.table({
                "id": range(rows),
                "amount": pa.array([Decimal("12.30")] * rows, type=pa.decimal128(8, 2)),
                "day": [date(2026, 10, 9)] * rows,
                "at": [datetime(2026, 10, 9, tzinfo=timezone.utc)] * rows,
            })
            if options.get("reverse"):
                table = table.take(pa.array(list(reversed(range(rows)))))
            if options.get("float_amount"):
                table = table.set_column(1, "amount", pa.array([12.3] * rows))
            return {"table": table, "sql": "SELECT fixture", "dialect": "duckdb", "provenance": {"fixture": True, "truncated": options.get("truncated", False)}, "summary": {"rows": rows}}
        finally:
            with self.lock:
                self.active[project] -= 1


@pytest.fixture
def setup(tmp_path, monkeypatch):
    monkeypatch.setitem(sys.modules, "dbt_workbench.query", SimpleNamespace(QueryEngine=Engine))
    Engine.calls.clear()
    Engine.active.clear()
    Engine.overlap = False
    spaces = Workspaces(tmp_path / "projects")
    data = tmp_path / "app"
    runtime = Runtime(data, spaces)
    yield runtime, spaces, data
    runtime.shutdown()


def complete(runtime, job, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = runtime.get_job(job["id"])
        if value["status"] in {"succeeded", "failed", "cancelled", "interrupted"}:
            return value
        time.sleep(0.01)
    raise AssertionError(runtime.get_job(job["id"]))


def running(runtime, job):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if runtime.get_job(job["id"])["status"] == "running":
            return
        time.sleep(0.01)
    raise AssertionError("Job never started")


@pytest.fixture
def fake_dbt(tmp_path, monkeypatch):
    executable = tmp_path / "fake dbt"
    executable.write_text("#!" + sys.executable + "\n" + r'''
import json,pathlib,subprocess,sys,time
args=sys.argv[1:]
out=pathlib.Path(args[args.index('--target-path')+1]);out.mkdir(exist_ok=True)
(out/'argv.json').write_text(json.dumps(args))
print(json.dumps({'info':{'level':'info','msg':'password="do not leak" Bearer hidden-token'}}),flush=True)
if 'stall' in args:
 print('stalling',flush=True)
 time.sleep(30)
status='error' if 'bad' in args else 'success'
(out/'manifest.json').write_text(json.dumps({'nodes':{},'metadata':{'fixture':True}}))
if args[0]!='parse':
 (out/'run_results.json').write_text(json.dumps({'results':[{'status':status,'message':'invalid model' if status=='error' else None}]}))
''')
    executable.chmod(0o755)
    monkeypatch.setenv("DBT_WORKBENCH_DBT", str(executable))
    return executable


def test_result_pages_are_typed_and_survive_reconnect(setup):
    runtime, spaces, data = setup
    job = complete(runtime, runtime.submit("p", "query", {}))
    assert job["status"] == "succeeded"
    result = runtime.get_result(job["result_id"])
    assert result["row_count"] == 5000
    assert "arrow_path" not in result
    page = runtime.result_page(result["id"], 2047, 3)
    assert [row[0] for row in page["rows"]] == [2047, 2048, 2049]
    assert page["rows"][0][1:] == ["12.30", "2026-10-09", "2026-10-09T00:00:00+00:00"]
    assert runtime.result_page(result["id"], 9000)["rows"] == []
    with pytest.raises(ValueError):
        runtime.result_page(result["id"], limit=1001)
    cursor = runtime.events(job["id"])[-1]["seq"]
    runtime.shutdown()
    reopened = Runtime(data, spaces)
    try:
        assert reopened.get_job(job["id"])["status"] == "succeeded"
        assert reopened.events(job["id"], after=cursor) == []
        assert reopened.result_page(result["id"], 4999)["rows"][0][0] == 4999
    finally:
        reopened.shutdown()


def test_project_queue_and_cancellation(setup):
    runtime, _, _ = setup
    first = runtime.submit("p", "query", {"wait": True})
    running(runtime, first)
    second = runtime.submit("p", "query", {})
    other_project = runtime.submit("q", "query", {"rows": 1})
    assert complete(runtime, other_project)["status"] == "succeeded"
    assert runtime.get_job(second["id"])["status"] == "queued"
    assert runtime.cancel(second["id"])["status"] == "cancelled"
    runtime.cancel(first["id"])
    assert complete(runtime, first)["status"] == "cancelled"
    assert not runtime.list_results("p")
    assert not Engine.overlap


def test_native_artifact_isolation_baseline_and_preview(setup, fake_dbt):
    runtime, spaces, _ = setup
    baseline = complete(runtime, runtime.submit("p", "baseline", {"target": "baseline"}))
    assert baseline["status"] == "succeeded"
    state = spaces.baseline("p")
    saved = Path(state["artifact_dir"], "manifest.json").read_bytes()
    preview = complete(runtime, runtime.submit("p", "preview", {"target": "dev", "model_id": "model.shop.orders", "defer": True, "favor_state": True}))
    assert preview["status"] == "succeeded", preview
    _, options = Engine.calls[-1]
    directory = Path(options["compiled_artifact_dir"])
    args = json.loads((directory / "argv.json").read_text())
    assert args[0] == "compile"
    assert "--favor-state" in args and args[args.index("--state") + 1] == state["artifact_dir"]
    assert args[args.index("--select") + 1] == "shop.orders"
    assert directory != Path(state["artifact_dir"])
    assert Path(state["artifact_dir"], "manifest.json").read_bytes() == saved
    logs = "\n".join(event["message"] for event in runtime.events(preview["id"]))
    assert "do not leak" not in logs and "hidden-token" not in logs
    assert "[REDACTED]" in logs


def test_run_results_failure_wins_over_zero_exit(setup, fake_dbt):
    runtime, spaces, _ = setup
    job = complete(runtime, runtime.submit("p", "build", {"select": "bad"}))
    assert job["status"] == "failed"
    assert "invalid model" in job["error"]
    assert "p" not in spaces.manifests


def test_native_cancel_quiet_process(setup, fake_dbt):
    runtime, _, _ = setup
    job = runtime.submit("p", "build", {"select": "stall"})
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if any("stalling" in e["message"] for e in runtime.events(job["id"])):
            break
        time.sleep(.01)
    else:
        raise AssertionError(runtime.get_job(job["id"]))
    started = time.monotonic()
    runtime.cancel(job["id"])
    assert complete(runtime, job)["status"] == "cancelled"
    assert time.monotonic() - started < 3


def test_restart_marks_unfinished_jobs_and_retains_event_cursor(setup):
    runtime, spaces, data = setup
    runtime.shutdown()
    connection = sqlite3.connect(data / "runtime.sqlite3")
    connection.execute("INSERT INTO jobs(id,project_id,kind,status,created_at,options) VALUES('orphan','p','query','running','2026-10-09','{}')")
    connection.commit()
    connection.close()
    restarted = Runtime(data, spaces)
    try:
        assert restarted.get_job("orphan")["status"] == "interrupted"
        events = restarted.events("orphan")
        assert "restart" in events[-1]["message"]
    finally:
        restarted.shutdown()


def test_compare_ignores_order_preserves_duplicates_and_reports_changes(setup):
    runtime, _, _ = setup
    first = complete(runtime, runtime.submit("p", "query", {"rows": 3}))
    second = complete(runtime, runtime.submit("p", "query", {"rows": 3, "reverse": True}))
    result = complete(runtime, runtime.submit("p", "compare", {"left_result_id": first["result_id"], "right_result_id": second["result_id"], "key_columns": ["id"]}))
    assert result["summary"]["equal"] is True
    assert runtime.result_page(result["result_id"])["rows"] == []
    third = complete(runtime, runtime.submit("p", "query", {"rows": 2}))
    changed = complete(runtime, runtime.submit("p", "compare", {"left_result_id": first["result_id"], "right_result_id": third["result_id"]}))
    assert changed["summary"]["equal"] is False
    assert changed["summary"]["differences"] == 1


def test_comparison_rejects_non_unique_keys(setup):
    runtime, _, _ = setup
    job = complete(runtime, runtime.submit("p", "query", {"rows": 2}))
    compared = complete(runtime, runtime.submit("p", "compare", {"left_result_id": job["result_id"], "right_result_id": job["result_id"], "key_columns": ["amount"]}))
    assert compared["status"] == "failed"
    assert "not unique" in compared["error"]


def test_redaction_handles_quoted_values_and_connection_urls():
    output = _redact('password="secret with spaces" url=postgres://user:verysecret@localhost access_token=abc Bearer def')
    assert "secret with spaces" not in output
    assert "verysecret" not in output
    assert "=abc" not in output
    assert "Bearer def" not in output


def test_second_runtime_cannot_reconcile_active_first_instance(setup):
    runtime, spaces, data = setup
    first = runtime.submit("p", "query", {"wait": True})
    running(runtime, first)
    with pytest.raises(RuntimeError, match="already open"):
        Runtime(data, spaces)
    assert runtime.get_job(first["id"])["status"] == "running"
    runtime.cancel(first["id"])
    assert complete(runtime, first)["status"] == "cancelled"


def test_baseline_preview_reuses_saved_compilation(setup, fake_dbt, monkeypatch):
    runtime, spaces, _ = setup
    baseline = complete(runtime, runtime.submit("p", "baseline", {}))
    assert baseline["status"] == "succeeded"
    monkeypatch.setenv("DBT_WORKBENCH_DBT", "/deliberately/missing/dbt")
    preview = complete(runtime, runtime.submit("p", "preview", {"baseline": True, "model_id": "model.shop.orders"}))
    assert preview["status"] == "succeeded", preview
    assert Engine.calls[-1][1]["compiled_artifact_dir"] == spaces.baseline("p")["artifact_dir"]


@pytest.mark.skipif(os.name == "nt", reason="POSIX process group behavior")
def test_restart_terminates_only_verified_orphan(setup):
    runtime, spaces, data = setup
    runtime.shutdown()
    orphan = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], start_new_session=True)
    identity = Runtime._process_identity(orphan.pid)
    assert identity
    connection = sqlite3.connect(data / "runtime.sqlite3")
    connection.execute("INSERT INTO jobs(id,project_id,kind,status,created_at,options) VALUES('orphan','p','build','running','2026-10-09','{}')")
    connection.execute("INSERT INTO processes VALUES(?,?,?)", ("orphan", orphan.pid, identity))
    connection.commit()
    connection.close()
    restarted = None
    try:
        restarted = Runtime(data, spaces)
        assert orphan.wait(timeout=3) < 0
        assert restarted.get_job("orphan")["status"] == "interrupted"
    finally:
        if restarted:
            restarted.shutdown()
        if orphan.poll() is None:
            orphan.kill()
            orphan.wait()


def test_numeric_canonicalization_keeps_all_decimal_digits():
    left = Decimal("12345678901234567890123456789012345678")
    right = Decimal("12345678901234567890123456789012345679")
    assert Runtime._canonical(left) != Runtime._canonical(right)
    assert Runtime._canonical(Decimal("12.30")) == Runtime._canonical(12.3)


def test_compare_rejects_equal_visible_rows_if_either_result_was_truncated(setup):
    runtime, _, _ = setup
    complete_result = complete(runtime, runtime.submit("p", "query", {"rows": 2}))
    truncated_result = complete(runtime, runtime.submit("p", "query", {"rows": 2, "truncated": True}))
    assert runtime.result_page(complete_result["result_id"])["rows"] == runtime.result_page(truncated_result["result_id"])["rows"]
    for left, right in [(complete_result, truncated_result), (truncated_result, complete_result)]:
        compared = complete(runtime, runtime.submit("p", "compare", {"left_result_id": left["result_id"], "right_result_id": right["result_id"]}))
        assert compared["status"] == "failed"
        assert "truncated" in compared["error"] and "max_rows" in compared["error"]
        assert compared["result_id"] is None
        assert not compared["summary"].get("equal")


def test_comparison_separates_numeric_row_equality_from_schema_equality(setup):
    runtime, _, _ = setup
    decimal_result = complete(runtime, runtime.submit("p", "query", {"rows": 2}))
    float_result = complete(runtime, runtime.submit("p", "query", {"rows": 2, "float_amount": True}))
    compared = complete(runtime, runtime.submit("p", "compare", {"left_result_id": decimal_result["result_id"], "right_result_id": float_result["result_id"]}))
    assert compared["status"] == "succeeded"
    assert compared["summary"]["equal"] is True
    assert compared["summary"]["schema_equal"] is False
    assert "result rows" in compared["summary"]["comparison_scope"]


def test_query_rejects_noncurrent_baseline_instead_of_silently_substituting(setup, fake_dbt):
    runtime, spaces, _ = setup
    assert complete(runtime, runtime.submit("p", "baseline", {}))["status"] == "succeeded"
    rejected = complete(runtime, runtime.submit("p", "query", {"baseline_id": "older-baseline"}))
    assert rejected["status"] == "failed"
    assert "Historical baseline" in rejected["error"]
    assert rejected["result_id"] is None
    assert Engine.calls == []
    accepted = complete(runtime, runtime.submit("p", "query", {"baseline_id": spaces.baseline("p")["id"], "rows": 1}))
    assert accepted["status"] == "succeeded"
