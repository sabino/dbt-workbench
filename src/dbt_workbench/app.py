"""Local HTTP API and bundled browser application."""

from __future__ import annotations

import csv
import io
import threading
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

from . import __version__
from .cli import default_data_dir
from .runtime import Runtime
from .workspace import FileConflict, WorkspaceManager


class OpenProject(BaseModel):
    model_config = ConfigDict(extra="forbid")
    path: str = Field(min_length=1, max_length=4096)
    profiles_dir: str | None = None


class SaveFile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    path: str = Field(min_length=1, max_length=4096)
    content: str = Field(max_length=2_000_000)
    expected_revision: str | None = None


class SubmitJob(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["parse", "build", "test", "baseline", "preview", "query", "compare"]
    options: dict[str, Any] = Field(default_factory=dict)


class ImportBaseline(BaseModel):
    model_config = ConfigDict(extra="forbid")
    artifact_dir: str = Field(min_length=1, max_length=4096)
    target: str = Field(min_length=1, max_length=128)


def create_app(data_dir: Path | str | None = None) -> FastAPI:
    directory = Path(data_dir if data_dir is not None else default_data_dir()).expanduser().resolve()
    mutations = threading.RLock()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        directory.mkdir(parents=True, exist_ok=True)
        app.state.workspaces = WorkspaceManager(directory)
        app.state.runtime = Runtime(directory, app.state.workspaces)
        try:
            yield
        finally:
            app.state.runtime.shutdown()

    app = FastAPI(title="dbt workbench", version=__version__, lifespan=lifespan)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=["localhost", "127.0.0.1", "[::1]", "testserver"])

    def require_idle(project_id: str):
        if any(job["status"] in {"queued", "running", "cancelling"} for job in app.state.runtime.list_jobs(project_id)):
            raise HTTPException(409, "Wait for this project's active jobs to finish or cancel them before changing files or reference state.")

    @app.middleware("http")
    async def local_requests(request: Request, call_next):
        # The process can run dbt and edit project files, so external websites
        # must not be able to drive this localhost service through a browser.
        origin = request.headers.get("origin")
        if origin and (origin == "null" or urlsplit(origin).netloc != request.headers.get("host")):
            return JSONResponse({"detail": "This local API accepts same-origin requests only."}, status_code=403)
        if request.headers.get("sec-fetch-site") == "cross-site":
            return JSONResponse({"detail": "Cross-site requests to the local workbench are disabled."}, status_code=403)
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "DENY"
        if request.url.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.exception_handler(FileConflict)
    async def conflict_handler(request: Request, exc: FileConflict):
        return JSONResponse({"detail": str(exc)}, status_code=409)

    @app.exception_handler(KeyError)
    async def missing_handler(request: Request, exc: KeyError):
        return JSONResponse({"detail": "The requested project, job, result, or file was not found."}, status_code=404)

    @app.exception_handler(FileNotFoundError)
    async def file_missing_handler(request: Request, exc: FileNotFoundError):
        return JSONResponse({"detail": str(exc)}, status_code=404)

    @app.exception_handler(ValueError)
    async def invalid_handler(request: Request, exc: ValueError):
        return JSONResponse({"detail": str(exc)}, status_code=400)

    @app.get("/api/health")
    def health():
        return {"status": "ok", "version": __version__, "preview_engines": ["duckdb", "sqlite"]}

    @app.get("/api/projects")
    def projects():
        return {"projects": app.state.workspaces.list_projects()}

    @app.post("/api/projects/demo", status_code=201)
    def demo():
        return app.state.workspaces.create_demo()

    @app.post("/api/projects/open", status_code=201)
    def open_project(body: OpenProject):
        return app.state.workspaces.open_project(body.path, body.profiles_dir)

    @app.get("/api/projects/{project_id}")
    def project(project_id: str):
        workspace = app.state.workspaces
        return {**workspace.get_project(project_id), "targets": workspace.targets(project_id), "baseline": workspace.baseline(project_id)}

    @app.post("/api/projects/{project_id}/baseline/import", status_code=201)
    def import_baseline(project_id: str, body: ImportBaseline):
        with mutations:
            require_idle(project_id)
            return app.state.workspaces.register_baseline(
                project_id, Path(body.artifact_dir).expanduser().resolve(), body.target
            )

    @app.get("/api/projects/{project_id}/files")
    def files(project_id: str):
        return {"files": app.state.workspaces.list_files(project_id)}

    @app.get("/api/projects/{project_id}/file")
    def read_file(project_id: str, path: str = Query(min_length=1)):
        return app.state.workspaces.read_file(project_id, path)

    @app.put("/api/projects/{project_id}/file")
    def save_file(project_id: str, body: SaveFile):
        with mutations:
            require_idle(project_id)
            return app.state.workspaces.save_file(project_id, body.path, body.content, body.expected_revision)

    @app.get("/api/projects/{project_id}/models")
    def models(project_id: str):
        return {"models": app.state.workspaces.model_list(project_id)}

    @app.post("/api/projects/{project_id}/jobs", status_code=202)
    def submit(project_id: str, body: SubmitJob):
        with mutations:
            return app.state.runtime.submit(project_id, body.kind, body.options)

    @app.get("/api/projects/{project_id}/jobs")
    def jobs(project_id: str):
        app.state.workspaces.get_project(project_id)
        return {"jobs": app.state.runtime.list_jobs(project_id)}

    @app.get("/api/jobs/{job_id}")
    def job(job_id: str):
        return app.state.runtime.get_job(job_id)

    @app.get("/api/jobs/{job_id}/events")
    def events(job_id: str, after: int = Query(default=0, ge=0)):
        app.state.runtime.get_job(job_id)
        return {"events": app.state.runtime.events(job_id, after)}

    @app.post("/api/jobs/{job_id}/cancel")
    def cancel(job_id: str):
        return app.state.runtime.cancel(job_id)

    @app.get("/api/projects/{project_id}/results")
    def results(project_id: str):
        app.state.workspaces.get_project(project_id)
        return {"results": app.state.runtime.list_results(project_id)}

    @app.get("/api/results/{result_id}")
    def result(result_id: str):
        return app.state.runtime.get_result(result_id)

    @app.get("/api/results/{result_id}/rows")
    def rows(result_id: str, offset: int = Query(default=0, ge=0), limit: int = Query(default=100, ge=1, le=1000)):
        return app.state.runtime.result_page(result_id, offset=offset, limit=limit)

    @app.get("/api/results/{result_id}/download")
    def download(result_id: str):
        metadata = app.state.runtime.get_result(result_id)

        def csv_chunks():
            buffer = io.StringIO(newline="")
            writer = csv.writer(buffer)
            writer.writerow([column["name"] for column in metadata["columns"]])
            yield buffer.getvalue()
            buffer.seek(0)
            buffer.truncate(0)
            for offset in range(0, metadata["row_count"], 1000):
                page = app.state.runtime.result_page(result_id, offset=offset, limit=1000)
                writer.writerows(page["rows"])
                yield buffer.getvalue()
                buffer.seek(0)
                buffer.truncate(0)

        return StreamingResponse(csv_chunks(), media_type="text/csv", headers={"Content-Disposition": f'attachment; filename="result-{result_id}.csv"'})

    static_dir = Path(__file__).parent / "static"
    app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")

    @app.get("/", include_in_schema=False)
    def index():
        page = static_dir / "index.html"
        if not page.exists():
            return JSONResponse({"detail": "Frontend assets are missing. Run npm ci && npm run build in frontend/."}, status_code=503)
        return FileResponse(page, headers={"Cache-Control": "no-cache"})

    return app
