import React from "react";
import {ChartBar} from "@phosphor-icons/react/dist/csr/ChartBar";
import {GitBranch} from "@phosphor-icons/react/dist/csr/GitBranch";
import {WarningCircle} from "@phosphor-icons/react/dist/csr/WarningCircle";
import {ArrowSquareOut} from "@phosphor-icons/react/dist/csr/ArrowSquareOut";
import {useResource, ResourceState} from "./panel-utils.js";

const h = React["createElement"];

function getJobId(job) {
  return job["job/id"] || job["id"];
}

function formatDate(value) {
  if (!value) return "Time not recorded";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function renderBadge(status) {
  return h("span", {"className": `pill status-${status || "unknown"}`}, status || "Not recorded");
}

function collectImpact(id, edges, downstream) {
  function visit(frontier, visited) {
    const reached = edges.filter(edge => frontier.has(edge[downstream ? "source" : "target"]))
      .map(edge => edge[downstream ? "target" : "source"])
      .filter(next => next !== id && !visited.has(next));
    return reached.length ? visit(new Set(reached), new Set([...visited, ...reached])) : Array.from(visited);
  }
  return visit(new Set([id]), new Set());
}

function navigate(props, view) {
  if (props["onInspect"]) props["onInspect"](view);
}

function LatestTests(props) {
  const jobs = props["jobs"];
  const [index, setIndex] = React["useState"](0);
  const currentIndex = Math.min(index, jobs.length - 1);
  const job = jobs[currentIndex];
  const resource = useResource(`${props["base"]}/runs/${encodeURIComponent(getJobId(job))}`);
  const data = resource["data"];
  const matches = data?.["job"]?.["id"] === getJobId(job);
  const tests = matches ? data["tests"] || [] : [];
  const hasNext = currentIndex < jobs.length - 1;
  React["useEffect"](function inspectEarlierTests() {
    if (matches && !tests.length && hasNext) setIndex(currentIndex + 1);
  }, [matches, tests.length, hasNext, currentIndex]);
  const failures = tests.filter(test => ["fail", "error"].includes(test["status"]));
  const counts = data?.["test_summary"]?.["statuses"] || {};
  return h("section", {"className": "inspector-card overview-tests"},
    h("div", {"className": "panel-toolbar"}, h(WarningCircle, {"size": 18, "aria-hidden": true}), h("h3", null, "Latest recorded tests"), h("button", {"className": "button small", "onClick": () => navigate(props, "runs")}, "Inspect runs")),
    h(ResourceState, {"resource": resource}, (!matches || (!tests.length && hasNext)) ? h("p", {"className": "hint", "role": "status"}, "Looking for recorded test outcomes…") : tests.length ? h("div", null,
      h("p", {"className": "hint"}, `${job["kind"]} · ${formatDate(job["finished_at"] || job["created_at"])} · ${tests.length} recorded test outcomes`),
      h("div", {"className": "tag-list", "aria-label": "Recorded test status counts"}, Object.entries(counts).map(([status, count]) => h("span", {"className": "pill", "key": status}, `${count} ${status}`))),
      failures.length ? failures.map((test, testIndex) => h("article", {"className": "test-entry", "key": `${test["unique_id"]}-${testIndex}`}, renderBadge(test["status"]), h("div", null,
        h("strong", null, test["name"] || test["unique_id"]), test["column_name"] ? h("p", {"className": "hint"}, `Column: ${test["column_name"]}`) : null,
        typeof test["failures"] === "number" ? h("p", {"className": "hint"}, `${test["failures"]} failures`) : null,
        test["message"] ? h("p", {"className": "hint"}, test["message"]) : null))) : h("p", {"className": "hint"}, "No failed or errored tests in this recorded run.")) :
        h("p", {"className": "hint"}, "No test outcomes recorded in completed build or test jobs.")));
}

function ModelImpact(props) {
  const graph = props["graph"];
  const nodes = graph["nodes"] || [];
  const models = nodes.filter(node => node["resource_type"] === "model");
  const [chosen, setChosen] = React["useState"]("");
  React["useEffect"](function followSelectedModel() { setChosen(""); }, [props["modelId"]]);
  const model = models.find(node => node["unique_id"] === chosen) || models.find(node => node["unique_id"] === props["modelId"]) || models[0];
  if (!model) return h("section", {"className": "inspector-card overview-impact"}, h("h3", null, "Model impact"), h("p", {"className": "hint"}, "Parse or build the project to inspect model dependencies."));
  const edges = graph["edges"] || [];
  const upstream = collectImpact(model["unique_id"], edges, false);
  const downstream = collectImpact(model["unique_id"], edges, true);
  const indexed = new Map(nodes.map(node => [node["unique_id"], node]));
  function renderResources(ids, label) {
    return h("details", null, h("summary", null, `${label} (${ids.length})`), ids.length ? ids.map(id => h("p", {"className": "dependency", "key": id}, indexed.get(id)?.["name"] || id, indexed.get(id)?.["resource_type"] ? ` · ${indexed.get(id)["resource_type"]}` : "")) : h("p", {"className": "hint"}, "None recorded."));
  }
  return h("section", {"className": "inspector-card overview-impact"}, h("div", {"className": "panel-toolbar"}, h(GitBranch, {"size": 18, "aria-hidden": true}), h("h3", null, "Model impact"),
    h("button", {"className": "button small", "onClick": () => navigate(props, "lineage")}, "Open lineage")),
    h("label", null, "Selected model", h("select", {"aria-label": "Overview model impact", "value": model["unique_id"], "onChange": event => setChosen(event["target"]["value"])}, models.map(node => h("option", {"key": node["unique_id"], "value": node["unique_id"]}, node["name"])))),
    model["description"] ? h("p", {"className": "hint"}, model["description"]) : null,
    h("div", {"className": "impact-counts"}, h("span", null, `${upstream.length} upstream resources`), h("span", null, `${downstream.length} downstream resources`)),
    renderResources(upstream, "Upstream"), renderResources(downstream, "Downstream"),
    props["files"].some(file => file["path"] === model["original_file_path"]) ? h("button", {"className": "button small", "onClick": () => props["onOpenFile"](model["original_file_path"])}, "Open model file") : null);
}

function LocalChanges(props) {
  const git = props["git"];
  const files = props["files"];
  const state = git["data"];
  const editable = new Set((files["data"]?.["files"] || []).map(file => file["path"]));
  const sourcePaths = new Set([...editable, ...(props["nodes"] || []).map(node => node["original_file_path"]).filter(Boolean)]);
  const changes = (state?.["changes"] || []).filter(change => sourcePaths.has(change["path"]));
  return h("section", {"className": "inspector-card overview-changes"}, h("div", {"className": "panel-toolbar"}, h(GitBranch, {"size": 18, "aria-hidden": true}), h("h3", null, "Local source changes"), h("button", {"className": "button small", "onClick": () => navigate(props, "git")}, "Version control")),
    h(ResourceState, {"resource": git}, !state?.["available"] ? h("p", {"className": "hint"}, state?.["reason"] || "Source control is unavailable for this project.") :
      h(ResourceState, {"resource": files}, h("p", {"className": "hint"}, `${state["branch"] || "Detached HEAD"} · ${changes.length} project source changes`),
        changes.length ? changes.map(change => h("div", {"className": "git-change", "key": change["path"]}, h("span", {"className": "pill", "title": "Git index and working-tree status"}, `${change["index_status"]}${change["working_status"]}`),
          h("span", {"className": "mono"}, change["path"]), editable.has(change["path"]) ? h("button", {"className": "button small", "aria-label": `Open changed file ${change["path"]}`, "onClick": () => props["onOpenFile"](change["path"])}, "Open file") : h("small", {"className": "hint"}, "Unavailable on disk"))) : h("p", {"className": "hint"}, "No changed project source files recorded."))));
}

export function OverviewPanel(props) {
  const lineage = useResource(`${props["base"]}/lineage`);
  const git = useResource(`${props["base"]}/git`);
  const files = useResource(`${props["base"]}/files`);
  const [revision, setRevision] = React["useState"](0);
  const jobs = props["jobs"] || [];
  const dbtJobs = jobs.filter(job => ["parse", "build", "test", "baseline", "preview"].includes(job["kind"]));
  const testJobs = dbtJobs.filter(job => ["build", "test"].includes(job["kind"]) && ["succeeded", "failed", "cancelled", "interrupted"].includes(job["status"]));
  const graph = lineage["data"];
  const nodes = graph?.["nodes"] || [];
  function refreshOverview() { lineage["reload"](); git["reload"](); files["reload"](); setRevision(value => value + 1); }
  return h("div", {"className": "inspector-content overview-panel"},
    h("div", {"className": "panel-toolbar"}, h("h2", null, props["project"]["name"]), h("button", {"className": "button small", "onClick": refreshOverview}, "Refresh")),
    h("p", {"className": "hint mono"}, props["project"]["path"]),
    h(ResourceState, {"resource": lineage}, h("div", {"className": "overview-metrics"}, [["Models", "model"], ["Tests", "test"], ["Sources", "source"]].map(([label, type]) => h("div", {"key": type}, h("strong", null, String(nodes.filter(node => node["resource_type"] === type).length)), h("span", null, label))),
      h("div", null, h("strong", null, String(dbtJobs.length)), h("span", null, "dbt jobs"))),
      graph?.["empty_reason"] ? h("p", {"className": "hint"}, graph["empty_reason"]) : null),
    h("section", {"className": "inspector-card overview-runs"}, h("div", {"className": "panel-toolbar"}, h(ChartBar, {"size": 18, "aria-hidden": true}), h("h3", null, "Recent dbt jobs"), h("button", {"className": "button small", "onClick": () => navigate(props, "runs")}, "All runs")),
      dbtJobs.length ? dbtJobs.slice(0, 6).map(job => h("button", {"className": "overview-run commit-entry", "key": getJobId(job), "onClick": () => navigate(props, "runs"), "aria-label": `Inspect ${job["kind"]} run from ${formatDate(job["created_at"])}`},
        h("span", {"className": "panel-toolbar"}, h("strong", null, job["kind"]), renderBadge(job["status"]), h(ArrowSquareOut, {"size": 14, "aria-hidden": true})), h("span", {"className": "hint"}, formatDate(job["created_at"])))) : h("p", {"className": "hint"}, "No dbt jobs recorded. Parse, build, or test the project to inspect a run.")),
    testJobs.length ? h(LatestTests, {...props, "jobs": testJobs, "key": `${revision}-${testJobs.map(job => `${getJobId(job)}-${job["status"]}`).join("|")}`}) :
      h("section", {"className": "inspector-card overview-tests"}, h("h3", null, "Latest recorded tests"), h("p", {"className": "hint"}, "No completed build or test jobs recorded.")),
    h(LocalChanges, {...props, "git": git, "files": files, "nodes": nodes}),
    h(ResourceState, {"resource": lineage}, graph ? h(ModelImpact, {...props, "graph": graph, "files": files["data"]?.["files"] || []}) : null));
}
