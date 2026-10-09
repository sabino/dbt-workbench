import React from "react";
import {clojure} from "@nextjournal/lang-clojure";
import {EditorState, Compartment} from "@codemirror/state";
import {EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, Decoration, ViewPlugin} from "@codemirror/view";
import {defaultKeymap, history, historyKeymap, indentWithTab} from "@codemirror/commands";
import {autocompletion, closeBrackets, closeBracketsKeymap} from "@codemirror/autocomplete";
import {sql, PostgreSQL} from "@codemirror/lang-sql";
import {HighlightStyle, syntaxHighlighting, defaultHighlightStyle, bracketMatching, indentOnInput} from "@codemirror/language";

function isLetter(char) { return Boolean(char) && ((char >= "a" && char <= "z") || (char >= "A" && char <= "Z") || char === "_"); }
function isWord(char) { return isLetter(char) || Boolean(char && char >= "0" && char <= "9"); }

// Offsets use UTF-16, matching CodeMirror selections and JavaScript slicing.
export function statementRanges(text) {
  const ranges = [];
  let start = 0;
  let hasCode = false;
  let escapes = false;
  let quote = null;
  let blockDepth = 0;
  let lineComment = false;
  let dollar = null;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    const next = text[index + 1];
    if (lineComment) {
      if (char === "\n") lineComment = false;
      continue;
    }
    if (blockDepth) {
      if (char === "/" && next === "*") { blockDepth += 1; index += 1; }
      else if (char === "*" && next === "/") { blockDepth -= 1; index += 1; }
      continue;
    }
    if (dollar) {
      if (text.startsWith(dollar, index)) { index += dollar.length - 1; dollar = null; }
      continue;
    }
    if (quote) {
      if (char === quote && next === quote) index += 1;
      else if (char === quote) quote = null;
      else if (char === "\\" && escapes) index += 1;
      continue;
    }
    if (char === "-" && next === "-") { lineComment = true; index += 1; }
    else if (char === "/" && next === "*") { blockDepth = 1; index += 1; }
    else if (char === "'" || char === '"' || char === "`") {
      quote = char;
      escapes = char === "'" && ["e", "E"].includes(text[index - 1]) && !isWord(text[index - 2]);
      hasCode = true;
    }
    else if (char === "$") {
      let end = index + 1;
      while (end < text.length && isWord(text[end])) end += 1;
      const tag = text[end] === "$" && (end === index + 1 || isLetter(text[index + 1])) ? [text.slice(index, end + 1)] : null;
      hasCode = true;
      if (tag) { dollar = tag[0]; index += dollar.length - 1; }
    } else if (char === ";") {
      if (hasCode) ranges.push({from: start, to: index});
      start = index + 1;
      hasCode = false;
    } else if (char.trim() !== "") hasCode = true;
  }
  if (hasCode) ranges.push({from: start, to: text.length});
  return ranges;
}

export function executionInput(text, selection, format, all = false) {
  if (format === "edn") return {"content": text, "statements": [text], "execution_scope": "document"};
  const ranges = statementRanges(text);
  if (all) return {"content": text, "statements": ranges.map(range => text.slice(range.from, range.to)), "execution_scope": "all"};
  if (selection["from"] !== selection["to"]) {
    const content = text.slice(selection["from"], selection["to"]);
    return {"content": content, "statements": [content], "execution_scope": "selection"};
  }
  const range = ranges.find(range => selection["head"] >= range.from && selection["head"] <= range.to) || ranges.find(range => range.from > selection["head"]) || ranges.at(-1);
  const content = range ? text.slice(range.from, range.to) : "";
  return {"content": content, "statements": [content], "execution_scope": "statement"};
}

const queryHighlightStyle = HighlightStyle.define(defaultHighlightStyle["specs"].map(function styleToken(spec) {
  if (spec["color"] === "#708") return {...spec, "color": "var(--syntax-keyword)", "fontWeight": "600"};
  if (spec["color"] === "#940") return {...spec, "color": "var(--muted)"};
  if (["#164", "#a11", "#085"].includes(spec["color"])) return {...spec, "color": "var(--syntax-string)"};
  if (spec["color"] === "#219") return {...spec, "color": "var(--syntax-number)"};
  if (["#30a", "#256", "#00f", "#a50"].includes(spec["color"])) return {...spec, "color": "var(--syntax-function)"};
  return spec;
}));

export function formatSql(text, options = {}) {
  return globalThis["sqlFormatter"]["format"](text, {"language": "duckdb", "keywordCase": "upper", "paramTypes": {"named": [":"]}, ...options});
}

export function focusQueryEditor() {
  document.querySelector(".query-code-editor .cm-content")?.focus();
}

export function QueryEditor(props) {
  const host = React.useRef(null);
  const view = React.useRef(null);
  const callbacks = React.useRef(props);
  callbacks.current = props;
  const language = React.useRef(new Compartment());
  function runEditor(editor, all) {
    const selection = editor["state"]["selection"]["main"];
    callbacks.current["onRun"](all, editor["state"]["doc"].toString(),
      {"from": selection["from"], "to": selection["to"], "head": selection["head"]});
  }
  function highlightStatement(editor) {
    if (callbacks.current["format"] !== "sql") return Decoration.none;
    const selection = editor["state"]["selection"]["main"];
    if (!selection["empty"]) return Decoration.none;
    const range = statementRanges(editor["state"]["doc"].toString()).find(range => selection["head"] >= range.from && selection["head"] <= range.to);
    if (!range || range.to <= range.from) return Decoration.none;
    const document = editor["state"]["doc"];
    const text = document.toString().slice(range.from, range.to);
    const firstLine = document["lineAt"](range.from + text.length - text.trimStart().length)["number"];
    const lastLine = document["lineAt"](range.to)["number"];
    return Decoration.set(Array.from({length: lastLine - firstLine + 1}, (_, index) =>
      Decoration.line({class: "active-statement"}).range(document["line"](firstLine + index)["from"])));
  }
  React.useEffect(function mountEditor() {
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({doc: props["value"], selection: {anchor: props["selection"]?.["from"] || 0, head: props["selection"]?.["head"] || 0}, extensions: [
        EditorView.lineWrapping, lineNumbers(), highlightActiveLine(), highlightActiveLineGutter(), drawSelection(),
        history(), bracketMatching(), closeBrackets(), autocompletion({icons: false}), indentOnInput(), syntaxHighlighting(queryHighlightStyle),
        ViewPlugin.define(function decorateStatement(editor) {
          return {decorations: highlightStatement(editor), update: function updateHighlight(update) {
            this.decorations = highlightStatement(update["view"]);
          }};
        }, {decorations: function getDecorations(plugin) { return plugin.decorations; }}),
        language.current.of(props["format"] === "sql" ? sql({dialect: PostgreSQL}) : clojure()),
        EditorView.contentAttributes.of({"aria-label": "Query editor", "spellcheck": "false"}),
        keymap.of([
          {key: "Shift-Enter", run: function runStatement(editor) { runEditor(editor, false); return true; }},
          {key: "F5", run: function runF5(editor) { runEditor(editor, false); return true; }},
          {key: "Mod-Enter", run: function runModEnter(editor) { runEditor(editor, false); return true; }},
          {key: "Mod-Shift-Enter", run: function runAll(editor) { runEditor(editor, true); return true; }},
          ...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, indentWithTab
        ]),
        EditorView.updateListener.of(function updateEditor(update) {
          if (update["docChanged"]) callbacks.current["onChange"](update["state"]["doc"].toString());
          if (update["selectionSet"] || update["docChanged"]) {
            const selection = update["state"]["selection"]["main"];
            callbacks.current["onSelection"]({"from": selection["from"], "to": selection["to"], "head": selection["head"]});
          }
        })
      ]})
    });
    view.current = editor;
    return function destroyEditor() { editor["destroy"](); view.current = null; };
  }, []);
  React.useEffect(function updateDocument() {
    if (view.current && view.current["state"]["doc"].toString() !== props["value"]) {
      const selection = props["selection"] || {from: 0, to: 0, head: 0};
      view.current["dispatch"]({changes: {from: 0, to: view.current["state"]["doc"].length, insert: props["value"]},
        selection: {anchor: Math.min(selection["head"] === selection["from"] ? selection["to"] : selection["from"], props["value"].length),
                    head: Math.min(selection["head"], props["value"].length)}});
    }
  }, [props["value"]]);
  React.useEffect(function updateLanguage() {
    view.current?.["dispatch"]({effects: language.current["reconfigure"](props["format"] === "sql" ? sql({dialect: PostgreSQL}) : clojure())});
  }, [props["format"]]);
  return React.createElement("div", {ref: host, className: "query-code-editor"});
}
