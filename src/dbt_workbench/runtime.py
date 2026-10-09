"""Durable local jobs, isolated dbt invocations, and immutable query results."""
from __future__ import annotations

from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone
from decimal import Decimal
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import threading
import time
from typing import Any, Callable
from uuid import uuid4

import pyarrow as pa
import pyarrow.ipc as ipc


TERMINAL = frozenset({"succeeded", "failed", "cancelled", "interrupted"})
KINDS = frozenset({"parse", "build", "test", "baseline", "preview", "query", "compare"})


class JobCancelled(Exception):
    """Cooperative cancellation acknowledged by the worker."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _json_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, float) and not math.isfinite(value):
        return str(value)
    if isinstance(value, dict):
        return {str(k): _json_value(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(v) for v in value]
    return value


def _dumps(value: Any) -> str:
    return json.dumps(_json_value(value), ensure_ascii=False, allow_nan=False)


_CREDENTIAL = re.compile(
    r"(?i)(\b(?:password|passwd|access_token|refresh_token|private_key|client_secret|secret_access_key)\b[\"']?\s*[:=]\s*)([\"']?)([^\s,}\"']+)([\"']?)"
)
_BEARER = re.compile(r"(?i)(\bBearer\s+)[A-Za-z0-9._~+/-]+=*")
_URL_PASSWORD = re.compile(r"(://[^\s/:@]+:)[^\s/@]+(@)")
_QUOTED_CREDENTIAL = re.compile(
    r'''(?i)(\b(?:password|passwd|access_token|refresh_token|private_key|client_secret|secret_access_key)\b["']?\s*[:=]\s*)(["'])(.*?)(\2)'''
)


def _redact(message: str) -> str:
    message = _URL_PASSWORD.sub(r"\1[REDACTED]\2", message)
    message = _BEARER.sub(r"\1[REDACTED]", message)
    message = _QUOTED_CREDENTIAL.sub(lambda m: m.group(1) + "[REDACTED]", message)
    return _CREDENTIAL.sub(lambda m: m.group(1) + "[REDACTED]", message)


class Runtime:
    """Serialize jobs per project while allowing independent projects to run."""

    def __init__(self, data_dir: Path, workspaces: Any):
        from .query import QueryEngine
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.workspaces = workspaces
        self.engine = QueryEngine(workspaces)
        self._process_lock = self._acquire_process_lock()
        self._lock = threading.RLock()
        self._closed = False
        self._scheduled: set[str] = set()
        self._cancel: dict[str, threading.Event] = {}
        self._processes: dict[str, subprocess.Popen] = {}
        self._pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="dbt-workbench")
        self._db = sqlite3.connect(self.data_dir / "runtime.sqlite3", check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.execute("PRAGMA foreign_keys=ON")
        self._db.executescript("""
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, kind TEXT NOT NULL,
                status TEXT NOT NULL, created_at TEXT NOT NULL, started_at TEXT,
                finished_at TEXT, options TEXT NOT NULL, summary TEXT,
                error TEXT, result_id TEXT
            );
            CREATE INDEX IF NOT EXISTS jobs_project ON jobs(project_id, created_at);
            CREATE TABLE IF NOT EXISTS events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL,
                timestamp TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL,
                data TEXT, FOREIGN KEY(job_id) REFERENCES jobs(id)
            );
            CREATE INDEX IF NOT EXISTS events_job ON events(job_id, seq);
            CREATE TABLE IF NOT EXISTS processes (
                job_id TEXT PRIMARY KEY, pid INTEGER NOT NULL, identity TEXT,
                FOREIGN KEY(job_id) REFERENCES jobs(id)
            );
            CREATE TABLE IF NOT EXISTS results (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, job_id TEXT NOT NULL,
                kind TEXT NOT NULL, columns TEXT NOT NULL, row_count INTEGER NOT NULL,
                sql TEXT, dialect TEXT, provenance TEXT NOT NULL, created_at TEXT NOT NULL,
                arrow_path TEXT NOT NULL, summary TEXT,
                FOREIGN KEY(job_id) REFERENCES jobs(id)
            );
        """)
        self._db.commit()
        # Jobs are never silently replayed after a crash: running SQL may have committed.
        with self._lock:
            # Never signal a reused PID. If this platform cannot establish process
            # identity, report interruption without guessing which process to kill.
            for process in self._db.execute("SELECT * FROM processes").fetchall():
                if process["identity"] and self._process_identity(process["pid"]) == process["identity"]:
                    self._terminate_orphan(process["pid"])
            self._db.execute("DELETE FROM processes")
            stale = self._db.execute(
                "SELECT id FROM jobs WHERE status NOT IN ('succeeded','failed','cancelled','interrupted')"
            ).fetchall()
            for row in stale:
                self._db.execute(
                    "UPDATE jobs SET status='interrupted', finished_at=?, error=? WHERE id=?",
                    (_now(), "Application stopped before this job completed; review before rerunning.", row["id"]),
                )
                self._event(row["id"], "Job interrupted by application restart.", "warning")
            self._db.commit()

    def _acquire_process_lock(self):
        handle = (self.data_dir / ".runtime.lock").open("a+b")
        try:
            if os.name == "nt":
                import msvcrt
                handle.seek(0)
                if not handle.read(1):
                    handle.write(b"0")
                    handle.flush()
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            handle.close()
            raise RuntimeError("This workbench data directory is already open in another application instance.") from exc
        return handle

    def _release_process_lock(self) -> None:
        handle = self._process_lock
        if handle.closed:
            return
        try:
            if os.name == "nt":
                import msvcrt
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()

    def _job(self, row: sqlite3.Row) -> dict:
        result = dict(row)
        result["options"] = json.loads(result["options"])
        result["summary"] = json.loads(result["summary"]) if result["summary"] else None
        return result

    def submit(self, project_id: str, kind: str, options: dict | None = None) -> dict:
        self.workspaces.get_project(project_id)
        if kind not in KINDS:
            raise ValueError(f"Unsupported job kind: {kind}")
        if options is not None and not isinstance(options, dict):
            raise ValueError("Job options must be an object.")
        # Force options to a detached JSON document before any worker can observe it.
        detached = json.loads(_dumps(options or {}))
        with self._lock:
            if self._closed:
                raise RuntimeError("The application is shutting down.")
            job_id = uuid4().hex
            self._db.execute(
                "INSERT INTO jobs(id,project_id,kind,status,created_at,options) VALUES(?,?,?,?,?,?)",
                (job_id, project_id, kind, "queued", _now(), _dumps(detached)),
            )
            self._db.commit()
            self._cancel[job_id] = threading.Event()
            self._event(job_id, "Job queued.")
            initial = self.get_job(job_id)
            if project_id not in self._scheduled:
                self._scheduled.add(project_id)
                self._pool.submit(self._drain_project, project_id)
            return initial

    def get_job(self, job_id: str) -> dict:
        with self._lock:
            row = self._db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise KeyError(f"Unknown job: {job_id}")
            return self._job(row)

    def list_jobs(self, project_id: str | None = None) -> list[dict]:
        with self._lock:
            if project_id is not None:
                rows = self._db.execute("SELECT * FROM jobs WHERE project_id=? ORDER BY created_at DESC, rowid DESC", (project_id,)).fetchall()
            else:
                rows = self._db.execute("SELECT * FROM jobs ORDER BY created_at DESC, rowid DESC").fetchall()
            return [self._job(row) for row in rows]

    def _event(self, job_id: str, message: str, level: str = "info", data: Any = None) -> None:
        with self._lock:
            self._db.execute(
                "INSERT INTO events(job_id,timestamp,level,message,data) VALUES(?,?,?,?,?)",
                (job_id, _now(), level, _redact(str(message)), _dumps(data) if data is not None else None),
            )
            self._db.commit()

    def events(self, job_id: str, after: int = 0) -> list[dict]:
        self.get_job(job_id)
        if after < 0:
            raise ValueError("Event cursor must be nonnegative.")
        with self._lock:
            rows = self._db.execute("SELECT * FROM events WHERE job_id=? AND seq>? ORDER BY seq LIMIT 1000", (job_id, after)).fetchall()
            result = []
            for row in rows:
                event = dict(row)
                event["data"] = json.loads(event["data"]) if event["data"] else None
                result.append(event)
            return result

    def cancel(self, job_id: str) -> dict:
        with self._lock:
            job = self.get_job(job_id)
            if job["status"] in TERMINAL:
                return job
            event = self._cancel.setdefault(job_id, threading.Event())
            event.set()
            if job["status"] == "queued":
                self._db.execute("UPDATE jobs SET status='cancelled',finished_at=? WHERE id=?", (_now(), job_id))
                self._event(job_id, "Queued job cancelled.")
            else:
                self._db.execute("UPDATE jobs SET status='cancelling' WHERE id=?", (job_id,))
                self._event(job_id, "Cancellation requested; waiting for worker acknowledgement.", "warning")
            self._db.commit()
            proc = self._processes.get(job_id)
        if proc is not None:
            self._terminate(proc)
        return self.get_job(job_id)

    def _drain_project(self, project_id: str) -> None:
        while True:
            with self._lock:
                row = self._db.execute("SELECT * FROM jobs WHERE project_id=? AND status='queued' ORDER BY created_at, rowid LIMIT 1", (project_id,)).fetchone()
                if row is None or self._closed:
                    self._scheduled.discard(project_id)
                    return
                job = self._job(row)
                self._db.execute("UPDATE jobs SET status='running',started_at=? WHERE id=?", (_now(), job["id"]))
                self._db.commit()
            self._execute(job)

    def _execute(self, job: dict) -> None:
        job_id = job["id"]
        cancel_event = self._cancel[job_id]
        emit = lambda message, level="info", data=None: self._event(job_id, message, level, data)
        status, error, summary, result_id = "succeeded", None, {}, None
        emit("Job started.")
        try:
            self._check_cancel(cancel_event)
            if job["options"].get("baseline_id"):
                # No execution path may silently substitute the latest baseline
                # for an explicitly requested historical artifact.
                self._baseline(job["project_id"], job["options"])
            if job["kind"] in {"parse", "build", "test", "baseline"}:
                summary = self._run_dbt(job, cancel_event, emit)
            elif job["kind"] == "preview":
                # dbt owns Jinja rendering, adapter dispatch, and reference deferral.
                opts = dict(job["options"])
                if opts.get("baseline"):
                    baseline = self._baseline(job["project_id"], opts)
                    if not baseline:
                        raise ValueError("Build a baseline before previewing its saved SQL.")
                    artifacts = Path(baseline.get("artifact_dir") or baseline["path"])
                    if artifacts.name == "manifest.json":
                        artifacts = artifacts.parent
                    emit("Using the baseline's saved compilation; referenced data remains live.")
                else:
                    self._run_dbt(job, cancel_event, emit, command="compile")
                    artifacts = self._job_dir(job) / "artifacts"
                opts.update(compiled_artifact_dir=str(artifacts), artifact_dir=str(artifacts))
                payload = self.engine.preview(job["project_id"], opts, cancel_event, emit)
                self._check_cancel(cancel_event)
                result_id = self._store_result(job, payload)
                summary = payload.get("summary") or {}
            elif job["kind"] == "query":
                payload = self.engine.query(job["project_id"], job["options"], cancel_event, emit)
                self._check_cancel(cancel_event)
                result_id = self._store_result(job, payload)
                summary = payload.get("summary") or {}
            else:
                payload = self._compare(job, cancel_event)
                result_id = self._store_result(job, payload)
                summary = payload["summary"]
            self._check_cancel(cancel_event)
        except JobCancelled:
            status = "cancelled"
            error = "Job cancelled."
        except Exception as exc:
            if cancel_event.is_set():
                status, error = "cancelled", "Worker stopped after cancellation."
            else:
                status, error = "failed", _redact(str(exc)) or type(exc).__name__
        finally:
            with self._lock:
                if cancel_event.is_set() and status == "succeeded":
                    status, error = "cancelled", "Job cancelled."
                if status != "succeeded" and result_id:
                    abandoned = self._db.execute("SELECT arrow_path FROM results WHERE id=?", (result_id,)).fetchone()
                    self._db.execute("DELETE FROM results WHERE id=?", (result_id,))
                    if abandoned:
                        Path(abandoned["arrow_path"]).unlink(missing_ok=True)
                    result_id = None
                self._db.execute("UPDATE jobs SET status=?,finished_at=?,summary=?,error=?,result_id=? WHERE id=?", (status, _now(), _dumps(summary), error, result_id, job_id))
                self._db.commit()
                self._processes.pop(job_id, None)
                self._db.execute("DELETE FROM processes WHERE job_id=?", (job_id,))
                self._db.commit()
                self._cancel.pop(job_id, None)
            emit(f"Job {status}." + (f" {error}" if error else ""), "error" if status == "failed" else "info")

    @staticmethod
    def _check_cancel(event: threading.Event) -> None:
        if event.is_set():
            raise JobCancelled()

    def _job_dir(self, job: dict) -> Path:
        path = self.workspaces.project_data(job["project_id"]) / "runs" / job["id"]
        path.mkdir(parents=True, exist_ok=True)
        return path

    @staticmethod
    def _dbt_binary() -> str:
        override = os.environ.get("DBT_WORKBENCH_DBT")
        if override:
            if not Path(override).is_file():
                raise RuntimeError("DBT_WORKBENCH_DBT must name an existing dbt executable.")
            return override
        sibling = Path(sys.executable).parent / ("dbt.exe" if os.name == "nt" else "dbt")
        executable = str(sibling) if sibling.is_file() else shutil.which("dbt")
        if not executable:
            raise RuntimeError("dbt is not installed. Install this application's dbt dependencies or set DBT_WORKBENCH_DBT to your dbt executable.")
        return executable

    def _baseline(self, project_id: str, options: dict) -> dict | None:
        baseline = self.workspaces.baseline(project_id)
        selected = options.get("baseline_id")
        if selected and (not baseline or baseline.get("id") != selected):
            raise ValueError("Historical baseline execution is not supported; choose the current baseline and retry.")
        return baseline

    def _run_dbt(self, job: dict, cancel_event: threading.Event, emit: Callable, command: str | None = None) -> dict:
        opts = job["options"]
        command = command or ("build" if job["kind"] == "baseline" else job["kind"])
        run_dir = self._job_dir(job)
        artifact_dir = run_dir / "artifacts"
        log_dir = run_dir / "logs"
        artifact_dir.mkdir(exist_ok=True)
        log_dir.mkdir(exist_ok=True)
        args = [self._dbt_binary(), command, *self.workspaces.dbt_args(job["project_id"]), "--target-path", str(artifact_dir), "--log-path", str(log_dir), "--log-format", "json"]
        target = opts.get("target") or ("baseline" if job["kind"] == "baseline" else None)
        if target:
            args.extend(["--target", str(target)])
        selection = opts.get("select") or (opts.get("model_id") if command == "compile" else None)
        if job["kind"] == "baseline":
            selection = None
        if selection and command != "parse":
            if isinstance(selection, str):
                selection = selection.split()
            if not isinstance(selection, list) or not all(isinstance(x, str) and x for x in selection):
                raise ValueError("Selection must be a model selector or list of selectors.")
            normalized = []
            manifest_getter = getattr(self.workspaces, "manifest", None)
            manifest = manifest_getter(job["project_id"]) if manifest_getter else {}
            for selector in selection:
                if selector.startswith("model."):
                    node = manifest.get("nodes", {}).get(selector, {})
                    selector = "fqn:" + ".".join(node["fqn"]) if node.get("fqn") else selector.removeprefix("model.")
                normalized.append(selector)
            args.extend(["--select", *normalized])
        # Building a new baseline is a full native build, never deferred to an older one.
        if opts.get("defer") and command != "parse" and job["kind"] != "baseline":
            baseline = self._baseline(job["project_id"], opts)
            if not baseline:
                raise ValueError("Build or import a baseline before enabling reference deferral.")
            state = baseline.get("artifact_dir") or baseline.get("path")
            if not state:
                raise ValueError("The baseline has no artifact directory.")
            state = Path(state)
            if state.name == "manifest.json":
                state = state.parent
            if not (state / "manifest.json").is_file():
                raise ValueError("The baseline manifest is missing; build a new baseline.")
            args.extend(["--defer", "--state", str(state)])
            if opts.get("favor_state"):
                args.append("--favor-state")
        elif opts.get("favor_state") and job["kind"] != "baseline":
            raise ValueError("Favor state requires reference deferral.")
        self._check_cancel(cancel_event)
        emit(f"Running dbt {command}" + (f" against target {target}." if target else "."))
        project = self.workspaces.get_project(job["project_id"])
        kwargs: dict = {"cwd": project["path"], "stdout": subprocess.PIPE, "stderr": subprocess.STDOUT, "text": True, "encoding": "utf-8", "errors": "replace", "bufsize": 1}
        if os.name == "nt":
            kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
        proc = subprocess.Popen(args, **kwargs)
        with self._lock:
            self._processes[job["id"]] = proc
            self._db.execute("INSERT OR REPLACE INTO processes VALUES(?,?,?)", (job["id"], proc.pid, self._process_identity(proc.pid)))
            self._db.commit()
        if cancel_event.is_set():
            self._terminate(proc)
        # A reader thread cannot block process cancellation while stdout is quiet.
        def read_output() -> None:
            assert proc.stdout is not None
            for line in proc.stdout:
                line = line.rstrip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                    info = record.get("info", {})
                    emit(str(info.get("msg", line)), str(info.get("level", "info")))
                except (json.JSONDecodeError, AttributeError):
                    emit(line)
        reader = threading.Thread(target=read_output, daemon=True, name=f"dbt-log-{job['id'][:8]}")
        reader.start()
        while proc.poll() is None:
            if cancel_event.wait(0.1):
                self._terminate(proc)
                break
        returncode = proc.wait()
        reader.join(timeout=5)
        if proc.stdout is not None and not reader.is_alive():
            proc.stdout.close()
        self._check_cancel(cancel_event)
        results_path = artifact_dir / "run_results.json"
        result_rows = []
        if results_path.is_file():
            result_rows = json.loads(results_path.read_text(encoding="utf-8")).get("results", [])
        counts = Counter(str(row.get("status", "unknown")) for row in result_rows)
        failures = [row for row in result_rows if row.get("status") in {"error", "fail", "runtime error", "partial success", "skipped"}]
        summary = {"command": command, "target": target, "returncode": returncode, "statuses": dict(counts), "artifact_dir": str(artifact_dir)}
        if returncode != 0 or failures:
            details = "; ".join(str(row.get("message") or row.get("unique_id") or row.get("status")) for row in failures[:3])
            raise RuntimeError(f"dbt {command} failed (exit {returncode})." + (f" {details}" if details else " See the job log for details."))
        manifest = artifact_dir / "manifest.json"
        if not manifest.is_file():
            raise RuntimeError(f"dbt {command} did not produce a manifest; inspect the job log.")
        self.workspaces.register_manifest(job["project_id"], manifest)
        if job["kind"] == "baseline":
            baseline = self.workspaces.register_baseline(job["project_id"], artifact_dir, str(target))
            summary["baseline"] = baseline
            emit("Immutable baseline registered.")
        return summary

    @staticmethod
    def _process_identity(pid: int) -> str | None:
        try:
            if sys.platform.startswith("linux"):
                stat = Path(f"/proc/{pid}/stat").read_text()
                fields = stat.rsplit(") ", 1)[1].split()
                return None if fields[0] == "Z" else fields[19]
            if os.name == "nt":
                import ctypes
                from ctypes import wintypes
                kernel = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
                kernel.OpenProcess.restype = wintypes.HANDLE
                kernel.GetProcessTimes.argtypes = [wintypes.HANDLE, *([ctypes.POINTER(wintypes.FILETIME)] * 4)]
                kernel.CloseHandle.argtypes = [wintypes.HANDLE]
                handle = kernel.OpenProcess(0x1000, False, pid)
                if not handle:
                    return None
                times = [wintypes.FILETIME() for _ in range(4)]
                try:
                    if kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
                        return str((times[0].dwHighDateTime << 32) | times[0].dwLowDateTime)
                finally:
                    kernel.CloseHandle(handle)
                return None
            result = subprocess.run(["ps", "-p", str(pid), "-o", "lstart="], capture_output=True, text=True, timeout=2)
            return result.stdout.strip() or None
        except (OSError, IndexError, subprocess.TimeoutExpired):
            return None

    @staticmethod
    def _terminate_orphan(pid: int) -> None:
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
            else:
                os.killpg(pid, signal.SIGTERM)
                identity = Runtime._process_identity(pid)
                deadline = time.monotonic() + 2
                while identity and time.monotonic() < deadline and Runtime._process_identity(pid) == identity:
                    time.sleep(0.025)
                if identity and Runtime._process_identity(pid) == identity:
                    os.killpg(pid, signal.SIGKILL)
        except (OSError, subprocess.TimeoutExpired):
            pass

    @staticmethod
    def _terminate(proc: subprocess.Popen) -> None:
        if proc.poll() is not None:
            return
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=5)
            else:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass

    def _store_result(self, job: dict, payload: dict) -> str:
        table = payload.get("table")
        if not isinstance(table, pa.Table):
            raise ValueError("Query worker did not return an Arrow table.")
        result_id = uuid4().hex
        folder = self.workspaces.project_data(job["project_id"]) / "results"
        folder.mkdir(parents=True, exist_ok=True)
        destination = folder / f"{result_id}.arrow"
        temporary = destination.with_suffix(".tmp")
        try:
            with ipc.new_file(temporary, table.schema) as writer:
                for batch in table.to_batches(max_chunksize=2048):
                    writer.write_batch(batch)
            temporary.replace(destination)
            columns = [{"name": field.name, "type": str(field.type)} for field in table.schema]
            with self._lock:
                self._db.execute("INSERT INTO results VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (result_id, job["project_id"], job["id"], job["kind"], _dumps(columns), table.num_rows, payload.get("sql", ""), payload.get("dialect", ""), _dumps(payload.get("provenance", {})), _now(), str(destination), _dumps(payload.get("summary", {}))))
                self._db.commit()
        except Exception:
            temporary.unlink(missing_ok=True)
            destination.unlink(missing_ok=True)
            raise
        return result_id

    @staticmethod
    def _result(row: sqlite3.Row, include_path: bool = False) -> dict:
        value = dict(row)
        for key in ("columns", "provenance", "summary"):
            value[key] = json.loads(value[key]) if value[key] else None
        if not include_path:
            value.pop("arrow_path", None)
        return value

    def get_result(self, result_id: str) -> dict:
        with self._lock:
            row = self._db.execute("SELECT * FROM results WHERE id=?", (result_id,)).fetchone()
            if row is None:
                raise KeyError(f"Unknown result: {result_id}")
            return self._result(row)

    def list_results(self, project_id: str | None = None) -> list[dict]:
        with self._lock:
            if project_id is not None:
                rows = self._db.execute("SELECT * FROM results WHERE project_id=? ORDER BY created_at DESC, rowid DESC", (project_id,)).fetchall()
            else:
                rows = self._db.execute("SELECT * FROM results ORDER BY created_at DESC, rowid DESC").fetchall()
            return [self._result(row) for row in rows]

    def result_path(self, result_id: str) -> Path:
        """Return the internal immutable Arrow file for the download endpoint."""
        with self._lock:
            row = self._db.execute("SELECT arrow_path FROM results WHERE id=?", (result_id,)).fetchone()
            if row is None:
                raise KeyError(f"Unknown result: {result_id}")
            return Path(row["arrow_path"])

    def result_page(self, result_id: str, offset: int = 0, limit: int = 100) -> dict:
        if offset < 0 or limit < 1 or limit > 1000:
            raise ValueError("Offset must be nonnegative and limit must be between 1 and 1000.")
        metadata = self.get_result(result_id)
        remaining, skip, batches = limit, offset, []
        with pa.memory_map(str(self.result_path(result_id)), "r") as source:
            reader = ipc.open_file(source)
            for index in range(reader.num_record_batches):
                batch = reader.get_batch(index)
                if skip >= batch.num_rows:
                    skip -= batch.num_rows
                    continue
                selected = batch.slice(skip, remaining)
                batches.append(selected)
                remaining -= selected.num_rows
                skip = 0
                if not remaining:
                    break
            table = pa.Table.from_batches(batches, schema=reader.schema)
            # Column-position access also works for SQL results with duplicate labels.
            rows = [[_json_value(table.column(col)[row].as_py()) for col in range(table.num_columns)] for row in range(table.num_rows)]
        return {"columns": metadata["columns"], "rows": rows, "row_count": metadata["row_count"], "offset": offset, "limit": limit}

    def _load_table(self, result_id: str) -> pa.Table:
        with pa.memory_map(str(self.result_path(result_id)), "r") as source:
            return ipc.open_file(source).read_all()

    @staticmethod
    def _canonical(value: Any) -> Any:
        if isinstance(value, bool):
            return ("boolean", value)
        if isinstance(value, (int, float, Decimal)):
            if isinstance(value, float) and not math.isfinite(value):
                return ("number", str(value))
            decimal = Decimal(str(value))
            if not decimal.is_finite():
                return ("number", str(decimal))
            # Decimal equality/hashing is exact across scales. normalize() uses
            # the ambient 28-digit context and would round DECIMAL(38) values.
            return ("number", decimal)
        if isinstance(value, dict):
            return tuple(sorted((str(k), Runtime._canonical(v)) for k, v in value.items()))
        if isinstance(value, (tuple, list)):
            return tuple(Runtime._canonical(v) for v in value)
        return (type(value).__name__, str(value))

    def _compare(self, job: dict, cancel_event: threading.Event) -> dict:
        opts = job["options"]
        left_id, right_id = opts.get("left_result_id"), opts.get("right_result_id")
        if not left_id or not right_id:
            raise ValueError("Choose both result IDs to compare.")
        left_meta, right_meta = self.get_result(left_id), self.get_result(right_id)
        if left_meta["project_id"] != job["project_id"] or right_meta["project_id"] != job["project_id"]:
            raise ValueError("Both comparison results must belong to this project.")
        if any((metadata.get("provenance") or {}).get("truncated") or (metadata.get("summary") or {}).get("truncated") for metadata in (left_meta, right_meta)):
            raise ValueError("Cannot compare truncated results: equal captured rows do not establish equal queries. Increase max_rows or narrow both queries, then rerun them before comparing.")
        left, right = self._load_table(left_id), self._load_table(right_id)
        if left.column_names != right.column_names:
            raise ValueError("Comparison requires matching column names and order.")
        if len(set(left.column_names)) != len(left.column_names):
            raise ValueError("Comparison needs unique column names; alias duplicate projections.")
        keys = opts.get("key_columns") or []
        if isinstance(keys, str):
            keys = [part.strip() for part in keys.split(",") if part.strip()]
        if not isinstance(keys, list) or any(key not in left.column_names for key in keys):
            raise ValueError("Comparison keys must be existing column names.")
        differences = []
        left_rows, right_rows = left.to_pylist(), right.to_pylist()
        if keys:
            def keyed(rows: list) -> dict:
                indexed = {}
                for row in rows:
                    self._check_cancel(cancel_event)
                    key = tuple(self._canonical(row[name]) for name in keys)
                    if key in indexed:
                        raise ValueError("Comparison key values are not unique; choose a unique key or compare without keys.")
                    indexed[key] = row
                return indexed
            indexed_left, indexed_right = keyed(left_rows), keyed(right_rows)
            for key in indexed_left.keys() | indexed_right.keys():
                self._check_cancel(cancel_event)
                lrow, rrow = indexed_left.get(key), indexed_right.get(key)
                if lrow is None or rrow is None:
                    differences.append({"change": "added" if lrow is None else "removed", "key": _dumps(key), "column": "*", "left": _dumps(lrow), "right": _dumps(rrow)})
                else:
                    for column in left.column_names:
                        if self._canonical(lrow[column]) != self._canonical(rrow[column]):
                            differences.append({"change": "changed", "key": _dumps(key), "column": column, "left": _dumps(lrow[column]), "right": _dumps(rrow[column])})
        else:
            counts_left = Counter(tuple(self._canonical(row[name]) for name in left.column_names) for row in left_rows)
            counts_right = Counter(tuple(self._canonical(row[name]) for name in right.column_names) for row in right_rows)
            for key in counts_left.keys() | counts_right.keys():
                self._check_cancel(cancel_event)
                delta = counts_right[key] - counts_left[key]
                if delta:
                    differences.append({"change": "added" if delta > 0 else "removed", "key": _dumps(key), "column": "*", "left": str(counts_left[key]), "right": str(counts_right[key])})
        differences.sort(key=lambda row: (row["key"], row["column"], row["change"]))
        schema = pa.schema([(name, pa.string()) for name in ("change", "key", "column", "left", "right")])
        table = pa.Table.from_pylist(differences, schema=schema)
        return {"table": table, "sql": "", "dialect": "comparison", "provenance": {"left_result_id": left_id, "right_result_id": right_id, "key_columns": keys, "comparison": "exact numeric value; unordered multiset" if not keys else "exact numeric value; unique keys"}, "summary": {"equal": not differences, "schema_equal": left.schema.equals(right.schema, check_metadata=False), "comparison_scope": "Complete result rows; numeric values compare exactly across numeric types.", "left_rows": left.num_rows, "right_rows": right.num_rows, "differences": len(differences), "key_columns": keys}}

    def shutdown(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            active_ids = [row["id"] for row in self._db.execute("SELECT id FROM jobs WHERE status NOT IN ('succeeded','failed','cancelled','interrupted')").fetchall()]
        for job_id in active_ids:
            self.cancel(job_id)
        self._pool.shutdown(wait=True, cancel_futures=True)
        with self._lock:
            self._db.close()
            self._release_process_lock()
