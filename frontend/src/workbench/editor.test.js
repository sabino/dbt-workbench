import {test, expect} from "bun:test";
import {format} from "sql-formatter";
import {executionInput, statementRanges, formatSql} from "./editor.js";

globalThis.sqlFormatter = {format};

test("statement splitting ignores quoted, dollar quoted, and commented semicolons", function verifyBoundaries() {
  const text = `SELECT ';', 'it''s;', $$a;b$$, $tag$c;d$tag$; -- ;\n/* outer ; /* inner ; */ */ SELECT 2; -- trailing comment`;
  expect(statementRanges(text).map(range => text.slice(range.from, range.to).trim())).toEqual([
    `SELECT ';', 'it''s;', $$a;b$$, $tag$c;d$tag$`, `-- ;\n/* outer ; /* inner ; */ */ SELECT 2`
  ]);
});

test("selection wins over cursor, all executes document order", function verifyScope() {
  const text = "SELECT '😀';\nSELECT 2; SELECT 3;";
  const position = text.indexOf("SELECT 2");
  expect(executionInput(text, {from: position, to: position, head: position + 4}, "sql").content.trim()).toBe("SELECT 2");
  expect(executionInput(text, {from: position, to: position + 8, head: position + 8}, "sql").content).toBe("SELECT 2");
  expect(executionInput(text, {from: 0, to: 0, head: 0}, "sql", true).statements.map(value => value.trim())).toEqual(["SELECT '😀'", "SELECT 2", "SELECT 3"]);
});

test("empty and comment-only documents cannot submit statements", function verifyEmpty() {
  expect(statementRanges("; -- comment ;\n/* nothing */")).toEqual([]);
  expect(executionInput("", {from: 0, to: 0, head: 0}, "sql", true).statements).toEqual([]);
});

test("formatting preserves parameters and quoted literals", function verifyFormatting() {
  const formatted = formatSql("select customer_id from orders where status=:status and note='select;:ignored'");
  expect(formatted).toContain("SELECT\n");
  expect(formatted).toContain(":status");
  expect(formatted).not.toContain(": status");
  expect(formatted).toContain("'select;:ignored'");
});

test("formatting honors keyword case and tab indentation", function verifyPreferences() {
  const formatted = formatSql("SELECT customer_id,SUM(amount) FROM orders GROUP BY customer_id", {keywordCase: "lower", useTabs: true});
  expect(formatted).toContain("select\n\tcustomer_id");
  expect(formatted).toContain("group by\n");
});
