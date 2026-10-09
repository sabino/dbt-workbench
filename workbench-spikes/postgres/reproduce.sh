#!/usr/bin/env bash
set -euo pipefail
spike_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
spike_container="workbench-postgres-spike-repro-$$"
docker_local() {
  env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
    docker --host=unix:///var/run/docker.sock "$@"
}
docker_local info --format '{{.ServerVersion}}'
if [ ! -d "$spike_dir/project" ]; then
  git clone https://github.com/dbt-labs/jaffle-shop-classic.git "$spike_dir/project"
  git -C "$spike_dir/project" checkout fd7bfacae4f497ff044a6a0275268676bf1b64c3
fi
if [ ! -x "$spike_dir/venv/bin/python" ]; then
  python -m venv "$spike_dir/venv"
fi
"$spike_dir/venv/bin/pip" install -r "$spike_dir/requirements.lock.txt"
docker_local pull postgres:16-bookworm@sha256:0ea6700a3b4f0ae6ce746519073558aed4d88a79d8d07622a9a644946c7319c4
docker_local run --rm -d --name "$spike_container" -e POSTGRES_USER=workbench \
  -e POSTGRES_PASSWORD=workbench_local_spike -e POSTGRES_DB=workbench \
  -p 127.0.0.1:55432:5432 \
  postgres:16-bookworm@sha256:0ea6700a3b4f0ae6ce746519073558aed4d88a79d8d07622a9a644946c7319c4
trap 'docker_local stop "$spike_container" >/dev/null' EXIT
for i in {1..30}; do
  if docker_local exec "$spike_container" pg_isready -U workbench -d workbench >/dev/null; then
    break
  fi
  sleep 1
done
"$spike_dir/venv/bin/python" "$spike_dir/bridge.py"
