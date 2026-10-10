import React from "react";
import {Info} from "@phosphor-icons/react/dist/csr/Info";
import {useResource, ResourceState} from "./panel-utils.js";

const h = React["createElement"];

function isWord(char) { return Boolean(char) && /[A-Za-z0-9_$]/.test(char); }

function scanQuoted(text, start, quote) {
  let value = "";
  let index = start + 1;
  while (index < text.length) {
    if (text[index] === quote) {
      if (text[index + 1] === quote) { value += quote; index += 2; }
      else return {"value": value, "end": index + 1, "closed": true};
    } else if (text[index] === "\\" && index + 1 < text.length) { value += text[index + 1]; index += 2; }
    else { value += text[index]; index += 1; }
  }
  return {"value": value, "end": index, "closed": false};
}

function scanTemplate(text, start, close) {
  let index = start + 2;
  while (index < text.length) {
    if (text[index] === "'" || text[index] === '"') index = scanQuoted(text, index, text[index])["end"];
    else if (text.startsWith(close, index)) return {"value": text.slice(start + 2, index), "end": index + 2, "closed": true};
    else index += 1;
  }
  return {"value": text.slice(start + 2), "end": text.length, "closed": false};
}

export function tokenizeDraft(text, templates = true) {
  const tokens = [];
  const diagnostics = [];
  const comments = [];
  let index = 0;
  while (index < text.length) {
    const start = index;
    const char = text[index];
    if (/\s/.test(char)) { index += 1; continue; }
    if (text.startsWith("--", index)) {
      const end = text.indexOf("\n", index);
      comments.push({start, end: end === -1 ? text.length : end});
      index = end === -1 ? text.length : end + 1;
      continue;
    }
    if (text.startsWith("/*", index)) {
      let depth = 1;
      index += 2;
      while (index < text.length && depth) {
        if (text.startsWith("/*", index)) { depth += 1; index += 2; }
        else if (text.startsWith("*/", index)) { depth -= 1; index += 2; }
        else index += 1;
      }
      if (depth) diagnostics.push("An SQL comment is incomplete.");
      comments.push({start, end: index});
      continue;
    }
    if (templates && text.startsWith("{#", index)) {
      const end = text.indexOf("#}", index + 2);
      if (end === -1) diagnostics.push("A Jinja comment is incomplete.");
      index = end === -1 ? text.length : end + 2;
      comments.push({start, end: index});
      continue;
    }
    if (templates && (text.startsWith("{{", index) || text.startsWith("{%", index))) {
      const block = text.startsWith("{%", index);
      const part = scanTemplate(text, index, block ? "%}" : "}}");
      if (block && /^-?\s*raw\s*-?$/.test(part["value"])) {
        const ending = /\{%-?\s*endraw\s*-?%\}/g;
        ending.lastIndex = part["end"];
        const match = ending.exec(text);
        index = match ? match.index + match[0].length : text.length;
        comments.push({start, end: index});
        continue;
      }
      tokens.push({"kind": block ? "template_block" : "template", "value": part["value"], "start": start, "end": part["end"]});
      if (!part["closed"]) diagnostics.push("A Jinja expression is incomplete.");
      index = part["end"]; continue;
    }
    if (char === "'" || char === '"' || char === "`") {
      const part = scanQuoted(text, index, char);
      tokens.push({"kind": char === "'" || !templates ? "string" : "identifier", "value": part["value"], "quoted": true, "start": start, "end": part["end"]});
      if (!part["closed"]) diagnostics.push("A quoted value is incomplete.");
      index = part["end"]; continue;
    }
    if (templates && char === "$") {
      const match = text.slice(index).match(/^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$/);
      if (match) {
        const delimiter = match[0];
        const end = text.indexOf(delimiter, index + delimiter.length);
        tokens.push({"kind": "string", "value": text.slice(index + delimiter.length, end === -1 ? text.length : end), "start": start, "end": end === -1 ? text.length : end + delimiter.length});
        if (end === -1) diagnostics.push("A dollar-quoted value is incomplete.");
        index = end === -1 ? text.length : end + delimiter.length; continue;
      }
    }
    if (/[A-Za-z_$]/.test(char)) {
      index += 1;
      while (isWord(text[index])) index += 1;
      tokens.push({"kind": "identifier", "value": text.slice(start, index), "quoted": false, "start": start, "end": index}); continue;
    }
    if (/[0-9]/.test(char)) {
      const match = text.slice(index).match(/^\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/);
      index += match[0].length;
      tokens.push({"kind": "number", "value": match[0], "start": start, "end": index}); continue;
    }
    index += 1;
    tokens.push({"kind": "symbol", "value": char, "start": start, "end": index});
  }
  return {"tokens": tokens, "diagnostics": diagnostics, "comments": comments};
}

function splitTopLevel(tokens, separator) {
  const groups = [];
  let group = [];
  let depth = 0;
  tokens.forEach(token => {
    if (token["kind"] === "symbol" && ["(", "[", "{"].includes(token["value"])) depth += 1;
    if (token["kind"] === "symbol" && [")", "]", "}"].includes(token["value"])) depth -= 1;
    if (!depth && token["kind"] === "symbol" && token["value"] === separator) { groups.push(group); group = []; }
    else group.push(token);
  });
  groups.push(group);
  return groups;
}

function readLiteral(tokens) {
  if (tokens.length === 1) {
    const token = tokens[0];
    if (token["kind"] === "string") return {"known": true, "value": token["value"]};
    if (token["kind"] === "number") return {"known": true, "value": Number(token["value"])};
    if (["true", "false", "none", "null"].includes(token["value"].toLowerCase())) return {"known": true, "value": {"true": true, "false": false, "none": null, "null": null}[token["value"].toLowerCase()]};
  }
  if (tokens.length === 2 && tokens[0]["value"] === "-" && tokens[1]["kind"] === "number") return {"known": true, "value": -Number(tokens[1]["value"])};
  if (tokens[0]?.["value"] === "[" && tokens.at(-1)?.["value"] === "]") {
    const parts = tokens.length === 2 ? [] : splitTopLevel(tokens.slice(1, -1), ",");
    const values = parts.map(readLiteral);
    return {"known": values.every(value => value["known"]), "value": values.map(value => value["value"])};
  }
  if (tokens[0]?.["value"] === "{" && tokens.at(-1)?.["value"] === "}") {
    const parts = tokens.length === 2 ? [] : splitTopLevel(tokens.slice(1, -1), ",");
    const entries = parts.map(part => {
      const pair = splitTopLevel(part, ":");
      const key = readLiteral(pair[0] || []);
      const value = readLiteral(pair[1] || []);
      return {"known": pair.length === 2 && key["known"] && typeof key["value"] === "string" && value["known"], "key": key["value"], "value": value["value"]};
    });
    return {"known": entries.every(entry => entry["known"]), "value": Object.fromEntries(entries.map(entry => [entry["key"], entry["value"]]))};
  }
  return {"known": false, "value": null};
}

export function parseJinjaMetadata(tokens) {
  const references = [];
  const sources = [];
  const configEntries = [];
  const diagnostics = [];
  tokens.filter(token => token["kind"] === "template").forEach(template => {
    const parts = tokenizeDraft(template["value"].replace(/^\s*-|-\s*$/g, ""), false)["tokens"];
    parts.forEach((token, index) => {
      const name = token["value"];
      if (token["kind"] !== "identifier" || !["ref", "source", "config"].includes(name) || parts[index + 1]?.["value"] !== "(" || parts[index - 1]?.["value"] === ".") return;
      let end = index + 2;
      let depth = 1;
      while (end < parts.length && depth) {
        if (parts[end]["value"] === "(" && parts[end]["kind"] === "symbol") depth += 1;
        if (parts[end]["value"] === ")" && parts[end]["kind"] === "symbol") depth -= 1;
        if (depth) end += 1;
      }
      if (depth) { diagnostics.push(`Incomplete ${name} call.`); return; }
      const args = splitTopLevel(parts.slice(index + 2, end), ",").filter(group => group.length);
      const positional = args.filter(group => group[1]?.["value"] !== "=").map(readLiteral);
      if (name === "ref" || name === "source") {
        const expected = name === "source" ? positional.length === 2 : [1, 2].includes(positional.length);
        if (!expected || !positional.every(arg => arg["known"] && typeof arg["value"] === "string")) { diagnostics.push(`Dynamic ${name} arguments cannot be resolved statically.`); return; }
        const values = positional.map(arg => arg["value"]);
        if (name === "ref") references.push({"name": values.at(-1), "package": values.length === 2 ? values[0] : null});
        else sources.push({"source": values[0], "table": values[1]});
      } else args.forEach(group => {
        const value = readLiteral(group.slice(2));
        if (group[0]?.["kind"] === "identifier" && group[1]?.["value"] === "=" && value["known"]) configEntries.push([group[0]["value"], value["value"]]);
        else diagnostics.push("Dynamic or unsupported config values are not inferred.");
      });
    });
  });
  return {"references": references, "sources": sources, "config": Object.fromEntries(configEntries), "diagnostics": diagnostics};
}

function isKeyword(token, word) { return token?.["kind"] === "identifier" && !token["quoted"] && token["value"].toLowerCase() === word; }

export function inferProjection(tokens, text) {
  const sql = tokens.filter(token => !["template", "template_block"].includes(token["kind"]));
  const statements = splitTopLevel(sql, ";").filter(group => group.length);
  if (statements.length !== 1) return {"fields": [], "complete": false, "reason": "Final fields require one SQL SELECT statement."};
  if (tokens.some(token => token["kind"] === "template_block")) return {"fields": [], "complete": false, "reason": "Jinja control blocks can change the final projection."};
  let depth = 0;
  const selects = statements[0].filter(token => {
    if (token["kind"] === "symbol" && token["value"] === "(") depth += 1;
    if (token["kind"] === "symbol" && token["value"] === ")") depth -= 1;
    return depth === 0 && isKeyword(token, "select");
  });
  if (selects.length !== 1) return {"fields": [], "complete": false, "reason": "No unambiguous outer SELECT projection is available."};
  const select = selects[0];
  if (!isKeyword(statements[0][0], "select") && !isKeyword(statements[0][0], "with")) return {"fields": [], "complete": false, "reason": "Only a SELECT or WITH query has an inferred final projection."};
  const following = tokens.filter(token => token["start"] > select["start"]);
  const projection = [];
  depth = 0;
  for (const token of following) {
    if (!depth && ["from", "where", "group", "having", "order", "limit", "qualify", "union", "intersect", "except", "fetch"].some(word => isKeyword(token, word))) break;
    if (token["kind"] === "symbol" && token["value"] === ";" && !depth) break;
    if (token["kind"] === "symbol" && token["value"] === "(") depth += 1;
    if (token["kind"] === "symbol" && token["value"] === ")") depth -= 1;
    projection.push(token);
  }
  if (isKeyword(projection[0], "distinct") || isKeyword(projection[0], "all")) projection.shift();
  if (depth !== 0) return {"fields": [], "complete": false, "reason": "Unbalanced projection expressions prevent field inference."};
  const fields = splitTopLevel(projection, ",").map(group => {
    const alias = group.length >= 3 && isKeyword(group.at(-2), "as") && group.at(-1)["kind"] === "identifier" ? group.at(-1)["value"] : null;
    const bare = group.length > 0 && group.every((token, index) => index % 2 === 0 ? token["kind"] === "identifier" : token["value"] === ".");
    const wildcard = group.length === 1 && group[0]["value"] === "*" || group.at(-1)?.["value"] === "*" && group.at(-2)?.["value"] === ".";
    return {"name": alias || (bare ? group.at(-1)["value"] : null), "expression": group.length ? text.slice(group[0]["start"], group.at(-1)["end"]) : "", "wildcard": Boolean(wildcard), "data_type": null};
  });
  const complete = fields.length > 0 && fields.every(field => field["name"] && !field["wildcard"]) && !projection.some(token => token["kind"] === "template");
  return {"fields": fields, "complete": complete, "reason": complete ? "Names inferred from explicit aliases or column references. Types require recorded artifacts." : "Wildcards, dynamic expressions, or unnamed expressions prevent a complete inferred schema."};
}

export function parseDraftMetadata(path, content) {
  const text = typeof content === "string" ? content : "";
  const extension = path?.includes(".") ? path.split(".").at(-1).toLowerCase() : "";
  const base = {"path": path || null, "type": extension || "text", "line_count": text.length ? text.split(/\r\n|\r|\n/).length : 0, "character_count": text.length};
  if (extension === "sql") {
    const scanned = tokenizeDraft(text);
    const jinja = parseJinjaMetadata(scanned["tokens"]);
    const projection = inferProjection(scanned["tokens"], text);
    return {...base, "sql": {...jinja, "projection": scanned["diagnostics"].length ? {"fields": [], "complete": false, "reason": "Incomplete comments, strings, or templates prevent projection inference."} : projection,
      "diagnostics": [...scanned["diagnostics"], ...jinja["diagnostics"]]}};
  }
  if (extension === "json") {
    try {
      const value = JSON.parse(text);
      const kind = value === null ? "null" : Array.isArray(value) ? "array" : typeof value;
      const details = kind === "object" ? Object.entries(value).map(([key, item]) => ({"key": key, "type": item === null ? "null" : Array.isArray(item) ? "array" : typeof item, "value": item === null || typeof item !== "object" ? item : null, "size": item && typeof item === "object" ? Array.isArray(item) ? item.length : Object.keys(item).length : null})) : [];
      return {...base, "json": {"kind": kind, "value": kind !== "object" && kind !== "array" ? value : null, "size": kind === "array" ? value.length : kind === "object" ? Object.keys(value).length : null, "fields": details, "error": null}};
    } catch (error) { return {...base, "json": {"error": error["message"]}}; }
  }
  return base;
}

function renderValue(value) { return value === null || value === undefined ? "Not recorded" : typeof value === "object" ? JSON.stringify(value) : String(value); }

function renderOwner(owner) {
  if (!owner || typeof owner !== "object") return renderValue(owner);
  return [owner["name"], owner["email"]].filter(Boolean).join(" · ") || "Owner details are available in configuration.";
}

function MetadataList(props) {
  return h("dl", {"className": "activity-metadata metadata-facts"}, props["items"].map(([key, value]) => h(React["Fragment"], {"key": key}, h("dt", null, key), h("dd", null, renderValue(value)))));
}

function ResourceRow(props) {
  const resource = props["resource"];
  const path = resource?.["path"];
  const canOpen = Boolean(path && props["files"].some(file => file["path"] === path));
  return h("div", {"className": "metadata-resource-row"},
    h("div", {"className": "metadata-resource-label"}, resource ? h("button", {"className": "metadata-resource-name", "title": resource["unique_id"], "onClick": () => props["onSelectResource"](resource["unique_id"])}, resource["name"]) : h("span", {"className": "mono"}, props["label"]),
      h("span", {"className": "pill metadata-kind"}, resource?.["resource_type"] || props["kind"] || "Unresolved"),
      resource?.["package_name"] ? h("small", {"className": "hint"}, resource["package_name"]) : null),
    canOpen ? h("button", {"className": "button small", "aria-label": `Open metadata resource file ${path}`, "onClick": () => props["onOpenFile"]?.(path)}, "Open file") : null,
    !resource ? h("small", {"className": "hint metadata-unresolved"}, props["catalogReady"] ? "No manifest match recorded." : "Catalog not loaded.") : null);
}

function DependencyList(props) {
  const ids = props["ids"] || [];
  const indexed = new Map(props["resources"].map(resource => [resource["unique_id"], resource]));
  return h("details", {"className": "metadata-dependencies"}, h("summary", null, `${props["label"]} (${ids.length})`), ids.length ? ids.map(id => h(ResourceRow, {...props, "key": id, "resource": indexed.get(id), "label": id})) : h("p", {"className": "hint"}, "None recorded."));
}

function DraftReferences(props) {
  const references = props["references"];
  const sources = props["sources"];
  const rows = [...references.map((reference, index) => ({"key": `ref-${index}`, "label": reference["package"] ? `${reference["package"]}.${reference["name"]}` : reference["name"], "kind": "ref",
    "matches": props["resources"].filter(resource => ["model", "seed", "snapshot"].includes(resource["resource_type"]) && resource["name"] === reference["name"] && (!reference["package"] || resource["package_name"] === reference["package"]))})),
    ...sources.map((source, index) => ({"key": `source-${index}`, "label": `${source["source"]}.${source["table"]}`, "kind": "source",
      "matches": props["resources"].filter(resource => resource["resource_type"] === "source" && resource["name"] === source["table"] && resource["unique_id"].endsWith(`.${source["source"]}.${source["table"]}`))}))];
  return h("div", {"className": "metadata-draft-references"}, h("h4", null, "Inferred references"), rows.length ? rows.map(row => h("div", {"className": "metadata-reference-group", "key": row["key"]},
    h("small", {"className": "hint"}, `${row["kind"] === "source" ? "source()" : "ref()"} · ${row["label"]}${row["matches"].length > 1 ? " · multiple manifest matches" : ""}`),
    row["matches"].length ? row["matches"].map(resource => h(ResourceRow, {...props, "key": resource["unique_id"], "resource": resource})) : h(ResourceRow, {...props, "label": row["label"], "kind": row["kind"]}))) : h("p", {"className": "hint"}, "No static ref() or source() calls found."));
}

function RecordedMetadata(props) {
  const resource = useResource(`${props["base"]}/documentation?resource_id=${encodeURIComponent(props["resourceId"])}`);
  const doc = resource["data"];
  const facts = doc ? [["Resource", doc["name"]], ["Owner", renderOwner(doc["owner"])],
    ...[["Materialization", doc["config"]?.["materialized"]], ["Package", doc["package_name"]], ["Relation", doc["relation_name"]]].filter(([, value]) => value !== null && value !== undefined)] : [];
  const freshness = doc?.["freshness"];
  return h(ResourceState, {"resource": resource}, doc ? h("section", {"className": "metadata-recorded"},
    h("div", {"className": "panel-toolbar"}, h("h3", null, "Recorded dbt artifacts"), h("button", {"className": "button small", "onClick": resource["reload"]}, "Refresh")),
    h("p", {"className": "hint"}, "Recorded schema and outcomes can differ from the unsaved draft."),
    h("div", {"className": "tag-list metadata-recorded-tags"}, h("span", {"className": "pill metadata-kind"}, doc["resource_type"]), doc["status"] ? h("span", {"className": `pill status-${doc["status"]}`}, doc["status"]) : null,
      (doc["tags"] || []).map(tag => h("span", {"className": "pill", "key": tag}, tag))),
    h(MetadataList, {"items": facts}), h("p", {"className": "metadata-description"}, doc["description"] || "No description recorded."),
    freshness ? h("div", {"className": "metadata-freshness"}, h("h4", null, "Recorded freshness"),
      freshness["status"] ? h("span", {"className": `pill status-${freshness["status"]}`}, freshness["status"]) : null,
      h(MetadataList, {"items": [["Latest loaded at", freshness["max_loaded_at"]], ["Checked at", freshness["snapshotted_at"]], ["Age in seconds", freshness["max_loaded_at_time_ago_in_s"]]]})) : null,
    h("h4", null, "Columns"), (doc["columns"] || []).length ? h("div", {"className": "grid-wrap metadata-schema"}, h("table", {"className": "result-grid"},
      h("thead", null, h("tr", null, h("th", {"scope": "col"}, "Column"), h("th", {"scope": "col"}, "Type"), h("th", {"scope": "col"}, "Description"))),
      h("tbody", null, doc["columns"].map(column => h("tr", {"key": column["name"]}, h("td", null, column["name"]), h("td", null, renderValue(column["data_type"])), h("td", null, renderValue(column["description"]))))))) : h("p", {"className": "hint"}, "No recorded columns."),
    h("h4", null, "Tests"), (doc["tests"] || []).length ? doc["tests"].map(test => h("div", {"className": "test-entry metadata-test", "key": test["unique_id"]},
      h("span", {"className": `pill status-${test["status"] || "unknown"}`}, test["status"] || "Not recorded"), h("span", null, test["name"]), test["result"]?.["message"] ? h("small", null, test["result"]["message"]) : null)) : h("p", {"className": "hint"}, "No recorded tests."),
    h(DependencyList, {...props, "label": "Upstream", "ids": doc["dependencies"] || doc["depends_on"]}),
    h(DependencyList, {...props, "label": "Downstream", "ids": doc["dependents"]}),
    h("details", {"className": "metadata-disclosure"}, h("summary", null, "Configuration"), h("pre", null, doc["config"] ? JSON.stringify(doc["config"], null, 2) : "No configuration recorded.")),
    h("details", {"className": "metadata-disclosure"}, h("summary", null, "Compiled SQL"), h("pre", {"tabIndex": 0}, doc["compiled_code"] || "No compiled SQL recorded.")),
    h("details", {"className": "metadata-disclosure"}, h("summary", null, "Artifact identity"), h(MetadataList, {"items": [["Resource ID", doc["unique_id"]], ["Manifest", doc["manifest_path"]], ["Catalog", doc["catalog_path"]], ["Run results", doc["run_results_path"]]]})),
    h("button", {"className": "button small", "onClick": () => props["onInspect"]?.("documentation")}, "Open documentation")) : null);
}

function DraftMetadata(props) {
  const draft = props["draft"];
  const sql = draft["sql"];
  const json = draft["json"];
  return h("section", {"className": "metadata-draft"}, h("h3", null, "Draft inference"), h("p", {"className": "hint"}, "Inferred from editor text. Types and tests come from recorded artifacts."),
    sql ? h("div", null,
      h(DraftReferences, {...props, "references": sql["references"], "sources": sql["sources"]}),
      Object.keys(sql["config"]).length ? h("details", {"className": "metadata-disclosure"}, h("summary", null, "Inferred config() values"), h("pre", null, JSON.stringify(sql["config"], null, 2))) : null,
      h("h4", null, "Inferred final projection"), h("p", {"className": "hint"}, sql["projection"]["reason"]),
      sql["projection"]["fields"].length ? h("div", {"className": "grid-wrap metadata-projection"}, h("table", {"className": "result-grid"},
        h("thead", null, h("tr", null, h("th", {"scope": "col"}, "Field"), h("th", {"scope": "col"}, "Expression"))),
        h("tbody", null, sql["projection"]["fields"].map((field, index) => h("tr", {"key": index}, h("td", null, field["name"] || (field["wildcard"] ? "Wildcard: schema unknown" : "Unnamed expression")), h("td", {"className": "mono"}, field["expression"])))))) : h("p", {"className": "hint"}, "No final fields can be inferred."),
      sql["diagnostics"].map((message, index) => h("p", {"className": "hint metadata-diagnostic", "key": index}, message))) : null,
    json ? json["error"] ? h("p", {"className": "hint", "role": "status"}, `Invalid JSON: ${json["error"]}`) : h("div", null,
      h(MetadataList, {"items": [["JSON type", json["kind"]], ...(json["size"] === null ? [["Scalar value", JSON.stringify(json["value"])]] : [["Entries", json["size"]]])]}),
      json["fields"].map(field => h("p", {"key": field["key"], "className": "mono"}, `${field["key"]}: ${field["type"]}`, h("small", {"className": "hint"}, field["size"] !== null ? ` · ${field["size"]} entries` : ` · ${JSON.stringify(field["value"])}`)))) : null,
    !sql && !json ? h("p", {"className": "hint"}, "No static schema inference is available for this file type.") : null);
}

export function MetadataPanel(props) {
  const catalog = useResource(`${props["base"]}/catalog?detail=resources`);
  const files = useResource(`${props["base"]}/files`);
  const file = props["file"];
  const draft = React["useMemo"](() => parseDraftMetadata(file?.["path"], props["content"] ?? file?.["content"] ?? ""), [file?.["path"], props["content"], file?.["content"]]);
  const resources = catalog["data"]?.["resources"] || [];
  const matches = resources.filter(resource => resource["path"] === file?.["path"]);
  const [selected, setSelected] = React["useState"]("");
  React["useEffect"](function followFile() { setSelected(""); }, [file?.["path"]]);
  const current = resources.find(resource => resource["unique_id"] === selected) || matches.find(resource => resource["unique_id"] === props["modelId"]) || matches[0];
  const rowProps = {...props, "resources": resources, "files": files["data"]?.["files"] || [], "catalogReady": Boolean(catalog["data"]), "onSelectResource": setSelected};
  if (!file) return h("p", {"className": "panel-empty"}, "Open a project file to inspect its metadata.");
  return h("div", {"className": "inspector-content metadata-panel"}, h("div", {"className": "panel-toolbar"}, h(Info, {"size": 18, "aria-hidden": true}), h("h2", null, file["path"].split("/").at(-1))),
    h(MetadataList, {"items": [["File", draft["path"]], ["Type", draft["type"]], ["Bytes on disk", file["size_bytes"]], ["Access", file["read_only"] ? "Read only" : "Editable text"], ...(!file["read_only"] ? [["Lines", draft["line_count"]], ["Characters", draft["character_count"]]] : [])]}),
    h("details", {"className": "metadata-disclosure"}, h("summary", null, "Saved file identity"), h(MetadataList, {"items": [["Saved revision", file["revision"]]]})),
    file["read_only"] ? h("p", {"className": "hint", "role": "status"}, file["preview_reason"]) : h(DraftMetadata, {...rowProps, "draft": draft}),
    h(ResourceState, {"resource": catalog}, matches.length > 1 ? h("label", null, "Recorded resource", h("select", {"aria-label": "Metadata resource", "value": matches.some(resource => resource["unique_id"] === current?.["unique_id"]) ? current["unique_id"] : "", "onChange": event => setSelected(event["target"]["value"])},
      h("option", {"value": ""}, "Resources in the active file"), matches.map(resource => h("option", {"key": resource["unique_id"], "value": resource["unique_id"]}, `${resource["name"]} · ${resource["resource_type"]}`)))) : null,
      selected && current ? h("div", {"className": "metadata-inspection-context"}, h("p", {"className": "hint"}, `Inspecting ${current["name"]}. The active file draft stays unchanged.`), h("button", {"className": "button small", "onClick": () => setSelected("")}, "Back to active file")) : null,
      current ? h(RecordedMetadata, {...rowProps, "resourceId": current["unique_id"], "key": current["unique_id"]}) : h("p", {"className": "hint"}, "No recorded manifest resource matches this file path.")));
}
