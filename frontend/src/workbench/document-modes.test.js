import {test, expect} from "bun:test";
import {planModeChange, recordConversion, canonicalContent, isDocumentDirty, matchesConversionRequest} from "./document-modes.js";

const sql = {mode: "sql", content: "-- Keep this comment\nselect id  from customers;\n", parameters: "{}", selection: {from: 3, to: 3, head: 3}};
const edn = {mode: "edn", content: "{:select [:id], :from [:customers]}\n", parameters: "{}", selection: {from: 0, to: 0, head: 0}};
const converted = {...edn, conversion: recordConversion(sql, edn)};

test("an untouched EDN view restores exact SQL, comments, selection and clean state", () => {
  expect(planModeChange(converted, "sql")).toMatchObject({kind: "restore", content: sql.content, selection: sql.selection});
  expect(canonicalContent(converted, "sql")).toBe(sql.content);
  expect(isDocumentDirty({...converted, nativeMode: "sql", savedContent: sql.content})).toBe(false);
});

test("an edited EDN view needs compilation and remains an unsaved SQL draft", () => {
  const changed = {...converted, content: "{:select [:name], :from [:customers]}"};
  expect(planModeChange(changed, "sql").kind).toBe("convert");
  expect(canonicalContent(changed, "sql")).toBeNull();
  expect(isDocumentDirty({...changed, nativeMode: "sql", savedContent: sql.content})).toBe(true);
  expect(planModeChange({...changed, content: edn.content}, "sql").kind).toBe("restore");
});

test("mode changes retain an existing SQL draft rather than claiming it is saved", () => {
  expect(isDocumentDirty({...converted, nativeMode: "sql", savedContent: "SELECT id FROM customers"})).toBe(true);
  expect(planModeChange(sql, "edn").kind).toBe("convert");
});

test("native EDN files restore exact EDN after viewing generated SQL", () => {
  const original = {...edn, content: "{:select [[42 :answer]]}"};
  const generated = {mode: "sql", content: 'SELECT :p0 AS "answer"', parameters: '{"p0":42}', selection: {head: 0, from: 0, to: 0}};
  const view = {...generated, conversion: recordConversion(original, generated)};
  expect(planModeChange(view, "edn")).toMatchObject({kind: "restore", content: original.content, parameters: "{}"});
  expect(canonicalContent(view, "edn")).toBe(original.content);
  expect(planModeChange({...view, parameters: '{"p0":43}'}, "edn").kind).toBe("convert");
  expect(isDocumentDirty({...view, parameters: '{"p0":43}', nativeMode: "edn", savedContent: original.content})).toBe(true);
});

test("changed named bindings survive restoration of untouched SQL", () => {
  const source = {...sql, content: "select id from customers where country=:country", parameters: '{"country":"CA"}'};
  const target = {...edn, content: "{:select [:id], :from [:customers], :where [:= :country [:param :country]]}", parameters: source.parameters};
  const view = {...target, conversion: recordConversion(source, target), parameters: '{"country":"US"}'};
  expect(planModeChange(view, "sql")).toMatchObject({kind: "restore", content: source.content, parameters: view.parameters});
});

test("blank and text documents stay intact; untitled files remain unsaved", () => {
  expect(planModeChange({...sql, content: ""}, "edn").kind).toBe("mode");
  expect(canonicalContent({...sql, content: ""}, "edn")).toBe("");
  expect(planModeChange({...sql, mode: "text", content: "a,b\n1,2"}, "sql")).toMatchObject({kind: "mode", content: "a,b\n1,2"});
  expect(isDocumentDirty({...sql, nativeMode: "sql", savedContent: sql.content, untitled: true})).toBe(true);
});

test("stale conversion responses cannot replace a different draft or binding", () => {
  expect(matchesConversionRequest(sql, sql)).toBe(true);
  expect(matchesConversionRequest({...sql, content: "SELECT 7"}, sql)).toBe(false);
  expect(matchesConversionRequest({...sql, parameters: '{"x":1}'}, sql)).toBe(false);
  expect(matchesConversionRequest(null, sql)).toBe(false);
});
