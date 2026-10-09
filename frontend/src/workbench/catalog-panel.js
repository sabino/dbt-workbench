import React from "react";
import {Database} from "@phosphor-icons/react/dist/csr/Database";
import {BookOpen} from "@phosphor-icons/react/dist/csr/BookOpen";
import {Clock} from "@phosphor-icons/react/dist/csr/Clock";
import {useResource, ResourceState} from "./panel-utils.js";

const h = React.createElement;

function renderBadge(value) {
  return h("span", {"className": `pill status-${value || "unknown"}`}, value || "Not recorded");
}

function formatValue(value) {
  if (value === null || value === undefined || value === "") return "Not recorded";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function formatOwner(owner) {
  if (!owner) return "Not recorded";
  if (typeof owner === "string") return owner;
  return [owner["name"], owner["email"]].filter(Boolean).join(" · ") || JSON.stringify(owner);
}

function formatSeconds(value) {
  return typeof value === "number" && Number.isFinite(value) ? `${value.toLocaleString(undefined, {"maximumFractionDigits": 3})}s` : "Not recorded";
}

function formatDate(value) {
  if (!value) return "Not recorded";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function renderMetadata(entries) {
  return h("dl", {"className": "activity-metadata"}, entries.map(([label, value]) => h(React.Fragment, {"key": label}, h("dt", null, label), h("dd", null, formatValue(value)))));
}

export function DocumentationPanel(props) {
  const catalog = useResource(`${props["base"]}/catalog?detail=resources`);
  const files = useResource(`${props["base"]}/files`);
  const [search, setSearch] = React.useState("");
  const [kind, setKind] = React.useState("");
  const [tag, setTag] = React.useState("");
  const [selected, setSelected] = React.useState(props["modelId"] || "");
  const resources = catalog["data"]?.["resources"] || [];
  const kinds = Array.from(new Set(resources.map(resource => resource["resource_type"]).filter(Boolean))).sort();
  const tags = Array.from(new Set(resources.flatMap(resource => resource["tags"] || []))).sort();
  const matching = resources.filter(resource => (!kind || resource["resource_type"] === kind) && (!tag || (resource["tags"] || []).includes(tag)) &&
    [resource["name"], resource["unique_id"], resource["description"], resource["path"], formatOwner(resource["owner"]), ...(resource["tags"] || [])].join(" ").toLowerCase().includes(search.trim().toLowerCase()));
  const id = matching.find(resource => resource["unique_id"] === selected)?.["unique_id"] || matching.find(resource => resource["resource_type"] === "model")?.["unique_id"] || matching[0]?.["unique_id"];
  function selectDependency(resourceId) {
    setSearch(""); setKind(""); setTag(""); setSelected(resourceId);
  }
  return h(ResourceState, {"resource": catalog}, h("div", {"className": "inspector-content catalog-panel"},
    h("div", {"className": "panel-toolbar"}, h(BookOpen, {"size": 20, "aria-hidden": true}), h("h2", null, "Documentation & Catalog"), h("button", {"className": "button small", "onClick": catalog["reload"]}, "Refresh")),
    h("div", {"className": "chart-controls"},
      h("label", null, "Search", h("input", {"aria-label": "Search catalog", "placeholder": "Name, description, owner or path", "value": search, "onChange": event => setSearch(event.target.value)})),
      h("label", null, "Resource type", h("select", {"aria-label": "Catalog resource type", "value": kind, "onChange": event => setKind(event.target.value)}, h("option", {"value": ""}, "All types"), kinds.map(value => h("option", {"key": value, "value": value}, value.replaceAll("_", " "))))),
      h("label", null, "Tag", h("select", {"aria-label": "Catalog tag", "value": tag, "onChange": event => setTag(event.target.value)}, h("option", {"value": ""}, "All tags"), tags.map(value => h("option", {"key": value, "value": value}, value))))),
    h("p", {"className": "hint", "role": "status"}, `${matching.length} of ${resources.length} resources`),
    matching.length ? h("label", null, "Resource", h("select", {"aria-label": "Catalog resource", "value": id || "", "onChange": event => setSelected(event.target.value)}, matching.map(resource => h("option", {"key": resource["unique_id"], "value": resource["unique_id"]}, `${resource["name"]} · ${resource["resource_type"]} · ${resource["package_name"] || resource["unique_id"]}`)))) : null,
    id ? h(ResourceDocumentation, {...props, "resourceId": id, "resources": resources, "files": files["data"]?.["files"] || [], "onSelectResource": selectDependency, "key": id}) :
      h("p", {"className": "panel-empty"}, catalog["data"]?.["empty_reason"] || "No resources match these filters.")));
}

function ResourceLinks(props) {
  const ids = props["ids"] || [];
  const indexed = new Map(props["resources"].map(resource => [resource["unique_id"], resource]));
  return h("details", null, h("summary", null, `${props["label"]} (${ids.length})`), ids.length ? ids.map(id => h("p", {"key": id, "className": "dependency"},
    indexed.has(id) ? h("button", {"className": "button small", "onClick": () => props["onSelectResource"](id), "title": id}, indexed.get(id)["name"], " · ", indexed.get(id)["resource_type"]) : h("span", {"className": "mono"}, id))) : h("p", {"className": "hint"}, "None recorded."));
}

function ResourceDocumentation(props) {
  const resource = useResource(`${props["base"]}/documentation?resource_id=${encodeURIComponent(props["resourceId"])}`);
  const doc = resource["data"];
  if (!doc) return h(ResourceState, {"resource": resource}, null);
  const type = doc["resource_type"];
  const path = doc["path"] || doc["original_file_path"];
  const editable = props["files"].some(file => file["path"] === path) && (!doc["package_name"] || props["resources"].some(item => item["resource_type"] === "model" && item["package_name"] === doc["package_name"] && props["files"].some(file => file["path"] === item["path"])));
  const tests = doc["tests"] || [];
  const columns = doc["columns"] || [];
  const freshness = doc["freshness"];
  return h(ResourceState, {"resource": resource}, h("article", {"className": "catalog-resource"},
    h("section", {"className": "inspector-card"}, h("div", {"className": "panel-toolbar"}, h(Database, {"size": 18, "aria-hidden": true}), h("h2", null, doc["name"]), h("span", {"className": "pill"}, type), doc["status"] ? renderBadge(doc["status"]) : null),
      h("p", {"className": "model-description"}, doc["description"] || "No description recorded."),
      doc["source_description"] ? h("p", {"className": "hint"}, doc["source_description"]) : null,
      h("p", {"className": "mono hint"}, doc["unique_id"]),
      h("div", {"className": "tag-list"}, (doc["tags"] || []).map(value => h("span", {"className": "pill", "key": value}, value))),
      renderMetadata([["Owner", formatOwner(doc["owner"])], ["Package", doc["package_name"]], ["File", path], ["Relation", doc["relation_name"]], ["Materialization", doc["config"]?.["materialized"]], ["Tests", tests.length]]),
      h("div", {"className": "panel-toolbar"}, editable ? h("button", {"className": "button small", "onClick": () => props["onOpenFile"](path)}, "Open file") : null,
        type === "model" ? h("button", {"className": "button small", "onClick": () => props["onRun"]("preview", {"model_id": doc["unique_id"]})}, "Preview data") : null)),
    type === "source" ? h("section", {"className": "inspector-card"}, h("h3", null, "Source freshness"), freshness ? h("div", null, renderBadge(freshness["status"]),
      renderMetadata([["Latest loaded at", formatDate(freshness["max_loaded_at"])], ["Checked at", formatDate(freshness["snapshotted_at"])], ["Age", formatSeconds(freshness["max_loaded_at_time_ago_in_s"])]]),
      freshness["error"] || freshness["message"] ? h("p", {"className": "hint"}, freshness["error"] || freshness["message"]) : null) : h("p", {"className": "hint"}, "No source freshness result recorded."),
      doc["freshness_config"] ? h("details", null, h("summary", null, "Freshness thresholds"), h("pre", null, JSON.stringify(doc["freshness_config"], null, 2))) : null) : null,
    type === "exposure" ? h("section", {"className": "inspector-card"}, h("h3", null, "Exposure"), renderMetadata([["Type", doc["type"]], ["Maturity", doc["maturity"]], ["URL", doc["url"]]])) : null,
    h("h3", null, "Schema"), columns.length ? h("div", {"className": "grid-wrap"}, h("table", {"className": "result-grid"},
      h("thead", null, h("tr", null, h("th", {"scope": "col"}, "Column"), h("th", {"scope": "col"}, "Type"), h("th", {"scope": "col"}, "Description"))),
      h("tbody", null, columns.map(column => h("tr", {"key": column["name"]}, h("td", null, column["name"]), h("td", null, formatValue(column["data_type"])), h("td", null, column["description"] || "No description recorded.")))))) : h("p", {"className": "hint"}, "No columns recorded for this resource."),
    h("h3", null, "Tests"), tests.length ? tests.map(test => h("div", {"className": "test-entry", "key": test["unique_id"]}, renderBadge(test["status"]), h("div", null,
      h("button", {"className": "button small", "onClick": () => props["onSelectResource"](test["unique_id"])}, test["name"]), test["result"]?.["message"] ? h("p", {"className": "hint"}, test["result"]["message"]) : null))) : h("p", {"className": "hint"}, "No tests defined for this resource."),
    doc["result"] ? h("details", null, h("summary", null, "Recorded outcome"), renderMetadata([["Status", doc["result"]["status"]], ["Execution time", formatSeconds(doc["result"]["execution_time"])], ["Failures", doc["result"]["failures"]], ["Message", doc["result"]["message"]]])) : null,
    h(ResourceLinks, {...props, "label": "Dependencies", "ids": doc["dependencies"] || doc["depends_on"]}),
    h(ResourceLinks, {...props, "label": "Downstream resources", "ids": doc["dependents"]}),
    h("details", null, h("summary", null, type === "macro" ? "Macro source" : "Source code"), h("pre", {"tabIndex": 0, "aria-label": "Resource source code"}, doc["code"] || "No source code recorded.")),
    h("details", null, h("summary", null, "Compiled SQL"), h("pre", {"tabIndex": 0, "aria-label": "Compiled SQL"}, doc["compiled_code"] || "No compiled SQL recorded.")),
    (doc["arguments"] || []).length ? h("details", null, h("summary", null, "Arguments"), h("pre", null, JSON.stringify(doc["arguments"], null, 2))) : null,
    doc["config"] ? h("details", null, h("summary", null, "Configuration"), h("pre", null, JSON.stringify(doc["config"], null, 2))) : null,
    h("details", null, h("summary", null, "Artifact sources"), renderMetadata([["Manifest", doc["manifest_path"]], ["Catalog", doc["catalog_path"]], ["Run results", doc["run_results_path"]], ["Freshness", doc["freshness_path"]]]))));
}

function getJobId(job) {
  return job["job/id"] || job["id"];
}

function jobLabel(job) {
  return `${job["kind"]} · ${job["status"]} · ${formatDate(job["created_at"])}`;
}

export function RunsPanel(props) {
  const [selected, setSelected] = React.useState("");
  const [comparison, setComparison] = React.useState("");
  const jobs = (props["jobs"] || []).filter(job => ["parse", "build", "test", "baseline", "preview"].includes(job["kind"]));
  const current = jobs.find(job => getJobId(job) === selected) || jobs[0];
  const id = current ? getJobId(current) : null;
  const previous = current ? jobs.slice(jobs.indexOf(current) + 1) : [];
  const compareId = previous.some(job => getJobId(job) === comparison) ? comparison : "";
  return h("div", {"className": "inspector-content runs-panel"},
    h("div", {"className": "panel-toolbar"}, h(Clock, {"size": 20, "aria-hidden": true}), h("h2", null, "Runs & Tests"), ["parse", "test", "build"].map(kind => h("button", {"className": "button small", "key": kind, "onClick": () => props["onRun"](kind, {})}, kind[0].toUpperCase() + kind.slice(1)))),
    jobs.length ? h("div", {"className": "chart-controls"},
      h("label", null, "Run history", h("select", {"aria-label": "Run history", "value": id || "", "onChange": event => { setSelected(event.target.value); setComparison(""); }}, jobs.map(job => h("option", {"value": getJobId(job), "key": getJobId(job)}, jobLabel(job))))),
      h("label", null, "Compare with previous run", h("select", {"aria-label": "Compare previous run", "value": compareId, "disabled": !previous.length, "onChange": event => setComparison(event.target.value)}, h("option", {"value": ""}, previous.length ? "Choose a previous run" : "No previous runs"), previous.map(job => h("option", {"value": getJobId(job), "key": getJobId(job)}, jobLabel(job)))))) : null,
    id ? h(RunDetails, {...props, "jobId": id, "comparisonId": compareId, "key": `${id}-${current["status"]}`}) : h("p", {"className": "panel-empty"}, "Build or test the project to inspect run artifacts."));
}

function collectOutcomes(data) {
  return (data["runs"] || []).flatMap(run => (run["results"] || []).map(result => ({...result, "run_name": run["name"]})));
}

function sumTiming(results) {
  const times = results.map(result => result["execution_time"]).filter(value => typeof value === "number" && Number.isFinite(value));
  return times.length ? times.reduce((sum, value) => sum + value, 0) : null;
}

function summarizeOutcomes(data) {
  const results = collectOutcomes(data).filter(result => ["model", "test"].includes(result["resource_type"]));
  return results.reduce((summary, result) => {
    const id = result["unique_id"];
    const previous = summary.get(id) || {"name": result["name"] || id, "type": result["resource_type"], "results": []};
    summary.set(id, {...previous, "results": [...previous["results"], result]});
    return summary;
  }, new Map());
}

function ComparisonSummary(props) {
  const comparison = useResource(`${props["base"]}/runs/${encodeURIComponent(props["jobId"])}`);
  const current = summarizeOutcomes(props["current"]);
  const previous = comparison["data"] ? summarizeOutcomes(comparison["data"]) : new Map();
  const ids = Array.from(new Set([...current.keys(), ...previous.keys()])).sort();
  function renderOutcome(outcome) {
    return outcome ? h("div", null, outcome["results"].map((result, index) => h("span", {"key": index, "title": result["run_name"]}, renderBadge(result["status"]), " ")), h("small", {"className": "hint"}, formatSeconds(sumTiming(outcome["results"])))) : "Not recorded";
  }
  return h("section", {"className": "inspector-card"}, h("h3", null, "Run comparison"), h(ResourceState, {"resource": comparison},
    h("p", {"className": "hint"}, "Recorded model and test outcomes only. Repeated resources show each invocation; timing sums recorded execution times."),
    comparison["data"] ? renderMetadata([["Selected run", props["current"]["job"]["id"]], ["Previous run", comparison["data"]["job"]["id"]]]) : null,
    ids.length ? h("div", {"className": "grid-wrap"}, h("table", {"className": "result-grid"}, h("thead", null, h("tr", null, h("th", {"scope": "col"}, "Resource"), h("th", {"scope": "col"}, "Selected run"), h("th", {"scope": "col"}, "Previous run"))),
      h("tbody", null, ids.map(id => h("tr", {"key": id}, h("td", {"title": id}, (current.get(id) || previous.get(id))["name"], h("small", null, (current.get(id) || previous.get(id))["type"])), h("td", null, renderOutcome(current.get(id))), h("td", null, renderOutcome(previous.get(id)))))))) : h("p", {"className": "hint"}, "No model or test outcomes recorded for these runs.")));
}

function RunDetails(props) {
  const resource = useResource(`${props["base"]}/runs/${encodeURIComponent(props["jobId"])}`);
  const [failedOnly, setFailedOnly] = React.useState(false);
  const [type, setType] = React.useState("");
  const data = resource["data"];
  if (!data) return h(ResourceState, {"resource": resource}, null);
  const job = data["job"];
  const results = collectOutcomes(data);
  const visible = results.filter(result => (!failedOnly || ["fail", "error"].includes(result["status"])) && (!type || result["resource_type"] === type));
  return h(ResourceState, {"resource": resource}, h("div", null,
    h("section", {"className": "inspector-card"}, h("div", {"className": "panel-toolbar"}, h("h3", null, `${job["kind"]} · ${job["id"].slice(0, 8)}`), renderBadge(job["status"]), h("button", {"className": "button small", "onClick": resource["reload"]}, "Refresh")),
      renderMetadata([["Started", formatDate(job["started_at"])], ["Finished", formatDate(job["finished_at"])], ["Recorded outcomes", results.length], ["Test outcomes", (data["tests"] || []).length], ["Execution time", formatSeconds(sumTiming(results))]]),
      h("div", {"className": "tag-list", "aria-label": "Test outcome summary"}, Object.entries(data["test_summary"]?.["statuses"] || {}).map(([status, count]) => h("span", {"className": "pill", "key": status}, `${count} ${status}`))),
      job["error"] ? h("p", {"className": "hint", "role": "alert"}, job["error"]) : null),
    props["comparisonId"] ? h(ComparisonSummary, {...props, "current": data, "jobId": props["comparisonId"], "key": props["comparisonId"]}) : null,
    h("div", {"className": "panel-toolbar"}, h("label", {"className": "check-control"}, h("input", {"type": "checkbox", "checked": failedOnly, "onChange": event => setFailedOnly(event.target.checked)}), "Failed outcomes only"),
      h("label", null, "Outcome type", h("select", {"aria-label": "Run outcome type", "value": type, "onChange": event => setType(event.target.value)}, h("option", {"value": ""}, "All resources"), h("option", {"value": "model"}, "Models"), h("option", {"value": "test"}, "Tests")))),
    h("h3", null, "Outcomes"), visible.length ? visible.map((result, index) => h("article", {"className": "test-entry", "key": `${result["run_name"]}-${result["unique_id"]}-${index}`}, renderBadge(result["status"]), h("div", null,
      h("strong", null, result["name"] || result["unique_id"]), h("p", {"className": "hint"}, `${result["run_name"]} · ${result["resource_type"] || "Resource"} · ${formatSeconds(result["execution_time"])}`),
      typeof result["failures"] === "number" ? h("small", null, `${result["failures"]} failures`) : null,
      result["message"] ? h("p", {"className": "hint"}, result["message"]) : null,
      result["timing"]?.length ? h("details", null, h("summary", null, "Timing"), result["timing"].map((timing, timingIndex) => h("p", {"className": "hint", "key": timingIndex}, `${timing["name"]}: ${formatDate(timing["started_at"])} → ${formatDate(timing["completed_at"])}`))) : null))) : h("p", {"className": "hint"}, results.length ? "No outcomes match these filters." : "No resource outcomes recorded."),
    h("h3", null, "Artifacts"), (data["runs"] || []).map(run => h("section", {"key": run["name"], "className": "inspector-card"}, h("h4", null, run["name"]),
      run["elapsed_time"] !== null && run["elapsed_time"] !== undefined ? h("p", {"className": "hint"}, `Elapsed: ${formatSeconds(run["elapsed_time"])}`) : null,
      (run["artifacts"] || []).length ? run["artifacts"].map(artifact => h("a", {"key": artifact["name"], "className": "artifact-link", "download": artifact["name"], "href": `/api/jobs/${encodeURIComponent(job["id"])}/artifacts?run=${encodeURIComponent(run["name"])}&name=${encodeURIComponent(artifact["name"])}`}, artifact["name"], h("small", null, `${artifact["size_bytes"].toLocaleString()} bytes`))) : h("p", {"className": "hint"}, "No artifacts recorded for this invocation."))),
    data["empty_reason"] ? h("p", {"className": "hint"}, data["empty_reason"]) : null));
}
