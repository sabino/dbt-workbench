"""Command line entry point. Runtime needs only Python and the installed package."""

import argparse
import os
from pathlib import Path

import uvicorn


def default_data_dir() -> Path:
    configured = os.environ.get("DBT_WORKBENCH_DATA_DIR")
    if configured:
        return Path(configured).expanduser()
    base = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local" / "share")))
    return base / "dbt-workbench"


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the local dbt workbench")
    parser.add_argument("--host", choices=["127.0.0.1", "localhost", "::1"], default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--data-dir", type=Path, default=default_data_dir())
    parser.add_argument("--version", action="version", version="dbt-workbench 0.1.0")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    from .app import create_app

    url_host = f"[{args.host}]" if ":" in args.host else args.host
    print(f"dbt workbench: http://{url_host}:{args.port}")
    print(f"Workspace data: {args.data_dir.expanduser().resolve()}")
    uvicorn.run(create_app(args.data_dir), host=args.host, port=args.port, log_level="info", access_log=False)

