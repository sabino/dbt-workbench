"""Local dbt workspaces and immutable state artifacts.

Opening a project records its location; it never copies over or normalizes a
user's project. Only an explicit save changes a project file.
"""

from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import tempfile
import threading
from typing import Iterator
import uuid

import yaml


class FileConflict(RuntimeError):
    """The file changed after the client last read it."""


_EDITABLE = {".sql", ".yaml", ".yml", ".edn"}
_EXCLUDED = {
    ".git", ".hg", ".svn", ".venv", "venv", "env", "node_modules",
    "target", "logs", "dbt_packages", "dbt_modules", "__pycache__",
    ".dbt", ".workbench", "dist", "build",
}
_TARGET_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
_LOCKS: dict[str, threading.RLock] = {}
_LOCKS_GUARD = threading.Lock()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


@contextmanager
def _locked(path: Path) -> Iterator[None]:
    """Serialize threads and processes using an independent lock file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with _LOCKS_GUARD:
        lock = _LOCKS.setdefault(str(path), threading.RLock())
    with lock, path.open("a+b") as handle:
        if os.name == "nt":
            import msvcrt

            handle.seek(0)
            if not handle.read(1):
                handle.write(b"0")
                handle.flush()
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl

            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            if os.name == "nt":
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _atomic_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        if path.exists():
            temporary_path.chmod(path.stat().st_mode & 0o777)
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _atomic_json(path: Path, value: object) -> None:
    _atomic_bytes(path, (json.dumps(value, indent=2) + "\n").encode("utf-8"))


def _load_yaml(path: Path) -> dict:
    if not path.is_file():
        return {}
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (yaml.YAMLError, UnicodeDecodeError) as exc:
        raise ValueError(f"Cannot read YAML in {path.name}.") from exc
    if not isinstance(value, dict):
        raise ValueError(f"Expected a YAML mapping in {path.name}.")
    return value


def _read_manifest(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ValueError("The dbt manifest is not valid JSON.") from exc
    if not isinstance(value, dict) or not isinstance(value.get("nodes"), dict):
        raise ValueError("The dbt manifest must contain a nodes mapping.")
    if any(not isinstance(node, dict) for node in value["nodes"].values()):
        raise ValueError("Every dbt manifest node must be a mapping.")
    if not isinstance(value.get("metadata", {}), dict):
        raise ValueError("The dbt manifest metadata must be a mapping.")
    return value


class WorkspaceManager:
    def __init__(self, data_dir: Path):
        self.data_dir = Path(data_dir).expanduser().resolve()
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self._registry_path = self.data_dir / "projects.json"
        self._registry_lock = self.data_dir / ".projects.lock"

    def _registry(self) -> dict:
        if not self._registry_path.exists():
            return {"version": 1, "projects": []}
        value = json.loads(self._registry_path.read_text(encoding="utf-8"))
        if not isinstance(value, dict) or not isinstance(value.get("projects"), list):
            raise ValueError("The workspace registry is invalid.")
        return value

    def list_projects(self) -> list[dict]:
        with _locked(self._registry_lock):
            return self._registry()["projects"]

    def get_project(self, project_id: str) -> dict:
        for project in self.list_projects():
            if project["id"] == project_id:
                return project
        raise KeyError(project_id)

    def project_data(self, project_id: str) -> Path:
        # Resolve ids through the registry before using them in a path.
        project = self.get_project(project_id)
        path = self.data_dir / "projects" / project["id"]
        path.mkdir(parents=True, exist_ok=True)
        return path

    def _project_lock(self, project_id: str) -> Path:
        return self.project_data(project_id) / ".workspace.lock"

    def open_project(self, path: str, profiles_dir: str | None = None) -> dict:
        project_path = Path(path).expanduser().resolve(strict=True)
        if not project_path.is_dir() or not (project_path / "dbt_project.yml").is_file():
            raise ValueError("Open a directory containing dbt_project.yml.")
        configuration = _load_yaml(project_path / "dbt_project.yml")
        if profiles_dir is not None:
            profile_path = Path(profiles_dir).expanduser().resolve(strict=True)
            if not profile_path.is_dir():
                raise ValueError("The profiles directory must be a directory.")
        else:
            profile_path = (
                project_path if (project_path / "profiles.yml").is_file()
                else Path.home() / ".dbt"
            ).resolve()
        with _locked(self._registry_lock):
            registry = self._registry()
            for project in registry["projects"]:
                if project["path"] == str(project_path) and project["profiles_dir"] == str(profile_path):
                    return project
            project = {
                "id": uuid.uuid4().hex,
                "name": str(configuration.get("name") or project_path.name),
                "path": str(project_path),
                "profiles_dir": str(profile_path),
                "is_demo": False,
                "created_at": _now(),
            }
            registry["projects"].append(project)
            _atomic_json(self._registry_path, registry)
            return project

    def create_demo(self) -> dict:
        with _locked(self._registry_lock):
            registry = self._registry()
            for project in registry["projects"]:
                if project.get("is_demo"):
                    return project
            project_id = "demo-jaffle-shop"
            data_path = self.data_dir / "projects" / project_id
            workspace = data_path / "workspace"
            profiles = data_path / "profiles"
            database_dir = data_path / "databases"
            data_path.mkdir(parents=True, exist_ok=True)
            # A failed initial import may leave an unpublished workspace. Recreate
            # only this application-owned demo before adding it to the registry.
            if workspace.exists():
                shutil.rmtree(workspace)
            shutil.copytree(Path(__file__).with_name("demo"), workspace)
            profiles.mkdir(exist_ok=True)
            database_dir.mkdir(exist_ok=True)
            baseline_database = database_dir / "baseline.duckdb"
            _atomic_bytes(
                profiles / "profiles.yml",
                yaml.safe_dump({
                    "jaffle_shop": {
                        "target": "dev",
                        "outputs": {
                            "baseline": {
                                "type": "duckdb", "path": str(baseline_database),
                                "schema": "main", "threads": 1,
                            },
                            "dev": {
                                "type": "duckdb", "path": str(database_dir / "dev.duckdb"),
                                "schema": "main", "threads": 1,
                                "attach": [{
                                    "path": str(baseline_database), "alias": "baseline",
                                    "read_only": True,
                                }],
                            },
                        },
                    },
                }, sort_keys=False).encode("utf-8"),
            )
            project = {
                "id": project_id, "name": "Jaffle Shop", "path": str(workspace),
                "profiles_dir": str(profiles), "is_demo": True, "created_at": _now(),
            }
            registry["projects"].append(project)
            _atomic_json(self._registry_path, registry)
            return project

    def _excluded(self, project: dict) -> set[str]:
        excluded = set(_EXCLUDED)
        config = _load_yaml(Path(project["path"]) / "dbt_project.yml")
        for key in ("target-path", "log-path", "packages-install-path"):
            value = config.get(key)
            if isinstance(value, str) and value:
                excluded.add(Path(value).parts[0])
        return excluded

    def _file_path(self, project: dict, name: str) -> Path:
        if not isinstance(name, str) or not name or "\\" in name or "\x00" in name:
            raise ValueError("Use a relative project file path.")
        relative = Path(name)
        if relative.is_absolute() or PureWindowsPath(name).is_absolute() or ".." in relative.parts:
            raise ValueError("The file must remain inside the opened project.")
        if relative.suffix.lower() not in _EDITABLE or relative.name.lower() == "profiles.yml":
            raise ValueError("Only project SQL, YAML, and EDN files can be edited; profiles are private.")
        if any(part in self._excluded(project) for part in relative.parts):
            raise ValueError("Generated and dependency files cannot be edited.")
        root = Path(project["path"])
        current = root
        for part in relative.parts:
            current = current / part
            if current.is_symlink():
                raise ValueError("Symbolic links cannot be read or edited as project files.")
        if not current.resolve().is_relative_to(root):
            raise ValueError("The file must remain inside the opened project.")
        return current

    def list_files(self, project_id: str) -> list[dict]:
        project = self.get_project(project_id)
        root = Path(project["path"])
        excluded = self._excluded(project)
        files = []
        for directory, directories, names in os.walk(root, followlinks=False):
            directories[:] = sorted(
                name for name in directories
                if name not in excluded and not (Path(directory) / name).is_symlink()
            )
            for name in sorted(names):
                path = Path(directory) / name
                if path.suffix.lower() not in _EDITABLE or name.lower() == "profiles.yml" or path.is_symlink():
                    continue
                relative = path.relative_to(root).as_posix()
                files.append({"path": relative, "name": name, "kind": path.suffix.lower()[1:]})
        return sorted(files, key=lambda item: item["path"])

    def read_file(self, project_id: str, path: str) -> dict:
        project = self.get_project(project_id)
        with _locked(self._project_lock(project_id)):
            file_path = self._file_path(project, path)
            raw = file_path.read_bytes()
            return {"path": file_path.relative_to(project["path"]).as_posix(), "content": raw.decode("utf-8"), "revision": _digest(raw)}

    def save_file(self, project_id: str, path: str, content: str, expected_revision: str | None) -> dict:
        if not isinstance(content, str):
            raise ValueError("File content must be text.")
        project = self.get_project(project_id)
        with _locked(self._project_lock(project_id)):
            file_path = self._file_path(project, path)
            current = _digest(file_path.read_bytes()) if file_path.exists() else None
            if expected_revision != current:
                raise FileConflict("The file changed since it was read. Reload it before saving.")
            if not file_path.parent.is_dir():
                raise ValueError("The file's parent directory does not exist.")
            raw = content.encode("utf-8")
            _atomic_bytes(file_path, raw)
            return {"path": file_path.relative_to(project["path"]).as_posix(), "content": content, "revision": _digest(raw)}

    def manifest(self, project_id: str) -> dict:
        path = self.project_data(project_id) / "manifest.json"
        return _read_manifest(path) if path.exists() else {}

    def register_manifest(self, project_id: str, manifest_path: Path) -> None:
        value = _read_manifest(Path(manifest_path))
        with _locked(self._project_lock(project_id)):
            _atomic_json(self.project_data(project_id) / "manifest.json", value)

    def model_list(self, project_id: str) -> list[dict]:
        project = self.get_project(project_id)
        manifest = self.manifest(project_id)
        entries = {}
        for unique_id, node in manifest.get("nodes", {}).items():
            if node.get("resource_type") != "model":
                continue
            path = node.get("original_file_path") or node.get("path")
            entries[unique_id] = {
                "unique_id": unique_id, "name": node.get("name"), "path": path,
                "materialized": node.get("config", {}).get("materialized", "view"),
                "depends_on": node.get("depends_on", {}).get("nodes", []),
                "columns": node.get("columns", {}),
            }
        config = _load_yaml(Path(project["path"]) / "dbt_project.yml")
        model_paths = config.get("model-paths", config.get("source-paths", ["models"]))
        if not isinstance(model_paths, list):
            model_paths = ["models"]
        package_name = str(config.get("name") or Path(project["path"]).name)
        source_paths = set()
        known_local_paths = {
            entry["path"] for entry in entries.values()
            if entry["unique_id"].startswith(f"model.{package_name}.")
        }
        for file in self.list_files(project_id):
            path = file["path"]
            if file["kind"] != "sql" or not any(Path(path).is_relative_to(str(folder)) for folder in model_paths):
                continue
            source_paths.add(path)
            if path not in known_local_paths:
                name = Path(path).stem
                entries[f"model.{package_name}.{name}"] = {
                    "unique_id": f"model.{package_name}.{name}", "name": name, "path": path,
                    "materialized": "unknown", "depends_on": [], "columns": {},
                }
        # Include dependency-package models from the manifest but remove deleted
        # local files, while discovering newly added models before the next parse.
        entries = {
            unique_id: entry for unique_id, entry in entries.items()
            if entry["path"] in source_paths or not unique_id.startswith(f"model.{package_name}.")
        }
        return sorted(entries.values(), key=lambda entry: (entry["name"] or "", entry["unique_id"]))

    def targets(self, project_id: str) -> list[str]:
        project = self.get_project(project_id)
        config = _load_yaml(Path(project["path"]) / "dbt_project.yml")
        profiles = _load_yaml(Path(project["profiles_dir"]) / "profiles.yml")
        profile_name = config.get("profile") or config.get("name")
        profile = profiles.get(profile_name, {}) if isinstance(profile_name, str) else {}
        outputs = profile.get("outputs", {}) if isinstance(profile, dict) else {}
        if not isinstance(outputs, dict):
            return []
        return sorted(name for name in outputs if isinstance(name, str) and _TARGET_NAME.fullmatch(name))

    def dbt_args(self, project_id: str) -> list[str]:
        project = self.get_project(project_id)
        return ["--project-dir", project["path"], "--profiles-dir", project["profiles_dir"]]

    def baseline(self, project_id: str, baseline_id: str | None = None) -> dict | None:
        path = self.project_data(project_id) / "baselines.json"
        if not path.exists():
            if baseline_id is not None:
                raise KeyError(baseline_id)
            return None
        snapshots = json.loads(path.read_text(encoding="utf-8"))
        if baseline_id is not None:
            for snapshot in snapshots:
                if snapshot["id"] == baseline_id:
                    return snapshot
            raise KeyError(baseline_id)
        return snapshots[-1] if snapshots else None

    def register_baseline(self, project_id: str, artifact_dir: Path, target: str) -> dict:
        if not isinstance(target, str) or not _TARGET_NAME.fullmatch(target):
            raise ValueError("Invalid baseline target name.")
        source = Path(artifact_dir).resolve(strict=True)
        if (self.project_data(project_id) / "baselines").resolve().is_relative_to(source):
            raise ValueError("The artifact directory cannot contain the workbench's baseline destination.")
        manifest = _read_manifest(source / "manifest.json")
        project = self.get_project(project_id)
        project_name = _load_yaml(Path(project["path"]) / "dbt_project.yml").get("name")
        manifest_name = manifest.get("metadata", {}).get("project_name")
        if manifest_name and project_name and manifest_name != project_name:
            raise ValueError(
                f"The baseline belongs to project {manifest_name!r}, not {project_name!r}."
            )
        if any(path.is_symlink() for path in source.rglob("*")):
            raise ValueError("Baseline artifacts cannot contain symbolic links.")
        with _locked(self._project_lock(project_id)):
            data_path = self.project_data(project_id)
            parent = data_path / "baselines"
            parent.mkdir(exist_ok=True)
            snapshot_id = uuid.uuid4().hex
            destination = parent / snapshot_id
            staging = Path(tempfile.mkdtemp(prefix=".snapshot-", dir=parent))
            try:
                shutil.copytree(source, staging, dirs_exist_ok=True)
                os.replace(staging, destination)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
            snapshot = {
                "id": snapshot_id, "path": str(destination), "artifact_dir": str(destination),
                "manifest_path": str(destination / "manifest.json"), "target": target,
                "created_at": _now(), "manifest_revision": _digest((destination / "manifest.json").read_bytes()),
                "data_is_snapshot": False,
                "description": "Immutable dbt reference artifacts; referenced target data remains live.",
            }
            for path in destination.rglob("*"):
                if path.is_file():
                    path.chmod(0o444)
            # Directories remain writable so application data can be removed by
            # its owner; published artifacts are never changed by this manager.
            metadata_path = data_path / "baselines.json"
            snapshots = json.loads(metadata_path.read_text(encoding="utf-8")) if metadata_path.exists() else []
            snapshots.append(snapshot)
            _atomic_json(metadata_path, snapshots)
            if not (data_path / "manifest.json").exists():
                _atomic_json(data_path / "manifest.json", manifest)
            return snapshot
