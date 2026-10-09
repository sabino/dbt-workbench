import React from "react";
import {Database} from "@phosphor-icons/react/dist/csr/Database";
import {Cloud} from "@phosphor-icons/react/dist/csr/Cloud";
import {CheckCircle} from "@phosphor-icons/react/dist/csr/CheckCircle";
import {WarningCircle} from "@phosphor-icons/react/dist/csr/WarningCircle";
import {MagnifyingGlass} from "@phosphor-icons/react/dist/csr/MagnifyingGlass";
import {requestJson, useResource, ResourceState} from "./panel-utils.js";

const h = React.createElement;
const environmentName = /^[A-Za-z_][A-Za-z0-9_]*$/;
const connectionId = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const routeIdentifier = /^[A-Za-z_][A-Za-z0-9_]*$/;

function quoteIdentifier(value) {
  return `"${String(value).replaceAll('"', '""')}"`;
}
function Badge(props) {
  return h("span", {"className": "pill"}, props["children"]);
}
function ConnectionForm(props) {
  const [form, setForm] = React.useState({"id": "", "type": "duckdb", "authenticator": "snowflake"});
  const remote = form["type"] === "snowflake";
  const credential = form["authenticator"] === "oauth" ? "token_env" : form["authenticator"] === "snowflake_jwt" ? "private_key_file_env" : "password_env";
  const valid = connectionId.test(form["id"] || "") && (remote
    ? Boolean(form["account"]?.trim() && form["user"]?.trim() && environmentName.test(form[credential] || "")
      && (credential !== "private_key_file_env" || !form["private_key_file_pwd_env"] || environmentName.test(form["private_key_file_pwd_env"])))
    : Boolean(form["path"]?.trim()));
  function updateField(key, value) { setForm(previous => ({...previous, [key]: value})); }
  function renderField(key, label, placeholder, required = false, pattern) {
    return h("label", {"key": key}, label, h("input", {"aria-label": label, "value": form[key] || "", "placeholder": placeholder,
      "required": required, "pattern": pattern, "disabled": Boolean(props["busy"]), "autoComplete": "off", "spellCheck": false,
      "onChange": event => updateField(key, event["target"]["value"])}));
  }
  function submit(event) {
    event.preventDefault();
    if (!valid || props["busy"]) return;
    const fields = remote ? ["id", "name", "type", "account", "user", "database", "schema", "warehouse", "role", "authenticator", credential,
      ...(credential === "private_key_file_env" ? ["private_key_file_pwd_env"] : [])] : ["id", "name", "type", "path", "schema"];
    const body = Object.fromEntries(fields.filter(field => form[field]?.trim()).map(field => [field, form[field].trim()]));
    props["onSave"](body).then(saved => { if (saved) setForm({"id": "", "type": form["type"], "authenticator": form["authenticator"]}); });
  }
  return h("details", {"className": "inspector-card"}, h("summary", null, "Add connection"),
    h("form", {"onSubmit": submit, "className": "inspector-content", "style": {"padding": 0}},
      h("label", null, "Platform", h("select", {"aria-label": "Connection platform", "value": form["type"], "disabled": props["busy"],
        "onChange": event => updateField("type", event["target"]["value"])}, h("option", {"value": "duckdb"}, "DuckDB"), h("option", {"value": "snowflake"}, "Snowflake"))),
      renderField("id", "Connection ID", "analytics", true, "[A-Za-z][A-Za-z0-9_-]{0,63}"),
      renderField("name", "Display name", "Analytics"),
      remote ? h(React.Fragment, null,
        renderField("account", "Snowflake account", "organization-account", true),
        renderField("user", "User", "analyst", true),
        h("div", {"className": "panel-toolbar"}, renderField("database", "Database", "ANALYTICS"), renderField("schema", "Schema", "PUBLIC")),
        h("div", {"className": "panel-toolbar"}, renderField("warehouse", "Warehouse", "COMPUTE_WH"), renderField("role", "Role", "READER")),
        h("label", null, "Authentication", h("select", {"aria-label": "Authentication", "value": form["authenticator"], "disabled": Boolean(props["busy"]),
          "onChange": event => updateField("authenticator", event["target"]["value"])}, h("option", {"value": "snowflake"}, "Password"), h("option", {"value": "oauth"}, "OAuth token"), h("option", {"value": "snowflake_jwt"}, "Key pair"))),
        renderField(credential, credential === "token_env" ? "Token environment variable" : credential === "private_key_file_env" ? "Private key file environment variable" : "Password environment variable",
          credential === "token_env" ? "SNOWFLAKE_TOKEN" : credential === "private_key_file_env" ? "SNOWFLAKE_KEY_FILE" : "SNOWFLAKE_PASSWORD", true, "[A-Za-z_][A-Za-z0-9_]*"),
        credential === "private_key_file_env" ? renderField("private_key_file_pwd_env", "Key passphrase environment variable", "SNOWFLAKE_KEY_PASSPHRASE", false, "[A-Za-z_][A-Za-z0-9_]*") : null,
        h("p", {"className": "hint"}, "Enter environment variable names, never credential values. Variables must be available to the workbench server."),
        h("p", {"className": "hint"}, "Saving stores configuration locally. Use Test connection to check Snowflake access."))
        : h(React.Fragment, null, renderField("path", "Database path", "/path/to/analytics.duckdb", true), renderField("schema", "Schema", "main"),
          h("p", {"className": "hint"}, "Choose an existing DuckDB file. Queries and schema reads open it read-only.")),
      h("button", {"className": "button primary small", "type": "submit", "disabled": !valid || props["busy"]}, props["saving"] ? "Saving..." : "Save connection")));
}

function CliConnectionImport(props) {
  const [configured, setConfigured] = React.useState(null);
  const [selected, setSelected] = React.useState("");
  const [id, setId] = React.useState("");
  const [busy, setBusy] = React.useState("");
  const connection = configured?.find(value => value["cli_connection"] === selected);
  function chooseConnection(name) {
    setSelected(name);
    setId(`cli-${name.replace(/[^A-Za-z0-9_-]/g, "_")}`.slice(0, 64));
  }
  async function loadConfigured() {
    setBusy("load");
    try {
      const result = await requestJson(`${props["base"]}/connections/cli`);
      const listed = result["connections"] || (Array.isArray(result) ? result : []);
      setConfigured(listed);
      if (listed.length) chooseConnection(listed[0]["cli_connection"]);
      else { setSelected(""); setId(""); }
    } catch (error) { props["onError"]?.(error); }
    finally { setBusy(""); }
  }
  async function importConnection(event) {
    event.preventDefault();
    if (!connection || !connectionId.test(id) || busy || props["busy"]) return;
    setBusy("import");
    try {
      await requestJson(`${props["base"]}/connections/cli/import`, "POST", {"cli_connection": selected, "id": id});
      props["onRefresh"](); props["onNotice"]?.("Snow CLI connection registered locally. Cached authentication stays with the CLI.");
    } catch (error) { props["onError"]?.(error); }
    finally { setBusy(""); }
  }
  return h("details", {"className": "inspector-card"}, h("summary", null, "Import Snow CLI connection"),
    h("p", {"className": "hint"}, "Reuse a connection configured in Snow CLI. Import stores its name locally and copies no credentials."),
    h("button", {"className": "button small", "disabled": Boolean(busy) || props["busy"], "onClick": loadConfigured},
      busy === "load" ? "Reading local configuration..." : configured ? "Refresh configured connections" : "Load configured connections"),
    configured && !configured.length ? h("p", {"className": "hint"}, "No Snow CLI connections are configured on this computer.") : null,
    configured?.length ? h("form", {"className": "inspector-content", "style": {"padding": 0}, "onSubmit": importConnection},
      h("label", null, "CLI connection", h("select", {"aria-label": "Configured Snow CLI connection", "value": selected,
        "disabled": Boolean(busy) || props["busy"], "onChange": event => chooseConnection(event["target"]["value"])},
        configured.map(value => h("option", {"key": value["cli_connection"], "value": value["cli_connection"]}, value["name"] || value["cli_connection"])))),
      connection ? h("p", {"className": "hint"}, [connection["account"], connection["database"], connection["schema"]].filter(Boolean).join(" · ")) : null,
      h("label", null, "Workbench connection ID", h("input", {"aria-label": "Imported connection ID", "value": id,
        "required": true, "pattern": "[A-Za-z][A-Za-z0-9_-]{0,63}", "disabled": Boolean(busy) || props["busy"],
        "onChange": event => setId(event["target"]["value"])})),
      h("p", {"className": "hint"}, "Listing and importing read local configuration. They do not open a cloud session or start a login."),
      h("button", {"className": "button primary small", "type": "submit", "disabled": !connection || !connectionId.test(id) || Boolean(busy) || props["busy"]},
        busy === "import" ? "Registering..." : "Import connection")) : null);
}

export function ConnectionsPanel(props) {
  const resource = useResource(`${props["base"]}/connections`);
  const [tab, setTab] = React.useState("connections");
  const [selected, setSelected] = React.useState(props["target"] || "");
  const [health, setHealth] = React.useState({});
  const [schema, setSchema] = React.useState(null);
  const [filter, setFilter] = React.useState("");
  const [schemaFilter, setSchemaFilter] = React.useState("");
  const [busy, setBusy] = React.useState("");
  const [schemaLoading, setSchemaLoading] = React.useState(false);
  const [schemaError, setSchemaError] = React.useState("");
  const schemaRequest = React.useRef(0);
  const connections = resource["data"]?.["connections"] || [];
  const selectedConnection = connections.find(connection => connection["id"] === selected);
  const supported = connections.filter(connection => connection["supported"]);
  const relations = schema?.["relations"] || [];
  const shownConnections = connections.filter(connection => `${connection["name"]} ${connection["id"]} ${connection["type"]}`.toLowerCase().includes(filter.toLowerCase()));
  const queryRelation = relations[0];
  const schemaName = queryRelation?.["schema"] || selectedConnection?.["schema"] || (selectedConnection?.["type"] === "snowflake" ? "PUBLIC" : "main");
  const tableName = queryRelation?.["name"];
  const example = tableName && selectedConnection
    ? `SELECT *\nFROM ${[selected, schemaName, tableName].map(quoteIdentifier).join(".")}\nLIMIT 100;` : "";
  function reportError(error) { props["onError"]?.(error); }
  function refreshConnections() { resource["reload"](); props["onRefresh"]?.(); }
  async function testConnection(id) {
    setBusy(`test:${id}`);
    try {
      const result = await requestJson(`${props["base"]}/connections/test`, "POST", {"id": id});
      setHealth(previous => ({...previous, [id]: result}));
    } catch (error) { reportError(error); }
    finally { setBusy(""); }
  }
  async function browseSchema(id) {
    const revision = ++schemaRequest["current"];
    setSelected(id); setTab("schema"); setSchema(null); setSchemaError(""); setSchemaLoading(true); setSchemaFilter("");
    try {
      const result = await requestJson(`${props["base"]}/connections/${encodeURIComponent(id)}`);
      if (revision === schemaRequest["current"]) setSchema(result);
    } catch (error) {
      if (revision === schemaRequest["current"]) { setSchemaError(error.message); reportError(error); }
    } finally { if (revision === schemaRequest["current"]) setSchemaLoading(false); }
  }
  async function saveConnection(body) {
    setBusy("save");
    try {
      await requestJson(`${props["base"]}/connections`, "POST", body);
      refreshConnections(); props["onNotice"]?.("Connection configuration saved."); return true;
    } catch (error) { reportError(error); return false; }
    finally { setBusy(""); }
  }
  async function removeConnection(id) {
    setBusy(`remove:${id}`);
    try {
      await requestJson(`${props["base"]}/connections/${encodeURIComponent(id)}`, "DELETE");
      if (selected === id) { ++schemaRequest["current"]; setSelected(""); setSchema(null); setSchemaLoading(false); }
      setHealth(previous => Object.fromEntries(Object.entries(previous).filter(([key]) => key !== id)));
      refreshConnections(); props["onNotice"]?.("Connection configuration removed. Database files are preserved.");
    } catch (error) { reportError(error); }
    finally { setBusy(""); }
  }
  function chooseConnection(id) {
    ++schemaRequest["current"]; setSelected(id); setSchema(null); setSchemaLoading(false); setSchemaError("");
  }
  function openQuery(content, dialect) {
    if (!props["onQuery"]) return;
    props["onTarget"]?.(selected, selectedConnection?.["type"]);
    props["onQuery"](content, dialect);
  }
  return h(ResourceState, {"resource": resource}, h("div", {"className": "inspector-content"},
    h("div", {"className": "segmented", "role": "group", "aria-label": "Connection sections"}, ["connections", "federation", "schema"].map(value => h("button", {
      "key": value, "className": `button small ${tab === value ? "selected" : ""}`, "aria-pressed": tab === value,
      "onClick": () => setTab(value)}, value[0].toUpperCase() + value.slice(1)))),
    tab === "connections" ? h(React.Fragment, null,
      h("p", {"className": "hint"}, "Project profiles and saved connections. Test and schema actions contact only the connection you choose."),
      h("div", {"className": "panel-toolbar"}, h(MagnifyingGlass, {"size": 18, "aria-hidden": true}),
        h("input", {"aria-label": "Search connections", "value": filter, "placeholder": "Search connections...", "onChange": event => setFilter(event["target"]["value"])})),
      !shownConnections.length ? h("p", {"className": "panel-empty"}, connections.length ? "No connections match this search." : "Add a connection or configure a project profile.") : null,
      shownConnections.map(connection => {
        const id = connection["id"];
        const result = health[id];
        return h("section", {"className": `connection-card ${props["target"] === id ? "selected" : ""}`, "key": id},
          h("div", {"className": "panel-toolbar"}, h(connection["type"] === "snowflake" ? Cloud : Database, {"size": 22, "aria-hidden": true}),
            h("strong", null, connection["name"] || id), h(Badge, null, connection["transport"] === "snow_cli" ? "Snow CLI" : connection["type"]), props["target"] === id ? h(Badge, null, "Query target") : null),
          h("p", {"className": "hint"}, `${id} · ${connection["source"] === "profile" ? "Project profile" : "Saved configuration"} · ${connection["database"] ? `${connection["database"]}.` : ""}${connection["schema"] || "Default schema"}`),
          connection["account"] ? h("p", {"className": "hint"}, connection["account"]) : null,
          result ? h("p", {"className": "hint", "role": "status"}, result["healthy"]
            ? `Connection succeeded · ${result["read_only"] ? "Read-only file access" : "SELECT access verified; permissions depend on role grants"}` : result["diagnostic"] || "Connection failed.") : null,
          !connection["supported"] ? h("p", {"className": "hint"}, "This platform is unavailable for interactive queries.") : null,
          h("div", {"className": "panel-toolbar"},
            h("button", {"className": "button small", "disabled": Boolean(busy) || !connection["supported"], "onClick": () => testConnection(id)}, busy === `test:${id}` ? "Testing..." : "Test connection"),
            h("button", {"className": "button small", "disabled": Boolean(busy) || !connection["supported"], "onClick": () => browseSchema(id)}, "Browse schema"),
            props["onTarget"] ? h("button", {"className": "button small", "disabled": !connection["supported"] || props["target"] === id,
              "onClick": () => props["onTarget"](id, connection["type"])}, "Use in Query lab") : null,
            connection["source"] !== "profile" ? h("button", {"className": "button small danger", "disabled": Boolean(busy), "onClick": () => removeConnection(id)}, "Remove") : null));
      }), h(CliConnectionImport, {"base": props["base"], "busy": Boolean(busy), "onRefresh": refreshConnections, "onError": reportError, "onNotice": props["onNotice"]}),
      h(ConnectionForm, {"busy": Boolean(busy), "saving": busy === "save", "onSave": saveConnection})) : null,
    tab !== "connections" ? h("label", null, "Connection", h("select", {"aria-label": `${tab} connection`, "value": selected,
      "onChange": event => chooseConnection(event["target"]["value"])}, h("option", {"value": ""}, "Choose a connection"),
      supported.map(connection => h("option", {"key": connection["id"], "value": connection["id"]}, `${connection["name"] || connection["id"]} · ${connection["type"]}`)))) : null,
    tab === "schema" ? h(React.Fragment, null,
      h("div", {"className": "panel-toolbar"}, h("button", {"className": "button small", "disabled": !selected || schemaLoading,
        "onClick": () => browseSchema(selected)}, schemaLoading ? "Reading schema..." : schema ? "Refresh schema" : "Read schema")),
      schemaError ? h("p", {"role": "alert", "className": "hint"}, schemaError) : null,
      schemaLoading ? h("p", {"role": "status", "className": "panel-empty"}, "Reading relations and columns...") : null,
      !schema && !schemaLoading && !schemaError ? h("p", {"className": "panel-empty"}, "Choose a connection and read its schema.") : null,
      schema ? h(React.Fragment, null,
        h("div", {"className": "panel-toolbar"}, h(MagnifyingGlass, {"size": 18, "aria-hidden": true}), h("input", {"aria-label": "Search schema and columns", "placeholder": "Search relations or columns...", "value": schemaFilter,
          "onChange": event => setSchemaFilter(event["target"]["value"])})),
        h("p", {"className": "hint"}, `${relations.length} relations returned by ${selected}.`),
        !relations.length ? h("p", {"className": "panel-empty"}, "No relations were returned. Check the selected database and access grants.") : null,
        relations.filter(relation => `${relation["database"]} ${relation["schema"]} ${relation["name"]} ${(relation["columns"] || []).map(column => `${column["name"]} ${column["type"]}`).join(" ")}`.toLowerCase().includes(schemaFilter.toLowerCase())).map(relation => h("details", {
          "className": "inspector-card", "key": `${relation["database"]}.${relation["schema"]}.${relation["name"]}`, "open": Boolean(schemaFilter)},
          h("summary", null, `${relation["schema"]}.${relation["name"]}`, " ", h(Badge, null, `${relation["columns"]?.length || 0} columns`)),
          h("p", {"className": "hint"}, relation["database"]),
          (relation["columns"] || []).map(column => h("div", {"className": "schema-column", "key": column["name"]}, h("span", null, column["name"]), h("span", {"className": "hint"}, column["type"]))),
          props["onQuery"] ? h("button", {"className": "button small", "onClick": () => openQuery(
            `SELECT *\nFROM ${[relation["database"], relation["schema"], relation["name"]].filter(Boolean).map(quoteIdentifier).join(".")}\nLIMIT 100;`, selectedConnection?.["type"] || "duckdb")}, "Open SELECT in Query lab") : null))) : null) : null,
    tab === "federation" ? h("section", {"className": "inspector-card"}, h("h3", null, "Query across connections"),
      h("p", {"className": "hint"}, "Use connection.schema.table to address a registered connection. Federated SELECT queries capture bounded source tables and execute locally."),
      h("p", {"className": "hint"}, "Limits, captured relations, and semantic warnings appear with each result. Snowflake captures contact the selected account using its configured credentials."),
      !tableName ? h(React.Fragment, null, h("p", {"className": "hint"}, "Read the selected connection's schema to build an example from an existing relation."),
        h("button", {"className": "button small", "disabled": !selected || schemaLoading, "onClick": () => browseSchema(selected)}, "Choose a relation from schema")) : h(React.Fragment, null,
        h("pre", {"aria-label": "Federated SELECT example"}, example),
        props["onQuery"] ? h("button", {"className": "button primary small", "onClick": () => openQuery(example, "federated")}, "Open federated SELECT in Query lab") : h("p", {"className": "hint"}, "Paste this SELECT into Query lab and choose the Federated engine."))) : null));
}

export function RoutingPanel(props) {
  const resource = useResource(`${props["base"]}/routing`);
  const models = useResource(`${props["base"]}/models`);
  const connections = useResource(`${props["base"]}/connections`);
  const [routes, setRoutes] = React.useState(null);
  const [validation, setValidation] = React.useState(null);
  const [busy, setBusy] = React.useState("");
  const [filter, setFilter] = React.useState("");
  const selected = routes || resource["data"]?.["routes"] || [];
  const available = (connections["data"]?.["connections"] || []).filter(connection => connection["supported"]);
  const modelRows = models["data"]?.["models"] || [];
  const validOverrides = selected.every(route => ["schema", "relation"].every(field => !route[field] || routeIdentifier.test(route[field])));
  function updateRoute(modelId, field, value) {
    const existing = selected.find(route => route["model_id"] === modelId);
    const updated = {...existing, "model_id": modelId, [field]: value};
    if (field !== "target" && !value) delete updated[field];
    setRoutes([...selected.filter(route => route["model_id"] !== modelId), ...(updated["target"] ? [updated] : [])]);
    setValidation(null);
  }
  async function saveOrValidate(save) {
    if (!validOverrides || busy) return;
    setBusy(save ? "save" : "validate");
    try {
      const result = await requestJson(`${props["base"]}/routing${save ? "" : "/validate"}`, "POST", {"version": 1, "routes": selected});
      setValidation(save ? result["validation"] : result);
      if (save) { setRoutes(result["routes"] || selected); resource["reload"](); props["onRefresh"]?.(); props["onNotice"]?.("Model routing saved. Review the relation validation below."); }
    } catch (error) { props["onError"]?.(error); }
    finally { setBusy(""); }
  }
  return h(ResourceState, {"resource": resource}, h(ResourceState, {"resource": models}, h(ResourceState, {"resource": connections},
    h("div", {"className": "inspector-content"},
      h("p", {"className": "hint"}, "Route model references to configured connections. SQL and EDN use saved overrides for unqualified model names."),
      h("p", {"className": "hint"}, "Same-account Snowflake routes use the query connection's role grants. Choose Federated for queries spanning engines or accounts."),
      h("div", {"className": "panel-toolbar"}, h("strong", null, `${selected.length} model overrides`),
        h("button", {"className": "button small", "disabled": Boolean(busy) || !validOverrides, "onClick": () => saveOrValidate(false)}, busy === "validate" ? "Validating..." : "Validate routing"),
        h("button", {"className": "button primary small", "disabled": Boolean(busy) || !validOverrides, "onClick": () => saveOrValidate(true)}, busy === "save" ? "Saving..." : "Save routing"),
        h("button", {"className": "button small", "disabled": Boolean(busy) || !selected.length, "onClick": () => { setRoutes([]); setValidation(null); }}, "Clear overrides")),
      h("p", {"className": "hint"}, "Saving also reads the routed relations to validate access."),
      !validOverrides ? h("p", {"className": "hint", "role": "alert"}, "Schema and relation overrides must start with a letter or underscore and contain letters, numbers, or underscores.") : null,
      h("input", {"aria-label": "Search routed models", "value": filter, "placeholder": "Search models...", "onChange": event => setFilter(event["target"]["value"])}),
      !modelRows.length ? h("p", {"className": "panel-empty"}, "No project models are available to route.") : null,
      modelRows.filter(model => `${model["name"]} ${model["path"]}`.toLowerCase().includes(filter.toLowerCase())).map(model => {
        const modelId = model["unique_id"];
        const route = selected.find(value => value["model_id"] === modelId);
        return h("section", {"className": "routing-row", "key": modelId}, h("div", null, h("strong", null, model["name"]), h("p", {"className": "hint"}, model["path"])),
          h("label", null, "Connection", h("select", {"aria-label": `Route ${model["name"]}`, "value": route?.["target"] || "", "disabled": Boolean(busy),
            "onChange": event => updateRoute(modelId, "target", event["target"]["value"])}, h("option", {"value": ""}, `Use query target (${props["target"] || "default"})`),
            route?.["target"] && !available.some(value => value["id"] === route["target"]) ? h("option", {"value": route["target"]}, `${route["target"]} · unavailable`) : null,
            available.map(connection => h("option", {"key": connection["id"], "value": connection["id"]}, `${connection["name"] || connection["id"]} · ${connection["type"]}`)))),
          route ? h("div", {"className": "panel-toolbar"}, ["schema", "relation"].map(field => h("label", {"key": field}, field === "schema" ? "Schema override" : "Relation override",
            h("input", {"aria-label": `${model["name"]} ${field} override`, "value": route[field] || "", "placeholder": "Use configured value", "disabled": Boolean(busy),
              "onChange": event => updateRoute(modelId, field, event["target"]["value"])})))) : null);
      }),
      validation ? h("section", {"className": "inspector-card", "role": "status"},
        h("div", {"className": "panel-toolbar"}, h(validation["valid"] ? CheckCircle : WarningCircle, {"size": 20, "aria-hidden": true}),
          h("strong", null, validation["valid"] ? "Routed relations are readable" : "Routing needs attention")),
        !validation["routes"]?.length ? h("p", {"className": "hint"}, "No explicit overrides. Queries use the selected target.") : null,
        (validation["routes"] || []).map(route => h("div", {"key": route["model_id"]}, h("strong", null, route["model_id"]),
          h("p", {"className": "hint"}, `${route["target"]}: ${route["valid"] ? [route["database"], route["schema"], route["relation"]].filter(Boolean).join(".") : route["diagnostic"] || "Relation could not be read."}`)))) : null))));
}
