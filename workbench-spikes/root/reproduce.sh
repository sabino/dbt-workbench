#!/usr/bin/env bash
set -euo pipefail
# Extract the evidence archive under /tmp first. Keep inherited proxy settings.
uv venv /tmp/dbt-workbench-feasibility-venv
uv pip install --python /tmp/dbt-workbench-feasibility-venv/bin/python dbt-core==1.12.5 dbt-duckdb==1.11.0 duckdb==1.5.6
uv venv /tmp/workbench-spikes/root/.venv
uv pip install --python /tmp/workbench-spikes/root/.venv/bin/python -r /tmp/workbench-spikes/root/requirements.txt
if [ ! -d /tmp/workbench-spikes/jaffle-shop-classic/.git ]; then
  git clone https://github.com/dbt-labs/jaffle-shop-classic.git /tmp/workbench-spikes/jaffle-shop-classic
fi
git -C /tmp/workbench-spikes/jaffle-shop-classic checkout fd7bfacae4f497ff044a6a0275268676bf1b64c3
/tmp/workbench-spikes/root/.venv/bin/python /tmp/workbench-spikes/root/probe.py
/tmp/workbench-spikes/root/.venv/bin/python /tmp/workbench-spikes/root/jaffle_probe.py
