import React from "react";
import {EditorState, Compartment, StateEffect, StateField} from "@codemirror/state";
import {EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, Decoration, ViewPlugin, hoverTooltip, showTooltip} from "@codemirror/view";
import {defaultKeymap, history, historyKeymap, indentWithTab, isolateHistory} from "@codemirror/commands";
import {autocompletion, closeBrackets, closeBracketsKeymap, startCompletion, closeCompletion} from "@codemirror/autocomplete";
import {sql, PostgreSQL, StandardSQL, SQLite, keywordCompletionSource} from "@codemirror/lang-sql";
import {HighlightStyle, syntaxHighlighting, defaultHighlightStyle, bracketMatching, indentOnInput} from "@codemirror/language";
import {createEdnExtensions, runEdnCommand} from "./edn-structure.js";
import {buildEditorIndex, createProjectCompletionSource, readEditorReference, readTemplateHighlights, renderEditorDocumentation} from "./editor-intelligence.js";

const editors = new WeakMap();
const documentationEffect = StateEffect.define();
const documentation = StateField.define({
  create() { return null; },
  update(value, transaction) {
    if (transaction.docChanged || transaction.selection) value = null;
    for (const effect of transaction.effects) if (effect.is(documentationEffect)) value = effect.value;
    return value;
  },
  provide: field => showTooltip.compute([field], state => state.field(field))
});

function readVisibleEditor() {
  const dom = document.querySelector(".main-pane .file-editor-pane:not([hidden]) .cm-editor");
  return dom ? editors.get(dom) : null;
}

function showEditorDocumentation(editor, entry) {
  const {view, callbacks} = editor;
  const fallback = {title: "Editor help", description: entry ? "This reference has no local definition." : callbacks.current["intelligence"]?.["empty_reason"] || "Place the cursor on a model, field, macro, dbt function, or HoneySQL keyword."};
  const description = entry || fallback;
  view.dispatch({effects: documentationEffect.of({pos: entry?.from ?? view.state.selection.main.head,
    end: entry?.to, above: true, create: () => ({dom: renderEditorDocumentation(description, path => {
      view.dispatch({effects: documentationEffect.of(null)});
      callbacks.current["onNavigate"]?.(path);
    })})})});
  return true;
}

export function runEditorCommand(name) {
  const editor = readVisibleEditor();
  if (!editor) return false;
  const {view, callbacks, index} = editor;
  const props = callbacks.current;
  view.focus();
  if (name === "trigger-completion") return !view.state.readOnly && startCompletion(view);
  if (name.startsWith("edn-")) {
    if (props["format"] !== "edn") return false;
    const navigation = new Set(["edn-forward", "edn-backward", "edn-up", "edn-down", "edn-select-expand", "edn-select-contract"]);
    if (view.state.readOnly && !navigation.has(name)) return false;
    closeCompletion(view);
    return runEdnCommand(view, name);
  }
  const entry = readEditorReference(view.state.doc.toString(), view.state.selection.main.head, props["format"], index.current);
  if (name === "goto-definition" && entry?.path) { props["onNavigate"]?.(entry.path); return true; }
  if (name === "show-documentation" || name === "goto-definition") return showEditorDocumentation(editor, entry);
  return false;
}

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
  if (document.querySelector("dialog[open]")) return;
  document.querySelector(".main-pane .file-editor-pane:not([hidden]) .cm-content")?.focus();
}

function createLanguage(props, index) {
  if (props["format"] === "text") return [];
  const source = createProjectCompletionSource(index, props["format"]);
  if (props["format"] === "edn") return [createEdnExtensions(props["options"]), autocompletion({override: [source], icons: false})];
  const dialect = props["dialect"] === "snowflake" ? StandardSQL : props["dialect"] === "sqlite" ? SQLite : PostgreSQL;
  const keywords = keywordCompletionSource(dialect, true);
  return [sql({dialect}), closeBrackets(),
    autocompletion({icons: false, override: [context => {
      const completion = source(context);
      if (completion?.template) return completion;
      const keyword = keywords(context);
      if (!completion) return keyword;
      return {...completion, options: [...completion.options, ...(keyword?.options || [])]};
    }]})];
}

export function QueryEditor(props) {
  const host = React.useRef(null);
  const view = React.useRef(null);
  const callbacks = React.useRef(props);
  callbacks.current = props;
  const language = React.useRef(new Compartment());
  const readOnly = React.useRef(new Compartment());
  const attributes = React.useRef(new Compartment());
  const intelligence = React.useRef(new Compartment());
  const representations = React.useRef(new Map());
  const activeMode = React.useRef(props["format"]);
  const schema = JSON.stringify(props["schema"] || {});
  const metadata = JSON.stringify(props["intelligence"] || {});
  const options = JSON.stringify(props["options"] || {});
  const index = React.useRef(null);
  const configuration = React.useRef("");
  const currentConfiguration = JSON.stringify([props["format"], props["readOnly"], props["label"], props["dialect"], schema, metadata, options]);
  if (configuration.current !== currentConfiguration) index.current = buildEditorIndex(props["intelligence"], props["schema"]);
  function readSelection() {
    const selection = props["selection"] || {"from": 0, "to": 0, "head": 0};
    return {"anchor": Math.min(selection["head"] === selection["from"] ? selection["to"] : selection["from"], props["value"].length),
      "head": Math.min(selection["head"], props["value"].length)};
  }
  function readOnlyExtensions() {
    return [EditorState.readOnly.of(Boolean(props["readOnly"])), EditorView.editable.of(!props["readOnly"])];
  }
  function highlightStatement(editor) {
    if (callbacks.current["file"] || callbacks.current["format"] !== "sql") return Decoration.none;
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
  function createIntelligenceExtensions() {
    return [hoverTooltip((editor, position, side) => {
      const entry = readEditorReference(editor.state.doc.toString(), position, callbacks.current["format"], index.current);
      if (!entry || position === entry.from && side < 0 || position === entry.to && side > 0) return null;
      return {pos: entry.from, end: entry.to, above: true, create: () => ({dom: renderEditorDocumentation(entry, path => callbacks.current["onNavigate"]?.(path))})};
    }, {hoverTime: 350}),
    ViewPlugin.define(editor => {
      function decorate() {
        if (callbacks.current["format"] !== "sql") return Decoration.none;
        return Decoration.set(readTemplateHighlights(editor.state.doc.toString()).filter(part => part.to > part.from)
          .map(part => Decoration.mark({class: part.className}).range(part.from, part.to)), true);
      }
      return {decorations: decorate(), update(update) { if (update.docChanged) this.decorations = decorate(); }};
    }, {decorations: plugin => plugin.decorations}),
    EditorView.domEventHandlers({
      mousemove(event, editor) {
        const position = editor.posAtCoords({x: event.clientX, y: event.clientY});
        const entry = (event.ctrlKey || event.metaKey) && position !== null ? readEditorReference(editor.state.doc.toString(), position, callbacks.current["format"], index.current) : null;
        editor.dom.classList.toggle("editor-definition-link", Boolean(entry?.path));
      },
      mouseleave(_event, editor) { editor.dom.classList.remove("editor-definition-link"); },
      mousedown(event, editor) {
        if (event.button !== 0 || !(event.ctrlKey || event.metaKey)) return false;
        const position = editor.posAtCoords({x: event.clientX, y: event.clientY});
        if (position === null) return false;
        const entry = readEditorReference(editor.state.doc.toString(), position, callbacks.current["format"], index.current);
        if (!entry?.path) return false;
        event.preventDefault();
        callbacks.current["onNavigate"]?.(entry.path);
        return true;
      }
    })];
  }
  function createEditorState() {
    return EditorState.create({doc: props["value"], selection: readSelection(), extensions: [
        EditorView.lineWrapping, lineNumbers(), highlightActiveLine(), highlightActiveLineGutter(), drawSelection(),
        history(), bracketMatching(), indentOnInput(), syntaxHighlighting(queryHighlightStyle), documentation,
        ViewPlugin.define(function decorateStatement(editor) {
          return {decorations: highlightStatement(editor), update: function updateHighlight(update) {
            this.decorations = highlightStatement(update["view"]);
          }};
        }, {decorations: function getDecorations(plugin) { return plugin.decorations; }}),
        language.current.of(createLanguage(props, index.current)),
        intelligence.current.of(createIntelligenceExtensions()),
        readOnly.current.of(readOnlyExtensions()),
        attributes.current.of(EditorView.contentAttributes.of({"aria-label": props["label"] || "Query editor", "spellcheck": "false"})),
        keymap.of([{key: "Escape", run: editor => {
          if (!editor.state.field(documentation)) return false;
          editor.dispatch({effects: documentationEffect.of(null)});
          return true;
        }}, ...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, indentWithTab]),
        EditorView.updateListener.of(function updateEditor(update) {
          if (update["docChanged"]) callbacks.current["onChange"](update["state"]["doc"].toString());
          if (update["selectionSet"] || update["docChanged"]) {
            const selection = update["state"]["selection"]["main"];
            callbacks.current["onSelection"]({"from": selection["from"], "to": selection["to"], "head": selection["head"]});
          }
        })
      ]});
  }
  React.useLayoutEffect(function mountEditor() {
    const editor = new EditorView({parent: host.current, state: createEditorState()});
    view.current = editor;
    configuration.current = currentConfiguration;
    editors.set(editor.dom, {view: editor, callbacks, index});
    return function destroyEditor() { editors.delete(editor.dom); editor["destroy"](); view.current = null; };
  }, []);
  React.useLayoutEffect(function updateRepresentation() {
    const editor = view.current;
    if (!editor) return;
    const modeChanged = activeMode.current !== props["format"];
    const effects = modeChanged || configuration.current !== currentConfiguration ? [language.current["reconfigure"](createLanguage(props, index.current)),
      intelligence.current["reconfigure"](createIntelligenceExtensions()),
      readOnly.current["reconfigure"](readOnlyExtensions()),
      attributes.current["reconfigure"](EditorView.contentAttributes.of({"aria-label": props["label"] || "Query editor", "spellcheck": "false"}))] : [];
    if (modeChanged) {
      // Each representation owns its undo history; viewing EDN never inserts EDN into SQL history.
      representations.current.set(activeMode.current, {"state": editor["state"], "scrollTop": editor["scrollDOM"]["scrollTop"]});
      const cached = representations.current.get(props["format"]);
      let state = cached?.["state"] || createEditorState();
      if (state["doc"].toString() !== props["value"]) {
        state = state["update"]({"changes": {"from": 0, "to": state["doc"].length, "insert": props["value"]}, "selection": readSelection(), "annotations": isolateHistory.of("full")})["state"];
      }
      editor["setState"](state["update"]({"effects": effects})["state"]);
      editor["scrollDOM"]["scrollTop"] = cached?.["scrollTop"] || 0;
      activeMode.current = props["format"];
    } else {
      const changes = editor["state"]["doc"].toString() !== props["value"]
        ? {"changes": {"from": 0, "to": editor["state"]["doc"].length, "insert": props["value"]}, "selection": readSelection()} : {};
      if (changes.changes || effects.length) editor["dispatch"]({...changes, "effects": effects});
    }
    configuration.current = currentConfiguration;
  }, [props["value"], currentConfiguration]);
  return React.createElement("div", {ref: host, className: "query-code-editor"});
}
