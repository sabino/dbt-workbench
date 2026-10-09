import {expect, test} from "bun:test";
import {parseDraftMetadata} from "./metadata-panel.js";
function parseSql(content) { return parseDraftMetadata("models/example.sql", content)["sql"]; }

test("outer projection excludes CTE and nested SELECT fields", function verifyOuterSelect() {
  const draft = parseSql(`WITH c AS (SELECT wrong_name, amount FROM input), d AS (SELECT another_wrong FROM input)
SELECT c.amount, (SELECT max(value) FROM detail) AS largest, concat('a,b', amount) AS "Display name" FROM c`);
  expect(draft["projection"]["complete"]).toBe(true);
  expect(draft["projection"]["fields"].map(field => field["name"])).toEqual(["amount", "largest", "Display name"]);
  expect(draft["projection"]["fields"].every(field => field["data_type"] === null)).toBe(true);
});

test("comments, strings, quoted keywords and dollar quotes cannot fabricate metadata", function verifyLexicalBoundaries() {
  const draft = parseSql(`-- SELECT bogus, {{ ref('comment_model') }}
/* outer SELECT bad /* {{ source('ignored', 'table') }} */ */
{# {{ config(materialized='wrong') }} #}
SELECT '{{ ref(''literal_model'') }}' AS message, $$SELECT hidden, {{ ref('dollar_model') }}$$ AS body,
'comma, FROM clause' AS note, "FROM" FROM {{ ref('actual_model') }}`);
  expect(draft["references"]).toEqual([{"name": "actual_model", "package": null}]);
  expect(draft["sources"]).toEqual([]);
  expect(draft["config"]).toEqual({});
  expect(draft["projection"]["fields"].map(field => field["name"])).toEqual(["message", "body", "note", "FROM"]);
});

test("static Jinja calls preserve literal dependency and config data", function verifyStaticJinja() {
  const draft = parseSql(`{{- config(materialized='view', enabled=true, tags=['daily', 'public']) -}}
SELECT id FROM {{ ref('shared', 'items', version=2) }} JOIN {{ source('raw', 'events') }} e ON e.id=id`);
  expect(draft["references"]).toEqual([{"name": "items", "package": "shared"}]);
  expect(draft["sources"]).toEqual([{"source": "raw", "table": "events"}]);
  expect(draft["config"]).toEqual({"materialized": "view", "enabled": true, "tags": ["daily", "public"]});
  expect(draft["projection"]["fields"].map(field => field["name"])).toEqual(["id"]);
});

test("dynamic arguments, control blocks, wildcards and batches remain unknown", function verifyUnknownSchemas() {
  const dynamic = parseSql(`SELECT {{ var('fields') }} FROM {{ ref(var('model')) }}`);
  expect(dynamic["references"]).toEqual([]);
  expect(dynamic["diagnostics"].join(" ")).toContain("Dynamic ref");
  expect(dynamic["projection"]["complete"]).toBe(false);
  expect(parseSql("SELECT {{ fields() }} AS named FROM input")["projection"]["complete"]).toBe(false);
  expect(parseSql(`{% if flag %} SELECT a {% else %} SELECT b {% endif %} FROM input`)["projection"]["fields"]).toEqual([]);
  expect(parseSql("SELECT t.*, value AS named FROM input t")["projection"]["complete"]).toBe(false);
  expect(parseSql("SELECT id; SELECT amount")["projection"]["fields"]).toEqual([]);
  expect(parseSql("SELECT id FROM a UNION SELECT other FROM b")["projection"]["complete"]).toBe(false);
});

test("incomplete drafts and unnamed expressions cannot claim complete schema", function verifyIncompleteDrafts() {
  expect(parseSql("SELECT id, amount + 1 FROM input")["projection"]["complete"]).toBe(false);
  ["SELECT 'unfinished", "SELECT id /* unfinished", "SELECT {{ ref('unfinished')", "-- SELECT hidden"].forEach(text => expect(parseSql(text)["projection"]["fields"]).toEqual([]));
  expect(parseSql("SELECT (amount AS total")["projection"]["fields"]).toEqual([]);
  expect(parseSql("CREATE VIEW output AS SELECT id FROM input")["projection"]["fields"]).toEqual([]);
});

test("nested literal config maps are parsed without evaluation or inherited keys", function verifyConfigData() {
  const config = parseSql(`{{ config(meta={'owner':'Example team','flags':[true,false]}, __proto__={'x':1}, timeout=-2) }} SELECT id`)["config"];
  expect(config["meta"]).toEqual({"owner": "Example team", "flags": [true, false]});
  expect(Object.hasOwn(config, "__proto__")).toBe(true);
  expect(Object.getPrototypeOf(config)).toBe(Object.prototype);
  expect(config["timeout"]).toBe(-2);
  expect(parseSql(`{{ "ref('inside_string')" }} SELECT id`)["references"]).toEqual([]);
});

test("JSON describes scalar, null, array and nested top-level types", function verifyJsonMetadata() {
  const json = parseDraftMetadata("example.json", '{"enabled":true,"note":null,"items":[1,2],"nested":{"x":1}}')["json"];
  expect(json["kind"]).toBe("object");
  expect(json["size"]).toBe(4);
  expect(json["fields"].map(field => [field["key"], field["type"], field["size"]])).toEqual([["enabled", "boolean", null], ["note", "null", null], ["items", "array", 2], ["nested", "object", 1]]);
  expect(parseDraftMetadata("example.json", "42")["json"]["value"]).toBe(42);
  expect(parseDraftMetadata("example.json", "null")["json"]["kind"]).toBe("null");
  expect(parseDraftMetadata("example.json", "[1,2]")["json"]["size"]).toBe(2);
  expect(parseDraftMetadata("example.json", "{broken}")["json"]["error"]).toBeTruthy();
});

test("generic file details use draft content and all newline forms", function verifyFileDetails() {
  expect(parseDraftMetadata("notes.md", "one\r\ntwo\nthree")["line_count"]).toBe(3);
  expect(parseDraftMetadata("notes.md", "")["line_count"]).toBe(0);
  expect(parseDraftMetadata("config.yml", "name: sample")["type"]).toBe("yml");
  expect(parseDraftMetadata("README", "text")["sql"]).toBeUndefined();
});
