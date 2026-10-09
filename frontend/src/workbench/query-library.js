import React from "react";
import {FloppyDisk} from "@phosphor-icons/react/dist/csr/FloppyDisk";
import {Export} from "@phosphor-icons/react/dist/csr/Export";
import {Trash} from "@phosphor-icons/react/dist/csr/Trash";
import {Popover} from "./workspace-ui.js";
const h = React.createElement;

export function readSavedQueries(storage, key) {
  try {
    const data = JSON.parse(storage.getItem(key) || "[]");
    return Array.isArray(data) ? data.filter(query => typeof query["id"] === "string" && typeof query["name"] === "string" && typeof query["content"] === "string" && ["sql", "edn", "visual"].includes(query["format"])) : [];
  } catch { return []; }
}

export function QueryLibrary(props) {
  const key = `workbench.queries.${props["projectId"]}`;
  const [queries, setQueries] = React.useState(() => readSavedQueries(localStorage, key));
  const [name, setName] = React.useState("");
  const [selected, setSelected] = React.useState("");
  const [error, setError] = React.useState("");
  function saveQueries(items) {
    try { localStorage.setItem(key, JSON.stringify(items)); setQueries(items); setError(""); return true; }
    catch { setError("Browser storage is full or unavailable. Export the query to save it."); return false; }
  }
  function saveQuery(event) {
    event.preventDefault();
    const saved = queries.find(query => query["id"] === selected);
    const query = {...props["query"], "id": saved?.["id"] || crypto.randomUUID(), "name": name.trim(), "saved_at": new Date().toISOString()};
    if (!query["name"]) return;
    if (saveQueries([query, ...queries.filter(item => item["id"] !== query["id"])])) {
      setSelected(query["id"]); props["onNotice"](`Saved query: ${query["name"]}`);
    }
  }
  function exportQuery() {
    const blob = new Blob([JSON.stringify({...props["query"], "name": name.trim() || "Workbench query"}, null, 2)], {"type": "application/json"});
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url; anchor.download = "workbench-query.json"; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return h(Popover, {"label": "Saved queries", "trigger": h(FloppyDisk, {size: 16}), "menuClass": "query-library-menu"},
    h("div", null,
      h("h3", null, "Saved queries"),
      h("label", null, "Open query", h("select", {"aria-label": "Open saved query", "value": selected, "onChange": event => {
        setSelected(event.target.value);
        const query = queries.find(item => item["id"] === event.target.value);
        if (query) { setName(query["name"]); props["onLoad"](query); }
        else setName("");
      }}, h("option", {"value": ""}, "Save as a separate query"), queries.map(query => h("option", {"key": query["id"], "value": query["id"]}, query["name"])))),
      h("form", {"onSubmit": saveQuery}, h("label", null, "Name", h("input", {"aria-label": "Saved query name", "value": name, "maxLength": 128, "onChange": event => setName(event.target.value)})),
        h("button", {"className": "button small", "type": "submit", "disabled": !name.trim()}, h(FloppyDisk, {size: 14}), selected ? "Update saved query" : "Save query")),
      h("div", {"className": "panel-toolbar"}, h("button", {"className": "button small", "onClick": exportQuery}, h(Export, {size: 14}), "Export query"),
        selected ? h("button", {"className": "button small danger", "onClick": () => { if (saveQueries(queries.filter(query => query["id"] !== selected))) { setSelected(""); setName(""); } }}, h(Trash, {size: 14}), "Delete saved query") : null),
      error ? h("p", {"role": "alert", "className": "hint"}, error) : null,
      h("p", {"className": "hint"}, "Saved in this browser for this project. Export includes the query and its parameters.")));
}
