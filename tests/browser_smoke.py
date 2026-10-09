"""Real browser workflow. Defaults to an isolated temporary application.

Run: uv run python tests/browser_smoke.py --output /tmp/workbench-browser
Use --base-url only for a disposable demo: this test edits its orders model.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen

from playwright.sync_api import expect, sync_playwright


@contextmanager
def server(base_url):
    if base_url:
        yield base_url
        return
    with tempfile.TemporaryDirectory(prefix="dbt-workbench-browser-") as data_dir:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        process = subprocess.Popen(
            [sys.executable, "-m", "dbt_workbench", "--data-dir", data_dir, "--port", str(port)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        address = f"http://127.0.0.1:{port}"
        try:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError("Workbench server exited during startup")
                try:
                    with urlopen(address + "/api/health", timeout=1):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError("Workbench server did not become ready")
            yield address
        finally:
            process.terminate()
            process.wait(timeout=10)


def verify(base_url: str, output: Path):
    output.mkdir(parents=True, exist_ok=True)
    evidence = {"jobs": [], "browser_errors": []}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1512, "height": 1000})
        page.on("pageerror", lambda error: evidence["browser_errors"].append(str(error)))
        page.goto(base_url)
        expect(page.get_by_role("button", name="Create Jaffle demo", exact=False)).to_be_visible()
        page.screenshot(path=str(output / "welcome.png"), full_page=True)
        page.get_by_role("button", name="Create Jaffle demo", exact=False).click()
        expect(page.get_by_label("Target", exact=True)).to_be_visible()
        previous_jobs = len(page.request.get(base_url + "/api/projects/demo-jaffle-shop/jobs").json()["jobs"])

        def run(action, *, success=True):
            with page.expect_response(lambda response: response.request.method == "POST" and response.url.endswith("/jobs")) as submitted:
                action()
            response = submitted.value
            assert response.status == 202, response.text()
            job = response.json()
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                job = page.request.get(base_url + "/api/jobs/" + job["id"]).json()
                if job["status"] in {"succeeded", "failed", "cancelled", "interrupted"}:
                    break
                page.wait_for_timeout(100)
            evidence["jobs"].append(job)
            if success:
                assert job["status"] == "succeeded", job
            expect(page.locator(".job-row.active")).to_contain_text(job["status"], timeout=10000)
            if job["result_id"]:
                expect(page.get_by_label("Saved result")).to_have_value(job["result_id"], timeout=10000)
            return job

        run(lambda: page.get_by_role("button", name=re.compile(r"^(Build|Rebuild) baseline$")).click())
        page.locator(".model-row[title='models/orders.sql']").click()
        expect(page.get_by_label("Model file editor")).to_contain_text("select * from final")
        page.get_by_label("Saved baseline version", exact=True).click()
        expect(page.get_by_label("Saved baseline version", exact=True)).to_be_checked()
        baseline = run(lambda: page.get_by_role("button", name="▷ Preview", exact=True).click())
        baseline_rows = page.request.get(base_url + "/api/results/" + baseline["result_id"] + "/rows").json()
        assert baseline_rows["row_count"] == 99

        page.get_by_role("button", name="Model SQL", exact=True).click()
        editor = page.get_by_label("Model file editor")
        original = editor.input_value()
        assert "where status = 'completed'" not in original, "Use a fresh disposable demo for this test"
        editor.fill(original.replace("select * from final", "select * from final where status = 'completed'"))
        page.get_by_role("button", name="Save file", exact=True).click()
        expect(page.get_by_role("status")).to_contain_text("File saved")
        page.get_by_label("Prefer baseline relations", exact=True).click()
        expect(page.get_by_label("Prefer baseline relations", exact=True)).to_be_checked()
        page.get_by_label("Saved baseline version", exact=True).click()
        expect(page.get_by_label("Saved baseline version", exact=True)).not_to_be_checked()
        run(lambda: page.get_by_role("button", name="▷ Build selected", exact=True).click())
        changed = run(lambda: page.get_by_role("button", name="▷ Preview", exact=True).click())
        changed_rows = page.request.get(base_url + "/api/results/" + changed["result_id"] + "/rows").json()
        assert 0 < changed_rows["row_count"] < baseline_rows["row_count"]

        page.get_by_role("button", name="Query lab", exact=True).click()
        native = run(lambda: page.get_by_role("button", name="▷ Run query", exact=True).click())
        page.get_by_role("button", name="Query lab", exact=True).click()
        page.get_by_label("Execution dialect", exact=True).select_option("sqlite")
        translated = run(lambda: page.get_by_role("button", name="▷ Run query", exact=True).click())

        def compare(left, right):
            page.locator(".comparison summary").click()
            page.get_by_label("Left result", exact=True).select_option(left["result_id"])
            page.get_by_label("Right result", exact=True).select_option(right["result_id"])
            return run(lambda: page.get_by_role("button", name="Compare →", exact=True).click())

        same = compare(native, translated)
        assert same["summary"]["equal"] is True
        expect(page.get_by_text("Rows match", exact=True)).to_be_visible()
        # React preserves the open details element across result changes.
        if not page.get_by_label("Left result", exact=True).is_visible():
            page.locator(".comparison summary").click()
        page.get_by_label("Left result", exact=True).select_option(baseline["result_id"])
        page.get_by_label("Right result", exact=True).select_option(changed["result_id"])
        different = run(lambda: page.get_by_role("button", name="Compare →", exact=True).click())
        assert different["summary"]["equal"] is False

        page.get_by_role("button", name="Query lab", exact=True).click()
        page.get_by_role("button", name="SQL", exact=True).click()
        page.get_by_label("Execution dialect", exact=True).select_option("duckdb")
        # The SQL default must have its own empty parameter map.
        sql_result = run(lambda: page.get_by_role("button", name="▷ Run query", exact=True).click())
        expect(page.locator(".result-grid tbody tr")).not_to_have_count(0)
        with page.expect_download() as downloading:
            page.get_by_role("link", name="Download CSV", exact=False).click()
        download = downloading.value
        download.save_as(str(output / "result.csv"))
        assert (output / "result.csv").read_text().startswith("customer_id,revenue")
        page.screenshot(path=str(output / "workbench.png"), full_page=True)
        page.get_by_role("button", name="Query lab", exact=True).click()
        page.get_by_label("SQL query editor").fill(
            "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 1000000000) SELECT SUM(n) AS value FROM t"
        )
        with page.expect_response(lambda response: response.request.method == "POST" and response.url.endswith("/jobs")) as submitted:
            page.get_by_role("button", name="▷ Run query", exact=True).click()
        long_job = submitted.value.json()
        page.get_by_role("button", name="Cancel", exact=True).click(timeout=10000)
        for _ in range(100):
            long_job = page.request.get(base_url + "/api/jobs/" + long_job["id"]).json()
            if long_job["status"] == "cancelled":
                break
            page.wait_for_timeout(100)
        assert long_job["status"] == "cancelled", long_job
        evidence["jobs"].append(long_job)
        page.get_by_label("SQL query editor").fill("SELECT 42 AS value")
        recovered = run(lambda: page.get_by_role("button", name="▷ Run query", exact=True).click())
        expect(page.locator(".result-grid tbody tr td").last).to_have_text("42")
        page.get_by_role("button", name="Model SQL", exact=True).click()
        page.screenshot(path=str(output / "model.png"), full_page=True)
        page.reload()
        page.get_by_role("button", name="Create Jaffle demo", exact=False).click()
        expect(page.locator(".job-row")).to_have_count(previous_jobs + len(evidence["jobs"]), timeout=10000)
        assert not evidence["browser_errors"], evidence["browser_errors"]
        evidence.update(baseline_rows=baseline_rows["row_count"], changed_rows=changed_rows["row_count"],
                        cross_engine_rows_equal=same["summary"]["equal"], edited_rows_equal=different["summary"]["equal"])
        (output / "evidence.json").write_text(json.dumps(evidence, indent=2))
        print(json.dumps({"jobs": len(evidence["jobs"]), "baseline_rows": evidence["baseline_rows"], "changed_rows": evidence["changed_rows"], "browser_errors": evidence["browser_errors"], "output": str(output)}))
        browser.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url")
    parser.add_argument("--output", type=Path, default=Path("/tmp/workbench-browser"))
    options = parser.parse_args()
    with server(options.base_url) as url:
        verify(url, options.output)
