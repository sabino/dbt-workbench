import {test, expect} from "bun:test";
import {EditorState} from "@codemirror/state";
import {CompletionContext} from "@codemirror/autocomplete";
import {default_extensions, parser} from "@nextjournal/clojure-mode";
import {buildEditorIndex, createProjectCompletionSource, readEditorReference, readTemplateHighlights} from "./editor-intelligence.js";
import {DBT_HELP, EDN_HELP} from "./editor-help.js";

const resources = [
  {name: "orders", resource_type: "model", package_name: "example", path: "models/orders.sql", description: "Orders at their original grain.", columns: [{name: "order_id", data_type: "BIGINT"}, {name: "amount", data_type: "DECIMAL", description: "Order amount."}]},
  {name: "customers", resource_type: "model", package_name: "example", path: "models/customers.sql", columns: ["customer_id", "name"]},
  {name: "events", source_name: "raw", resource_type: "source", columns: ["event_id"]},
  {name: "contacts", source_name: "crm", resource_type: "source", columns: ["external_id"]},
  {name: "shared", resource_type: "model", package_name: "alpha", columns: ["alpha_id"]},
  {name: "shared", resource_type: "model", package_name: "beta", columns: ["beta_id"]},
  {name: "money", resource_type: "macro", package_name: "example", path: "macros/money.sql", description: "Format an amount.", arguments: [{name: "amount", type: "numeric", description: "Amount expression."}]},
  {name: "shared_macro", resource_type: "macro", package_name: "alpha", arguments: []},
  {name: "shared_macro", resource_type: "macro", package_name: "beta", arguments: []}
];
const index = buildEditorIndex({resources}, {orders: [{name: "amount", type: "DOUBLE"}, {name: "observed_only", type: "VARCHAR"}]});

function complete(marked, format = "sql", explicit = true, readOnly = false) {
  const pos = marked.indexOf("|");
  if (pos < 0) throw new Error("A completion fixture needs a cursor.");
  const doc = marked.slice(0, pos) + marked.slice(pos + 1);
  const state = EditorState.create({doc, selection: {anchor: pos}, extensions: [EditorState.readOnly.of(readOnly), ...(format === "edn" ? [default_extensions[0]] : [])]});
  expect(state.readOnly).toBe(readOnly);
  const before = state.doc.toString();
  const result = createProjectCompletionSource(index, format)(new CompletionContext(state, pos, explicit));
  expect(state.doc.toString()).toBe(before);
  return {result, doc, pos, labels: result?.options.map(option => option.label) || []};
}

function reference(marked, format = "sql") {
  const pos = marked.indexOf("|");
  return readEditorReference(marked.replace("|", ""), pos, format, index);
}

test("catalog merge keeps declared types and docs without mutating input", () => {
  const orders = index.relations.find(resource => resource.name === "orders");
  expect(orders.columns.find(column => column.name === "amount")).toEqual({name: "amount", data_type: "DECIMAL", description: "Order amount.", type: "DOUBLE"});
  expect(orders.columns.find(column => column.name === "observed_only").data_type).toBe("VARCHAR");
  expect(resources[0].columns).toHaveLength(2);
});

test("SQL fields resolve plain aliases and static dbt ref aliases", () => {
  for (const from of ["orders AS o", "{{ ref('orders') }} o", "{{ builtins.ref('example', 'orders') }} AS o"]) {
    const completion = complete(`SELECT o.am| FROM ${from}`);
    expect(completion.labels).toEqual(["order_id", "amount", "observed_only"]);
    expect(completion.result.from).toBe("SELECT o.".length);
    expect(completion.result.options.find(option => option.label === "amount").detail).toContain("DECIMAL");
  }
  expect(complete("SELECT c.na| FROM orders o JOIN customers c ON c.customer_id=o.order_id").labels).toContain("name");
});

test("static source calls bind fields while dynamic and ambiguous refs do not", () => {
  expect(complete("SELECT e.ev| FROM {{ source('raw', 'events') }} e").labels).toEqual(["event_id"]);
  for (const from of ["{{ ref(var('model')) }} o", "{{ ref('shared') }} o", "{{ ref('orders' ~ suffix) }} o"]) {
    expect(complete(`SELECT o.am| FROM ${from}`).result).toBeNull();
  }
  expect(complete("SELECT s.al| FROM {{ ref('alpha', 'shared') }} s").labels).toEqual(["alpha_id"]);
  expect(complete("SELECT s.be| FROM {{ ref('beta', 'shared') }} s").labels).toEqual(["beta_id"]);
});

test("ref and source arguments suggest model names and source-specific tables", () => {
  expect(complete("SELECT * FROM {{ ref('or|') }}").labels).toContain("orders");
  expect(complete("SELECT * FROM {{ ref('alpha', 'sh|') }}").labels).toEqual(["shared"]);
  expect(complete("SELECT * FROM {{ source('r|', 'events') }}").labels).toEqual(["raw", "crm"]);
  expect(complete("SELECT * FROM {{ source('raw', 'ev|') }}").labels).toEqual(["events"]);
  expect(complete("SELECT * FROM {{ source('crm', 'co|') }}").labels).toEqual(["contacts"]);
});

test("SQL comments and literals cannot supply references or completion text", () => {
  for (const marked of ["SELECT 'orders.am|ount'", "-- orders.am|ount", "/* orders.am|ount */", "SELECT '{{ ref(\"orders\") }} o.am|ount'"]) {
    expect(complete(marked).result).toBeNull();
    expect(reference(marked)).toBeNull();
  }
  expect(complete("SELECT o.am| FROM orders o; SELECT * FROM customers o").labels).toContain("amount");
  expect(complete("SELECT o.am| FROM '{{ ref(\"orders\") }}' o").result).toBeNull();
});

test("explicit completion inside empty SQL comments remains suppressed", () => {
  expect(complete("SELECT 1 -- |", "sql", true).result).toBeNull();
  expect(complete("SELECT /* | */ 1", "sql", true).result).toBeNull();
});

test("CTE output columns are not invented without derived metadata", () => {
  expect(complete("WITH recent AS (SELECT amount FROM orders) SELECT r.am| FROM recent r").result).toBeNull();
});

test("partial templates offer builtins and package-qualified macro completions", () => {
  const labels = complete("SELECT {{ mo|").labels;
  expect(labels).toContain("money");
  expect(labels).toContain("example.money");
  expect(labels).toContain("alpha.shared_macro");
  expect(labels).toContain("beta.shared_macro");
  expect(labels).not.toContain("shared_macro");
  expect(labels).toContain("ref");
  expect(complete("{{ var('not_a_model|') }}").result).toBeNull();
});

test("resource and macro references expose declared documentation and exact ranges", () => {
  const field = reference("SELECT o.am|ount FROM {{ ref('orders') }} o");
  expect(field).toMatchObject({title: "amount", signature: "orders.amount", dataType: "DECIMAL", description: "Order amount.", path: "models/orders.sql"});
  const model = reference("SELECT * FROM {{ ref('ord|ers') }}");
  expect(model).toMatchObject({title: "orders", kind: "model", path: "models/orders.sql"});
  const macro = reference("SELECT {{ example.mo|ney('amount') }}");
  expect(macro).toMatchObject({title: "example.money", signature: "money(amount)", description: "Format an amount.", path: "macros/money.sql"});
  expect(macro.arguments[0]).toMatchObject({name: "amount", type: "numeric"});
  expect(reference("{{ shared_ma|cro() }}")).toBeNull();
  expect(reference("{{ re|f('orders') }}").url).toBe(DBT_HELP.find(entry => entry.name === "ref").url);
});

test("EDN parser completes HoneySQL clauses, relation keywords and alias fields", () => {
  const query = "{:select [:o.am|] :from [[:orders :o]] :join [[:customers :c] [:= :o.order_id :c.customer_id]]}";
  expect(parser.parse(query.replace("|", "")).topNode.name).toBe("Program");
  expect(complete(query, "edn").labels).toEqual(["order_id", "amount", "observed_only"]);
  expect(complete("{:select [:c.na|] :from [[:orders :o]] :left-join [[:customers :c] [:= :o.order_id :c.customer_id]]}", "edn").labels).toEqual(["customer_id", "name"]);
  const labels = complete("{:se| []}", "edn").labels;
  expect(labels).toContain(":select");
  expect(labels).toContain(":orders");
  expect(labels).toContain(":amount");
});

test("EDN field and clause references carry model docs and HoneySQL help", () => {
  expect(reference("{:select [:o.am|ount] :from [[:orders :o]]}", "edn")).toMatchObject({title: "amount", dataType: "DECIMAL", path: "models/orders.sql"});
  expect(reference("{:select [:amount] :from [:ord|ers]}", "edn")).toMatchObject({title: "orders", kind: "model"});
  expect(reference("{:whe|re [:= :amount 1]}", "edn").description).toBe(EDN_HELP.find(entry => entry.name === ":where").description);
});

test("EDN strings, comments and discarded forms offer no metadata references", () => {
  for (const marked of ['{:select [":am|ount"]}', "; :am|ount\n{}", "#_{:select [:am|ount]} {:select [1]}"]) {
    expect(complete(marked, "edn").result).toBeNull();
    expect(reference(marked, "edn")).toBeNull();
  }
  expect(complete("{:select [:x.am|] :from [:shared]}", "edn").labels).toEqual([]);
});

test("template highlights ignore literal and comment lookalikes", () => {
  const text = "-- {{ ref('hidden') }}\nSELECT '{{ literal }}', {{ ref('orders') }}";
  const slices = readTemplateHighlights(text).map(range => text.slice(range.from, range.to));
  expect(slices).toEqual(["{{", "}}", "ref", "'orders'"]);
  expect(readTemplateHighlights("{{ ref('orders')").some(range => range.className === "syntax-template-function")).toBe(true);
});

test("plain text completion and reference lookup leave buffers untouched", () => {
  expect(complete("orders|", "text").result).toBeNull();
  expect(reference("orders|", "text")).toBeNull();
});


test("explicit EDN completion stays outside comments, strings and discarded forms", () => {
  for (const marked of ["; |\n{}", '#_{:select [|]} {}', '{:select [" | "]}']) {
    expect(complete(marked, "edn", true).result).toBeNull();
  }
});

test("readonly completion reads metadata without modifying SQL or EDN", () => {
  expect(complete("SELECT o.am| FROM orders o", "sql", true, true).labels).toContain("amount");
  expect(complete("{:select [:o.am|] :from [[:orders :o]]}", "edn", true, true).labels).toContain("amount");
});


test("SQL named parameters retain bindings without column completion or navigation", () => {
  for (const marked of ["SELECT :am|ount FROM orders", "SELECT amount FROM orders WHERE amount = :am|ount"]) {
    expect(complete(marked).result).toBeNull();
    expect(reference(marked)).toBeNull();
  }
});

test("filtered qualified refs cannot identify the resulting relation", () => {
  const expression = "{{ builtins.ref('example', 'orders') | replace('orders', 'customers') }}";
  expect(complete(`SELECT o.am| FROM ${expression} o`).result).toBeNull();
  expect(reference("SELECT * FROM {{ builtins.ref('example', 'ord|ers') | replace('orders', 'customers') }}")).toBeNull();
});

test("discarded EDN relations and fields cannot create navigable metadata", () => {
  for (const marked of [
    "#_{:from [:ord|ers]} {:select [1]}",
    "{:select [#_:am|ount :order_id] :from [:orders]}",
    "#_{:from [[:orders :o]]} {:select [:o.am|ount]}"
  ]) {
    expect(reference(marked, "edn")).toBeNull();
  }
});

test("CTE names and aliases shadow manifest resources without borrowing their columns", () => {
  expect(reference("WITH orders AS (SELECT 1 AS amount) SELECT * FROM or|ders")).toBeNull();
  expect(reference("WITH orders(amount) AS (SELECT 1) SELECT o.am|ount FROM orders o")).toBeNull();
  expect(complete("WITH orders AS (SELECT 1 AS amount) SELECT o.am| FROM orders o").result).toBeNull();
  expect(complete("WITH orders AS (SELECT 1 AS amount) SELECT am| FROM orders").labels).not.toContain("amount");
  expect(complete("WITH orders AS (SELECT 1 AS amount), customers AS (SELECT 2 AS name) SELECT c.na| FROM customers c").result).toBeNull();
  expect(reference("WITH recent AS (SELECT o.am|ount FROM orders o) SELECT * FROM recent").path).toBe("models/orders.sql");
});

test("nested queries and set branches isolate binding names", () => {
  expect(reference("SELECT am|ount FROM (SELECT amount FROM orders) recent")).toBeNull();
  expect(complete("SELECT o.am| FROM (SELECT amount FROM orders o) o").result).toBeNull();
  expect(reference("SELECT * FROM customers c WHERE EXISTS (SELECT o.am|ount FROM orders o)").path).toBe("models/orders.sql");
  expect(complete("SELECT c.na| FROM customers c WHERE EXISTS (SELECT 1 FROM orders c)").labels).toContain("name");
  expect(reference("SELECT o.am|ount FROM orders o UNION ALL SELECT name FROM customers o").path).toBe("models/orders.sql");
  expect(reference("SELECT amount FROM orders o UNION ALL SELECT o.na|me FROM customers o").path).toBe("models/customers.sql");
  expect(complete("SELECT o.am| FROM orders o UNION ALL SELECT name FROM customers o").labels).not.toContain("name");
});

test("only adjacent Jinja whitespace trim markers preserve direct relation calls", () => {
  for (const relation of ["{{- ref('orders') -}}", "{{- ref('orders') }}", "{{ ref('orders') -}}"])
    expect(complete(`SELECT o.am| FROM ${relation} o`).labels).toContain("amount");
  for (const relation of ["{{ -ref('orders') }}", "{{ - ref('orders') }}", "{{ ref('orders') - }}"])
    expect(complete(`SELECT o.am| FROM ${relation} o`).result).toBeNull();
  expect(reference("{{ -ref('ord|ers') }}")).toBeNull();
});

test("SQL relation completions insert real names with component quoting and aliases", () => {
  const metadata = buildEditorIndex({resources: [
    {name: "items", alias: "published_items", relation_name: "analytics.published_items", resource_type: "model", columns: []},
    {name: "other", relation_name: '"CATALOG"."Analytics"."Item.Name"', resource_type: "source", columns: []},
    {name: "spaced", relation_name: "reporting.Order Items", resource_type: "model", columns: []},
    {name: "escaped", relation_name: '"Analytics"."A""B"', resource_type: "model", columns: []},
    {name: "aliased", alias: "actual_table", resource_type: "model", columns: []},
    {name: "source_name", identifier: "raw_table", resource_type: "source", columns: []}
  ]});
  const state = EditorState.create({doc: "SELECT * FROM "});
  const result = createProjectCompletionSource(metadata, "sql")(new CompletionContext(state, state.doc.length, true));
  const relations = result.options.filter(option => option.type === "class");
  expect(relations.map(option => option.apply)).toEqual(["analytics.published_items", '"CATALOG"."Analytics"."Item.Name"', 'reporting."Order Items"', '"Analytics"."A""B"', "actual_table", "raw_table"]);
  expect(relations[0].label).toBe("analytics.published_items");
  const quoted = buildEditorIndex({resources: [{name: "items", relation_name: '"Analytics"."Published Items"', resource_type: "model", path: "models/items.sql", columns: ["amount"]}]});
  const text = 'SELECT amount FROM "Analytics"."Published Items"';
  expect(readEditorReference(text, text.indexOf("Published") + 2, "sql", quoted).path).toBe("models/items.sql");
});

test("unresolved derived relations keep unqualified field ownership unknown", () => {
  expect(reference("SELECT am|ount FROM (SELECT 1 AS amount) recent JOIN orders o ON true")).toBeNull();
  expect(complete("SELECT am| FROM (SELECT 1 AS amount) recent JOIN orders o ON true").labels).not.toContain("amount");
  expect(reference("SELECT o.am|ount FROM (SELECT 1 AS amount) recent JOIN orders o ON true").path).toBe("models/orders.sql");
});
