import React from "react";
import {GitPanel} from "./git-panel.js";
import {ConnectionsPanel, RoutingPanel} from "./connections-panel.js";
import {DocumentationPanel, RunsPanel} from "./catalog-panel.js";
import {useResource, ResourceState} from "./panel-utils.js";
import {OverviewPanel} from "./overview-panel.js";
import {MetadataPanel} from "./metadata-panel.js";
import {GitBranch} from "@phosphor-icons/react/dist/csr/GitBranch";
export {ResultChart, aggregateChart} from "./charts.js";

const h = React.createElement;

export function ResultTable(props) {
  const page = props["page"];
  if (!page) return null;
  return h("div", {"className": "grid-wrap"}, h("table", {"className": "result-grid"},
    h("thead", null, h("tr", null, page["columns"].map(column => h("th", {"key": column["name"]}, column["name"], h("small", null, column["type"]))))),
    h("tbody", null, page["rows"].map((row, index) => h("tr", {"key": index}, row.map((value, col) => h("td", {"key": col}, value === null ? "null" : typeof value === "object" ? JSON.stringify(value) : String(value))))))));
}

export function layoutLineage(nodes, edges) {
  const visibleIds = new Set(nodes.map(node => node["unique_id"]));
  const incoming = new Map(nodes.map(node => [node["unique_id"], edges.filter(edge => edge["target"] === node["unique_id"] && visibleIds.has(edge["source"])).map(edge => edge["source"])]));
  const depths = new Map();
  function findDepth(id, visiting) {
    if (depths.has(id)) return depths.get(id);
    if (visiting.has(id)) return 0;
    const next = new Set(visiting).add(id);
    const depth = Math.min(nodes.length, Math.max(0, ...incoming.get(id).map(parent => findDepth(parent, next) + 1)));
    depths.set(id, depth);
    return depth;
  }
  const counts = new Map();
  return nodes.map(node => {
    const depth = findDepth(node["unique_id"], new Set());
    const row = counts.get(depth) || 0;
    counts.set(depth, row + 1);
    return {...node, "x": 20 + depth * 210, "y": 30 + row * 80};
  });
}

function findDependencies(id, edges, downstream) {
  const found = new Set();
  const remaining = [id];
  while (remaining.length) {
    const next = remaining.pop();
    edges.filter(edge => edge[downstream ? "source" : "target"] === next).forEach(edge => {
      const target = edge[downstream ? "target" : "source"];
      if (target !== id && !found.has(target)) { found.add(target); remaining.push(target); }
    });
  }
  return Array.from(found);
}

function LineagePanel(props) {
  const resource = useResource(`${props["base"]}/lineage`);
  const [selected, setSelected] = React.useState(null);
  const [filter, setFilter] = React.useState("");
  const [showTests, setShowTests] = React.useState(false);
  const [zoom, setZoom] = React.useState(1);
  const graph = resource["data"];
  const nodes = layoutLineage((graph?.["nodes"] || []).filter(node => (showTests || node["resource_type"] !== "test") && `${node["name"]} ${(node["tags"] || []).join(" ")}`.toLowerCase().includes(filter.toLowerCase())), graph?.["edges"] || []);
  const indexed = new Map(nodes.map(node => [node["unique_id"], node]));
  const edges = (graph?.["edges"] || []).filter(edge => indexed.has(edge["source"]) && indexed.has(edge["target"]));
  const width = Math.max(600, ...nodes.map(node => node["x"] + 200));
  const height = Math.max(300, ...nodes.map(node => node["y"] + 70));
  const current = nodes.find(node => node["unique_id"] === selected);
  return h(ResourceState, {"resource": resource}, h("div", {"className": "inspector-content"},
    h("div", {"className": "panel-toolbar"}, h("input", {"aria-label": "Filter lineage", "placeholder": "Filter models or tags…", "value": filter, "onChange": event => setFilter(event.target.value)}), h("button", {"className": "button small", "onClick": resource["reload"]}, "Refresh")),
    h("div", {"className": "panel-toolbar"}, h("label", {"className": "check-control"}, h("input", {"type": "checkbox", "checked": showTests, "onChange": event => setShowTests(event.target.checked)}), "Show tests"), h("button", {"className": "button small", "aria-label": "Zoom out lineage", "onClick": () => setZoom(Math.max(.5, zoom - .1))}, "−"), h("button", {"className": "button small", "aria-label": "Zoom in lineage", "onClick": () => setZoom(Math.min(2, zoom + .1))}, "+"), h("span", {"className": "hint"}, `${Math.round(zoom * 100)}%`)),
    nodes.length ? h("div", {"className": "lineage-canvas"}, h("svg", {"viewBox": `0 0 ${width} ${height}`, "width": width * zoom, "height": height * zoom, "role": "img", "aria-label": "Project dependency graph"},
      edges.map(edge => { const source = indexed.get(edge["source"]); const target = indexed.get(edge["target"]); return h("path", {"key": `${edge["source"]}-${edge["target"]}`, "d": `M${source["x"] + 170},${source["y"] + 23} C${source["x"] + 195},${source["y"] + 23} ${target["x"] - 25},${target["y"] + 23} ${target["x"]},${target["y"] + 23}`, "className": "lineage-edge"}); }),
      nodes.map(node => h("g", {"key": node["unique_id"], "transform": `translate(${node["x"]},${node["y"]})`, "role": "button", "tabIndex": 0, "aria-label": `Inspect ${node["name"]}`, "onClick": () => setSelected(node["unique_id"]), "onKeyDown": event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); setSelected(node["unique_id"]); } }, "className": `lineage-node ${node["resource_type"]} ${selected === node["unique_id"] ? "selected" : ""}`},
        h("rect", {"width": 170, "height": 48, "rx": 6}), h("text", {"x": 10, "y": 20}, node["name"].slice(0, 23)), h("text", {"x": 10, "y": 36, "className": "node-caption"}, `${node["resource_type"]} · ${node["status"] || "not run"}`)))) ) : h("p", {"className": "panel-empty"}, graph?.["empty_reason"] || "No resources match this filter."),
    current ? h("section", {"className": "inspector-card"}, h("h3", null, current["name"]), h("p", {"className": "hint"}, current["description"] || "No description recorded."), h("div", {"className": "impact-counts"}, h("span", null, `${findDependencies(selected, graph["edges"], false).length} upstream`), h("span", null, `${findDependencies(selected, graph["edges"], true).length} downstream`)), current["original_file_path"] ? h("button", {"className": "button small", "onClick": () => props["onOpenFile"](current["original_file_path"])}, "Open file") : null,
      h("details", null, h("summary", null, "Downstream impact"), findDependencies(selected, graph["edges"], true).map(id => h("p", {"className": "dependency", "key": id}, id)))) : h("p", {"className": "hint"}, "Select a node to inspect dependencies and downstream impact.")));
}

export function ProjectInspector(props) {
  const base = `/api/projects/${props["project"]["id"]}`;
  const childProps = {...props, "base": base};
  const views = {"metadata": MetadataPanel, "lineage": LineagePanel, "documentation": DocumentationPanel, "git": GitPanel, "connections": ConnectionsPanel, "routing": RoutingPanel, "runs": RunsPanel, "overview": OverviewPanel};
  return h(views[props["view"]] || OverviewPanel, {...childProps, "key": `${base}-${props["view"]}`});
}

export function ProjectBranch(props) {
  const resource = useResource(`/api/projects/${props["project"]["id"]}/git?revision=${encodeURIComponent(props["notice"] || "")}`);
  const state = resource["data"];
  const branch = state?.["available"] ? state["branch"] || "Detached HEAD" : "Local project";
  return h("button", {"className": "header-branch", "aria-label": "Open source control", "title": branch, "onClick": () => props["onInspect"]("git")}, h(GitBranch, {"size": 16}), h("span", null, resource["loading"] ? "Git…" : branch));
}

export function CommandPalette(props) {
  const dialog = React.useRef(null);
  const input = React.useRef(null);
  const [search, setSearch] = React.useState("");
  const [index, setIndex] = React.useState(0);
  const files = useResource(`/api/projects/${props["project"]["id"]}/files`);
  const commands = [["metadata", "Inspect file metadata"], ["lineage", "Open lineage"], ["documentation", "Search documentation"], ["git", "Open version control"], ["connections", "Manage connections"], ["routing", "Configure environment routing"], ["runs", "Inspect runs and tests"], ["activity", "Show activity"]];
  const items = [...commands.map(([view, label]) => ({"id": view, "label": label, "view": view})), ...(files["data"]?.["files"] || []).map(file => ({"id": file["path"], "label": file["path"], "path": file["path"]}))].filter(item => item["label"].toLowerCase().includes(search.toLowerCase())).slice(0, 40);
  function showPalette() { setSearch(""); setIndex(0); dialog.current.showModal(); input.current.focus(); }
  function selectItem(item) {
    if (!item) return;
    dialog.current.close();
    if (item["path"]) props["onOpenFile"](item["path"]);
    else props["onInspect"](item["view"]);
  }
  React.useEffect(function bindPalette() {
    function handleShortcut(event) { if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") { event.preventDefault(); showPalette(); } }
    document.addEventListener("keydown", handleShortcut);
    return function unbindPalette() { document.removeEventListener("keydown", handleShortcut); };
  }, []);
  return h(React.Fragment, null,
    h("button", {"className": "command-search", "onClick": showPalette, "aria-label": "Search files and commands"}, h("span", null, "Search files or run a command…"), h("kbd", null, "⌘ K")),
    h("dialog", {"ref": dialog, "className": "command-dialog", "aria-label": "Command palette", "onClick": event => { if (event.target === dialog.current) dialog.current.close(); }},
      h("input", {"ref": input, "aria-label": "Search commands", "placeholder": "Search files and commands…", "value": search, "onChange": event => { setSearch(event.target.value); setIndex(0); }, "onKeyDown": event => {
        if (event.key === "ArrowDown") { event.preventDefault(); setIndex(Math.min(items.length - 1, index + 1)); }
        if (event.key === "ArrowUp") { event.preventDefault(); setIndex(Math.max(0, index - 1)); }
        if (event.key === "Enter") { event.preventDefault(); selectItem(items[index]); }
      }}),
      h("div", {"className": "command-list"}, items.length ? items.map((item, itemIndex) => h("button", {"className": `command-item ${itemIndex === index ? "selected" : ""}`, "key": item["id"], "onClick": () => selectItem(item)}, item["label"])) : h("p", {"className": "panel-empty"}, "No files or commands match.")),
      h("p", {"className": "hint"}, "↑ ↓ to choose · Enter to open · Esc to close")));
}
