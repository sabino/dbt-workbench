import {test, expect} from "bun:test";
import {EditorState, EditorSelection, Transaction, Compartment} from "@codemirror/state";
import {ViewPlugin} from "@codemirror/view";
import {history, undo, redo} from "@codemirror/commands";
import {syntaxTree} from "@codemirror/language";
import {createEdnExtensions, inferParens, runEdnCommand} from "./edn-structure.js";

function createView(doc, anchor, options = {}, head = anchor) {
  const view = {
    state: EditorState.create({doc, selection: EditorSelection.single(anchor, head), extensions: [history(), createEdnExtensions(options), EditorState.readOnly.of(Boolean(options.readOnly))]}),
    dispatch(...specs) { this.state = this.state.update(...specs).state; }
  };
  // CodeMirror commands accept dispatch as a detached callback.
  view.dispatch = view.dispatch.bind(view);
  return view;
}

function text(view) { return view.state.doc.toString(); }

function type(view, insert) {
  const range = view.state.selection.main;
  view.dispatch({changes: {from: range.from, to: range.to, insert}, selection: {anchor: range.from + insert.length}, annotations: Transaction.userEvent.of("input.type")});
}

test("loading EDN modes preserves the exact source and cursor", () => {
  const doc = '{:select   [:amount]\n :from [:orders]} ; untouched spacing\n';
  for (const ednMode of ["plain", "paredit", "parinfer"]) {
    const options = Object.freeze({ednMode, rainbowEdn: false});
    const view = createView(doc, 12, options);
    expect(text(view)).toBe(doc);
    expect(view.state.selection.main.head).toBe(12);
    expect(syntaxTree(view.state).topNode.name).toBe("Program");
    expect(options).toEqual({ednMode, rainbowEdn: false});
  }
});

test("Parinfer repairs typed delimiters in one undo step and keeps cursor inside", () => {
  const view = createView("", 0, {ednMode: "parinfer"});
  type(view, "[");
  expect(text(view)).toBe("[]");
  expect(view.state.selection.main.head).toBe(1);
  expect(undo(view)).toBe(true);
  expect(text(view)).toBe("");
  expect(view.state.selection.main.head).toBe(0);
  expect(redo(view)).toBe(true);
  expect(text(view)).toBe("[]");
  expect(view.state.selection.main.head).toBeGreaterThanOrEqual(0);
  expect(view.state.selection.main.head).toBeLessThanOrEqual(view.state.doc.length);
});

test("Parinfer preserves non-typing transactions, pasted text and selected deletion", () => {
  for (const event of ["input.paste", "input.complete", "input.convert", "input.edn", "delete.selection"]) {
    const view = createView("[one]", 2, {ednMode: "parinfer"});
    view.dispatch({changes: {from: 0, to: view.state.doc.length, insert: "[one"}, selection: {anchor: 4}, annotations: Transaction.userEvent.of(event)});
    expect(text(view)).toBe("[one");
    expect(view.state.selection.main.head).toBe(4);
  }
  const view = createView("[one]", 2, {ednMode: "parinfer"});
  view.dispatch({selection: {anchor: 4}});
  expect(text(view)).toBe("[one]");
});

test("Parinfer skips readonly and multiple cursor transactions", () => {
  const view = createView("", 0, {ednMode: "parinfer", readOnly: true});
  const transaction = view.state.update({changes: {from: 0, insert: "["}, annotations: Transaction.userEvent.of("input.type")});
  expect(transaction.newDoc.toString()).toBe("[");
  expect(inferParens(transaction)).toBe(transaction);
  const state = EditorState.create({doc: "a b", extensions: [EditorState.allowMultipleSelections.of(true), createEdnExtensions({ednMode: "parinfer"})]});
  const multiple = state.update({changes: {from: 0, insert: "["}, selection: EditorSelection.create([EditorSelection.cursor(1), EditorSelection.cursor(3)]), annotations: Transaction.userEvent.of("input.type")});
  expect(multiple.newDoc.toString()).toBe("[a b");
});

test("collection navigation changes selection without changing text", () => {
  const view = createView("{:select [:amount :order_id]}", 15, {ednMode: "paredit"});
  expect(runEdnCommand(view, "edn-up")).toBe(true);
  expect(view.state.selection.main.head).toBe(9);
  view.dispatch({selection: {anchor: 9}});
  expect(runEdnCommand(view, "edn-down")).toBe(true);
  expect(view.state.selection.main.head).toBe(10);
  view.dispatch({selection: {anchor: 2}});
  expect(runEdnCommand(view, "edn-down")).toBe(true);
  expect(view.state.selection.main.head).toBe(10);
  view.dispatch({selection: {anchor: 15}});
  expect(runEdnCommand(view, "edn-down")).toBe(false);
  expect(text(view)).toBe("{:select [:amount :order_id]}");
  expect(runEdnCommand(view, "unknown-command")).toBe(false);
});

test("forward and backward navigation move by whole EDN forms", () => {
  const view = createView("[alpha {:beta 2} gamma]", 1, {ednMode: "paredit"});
  expect(runEdnCommand(view, "edn-forward")).toBe(true);
  expect(view.state.selection.main.head).toBe(6);
  expect(runEdnCommand(view, "edn-forward")).toBe(true);
  expect(view.state.selection.main.head).toBe(16);
  expect(runEdnCommand(view, "edn-backward")).toBe(true);
  expect(view.state.selection.main.head).toBe(7);
  expect(text(view)).toBe("[alpha {:beta 2} gamma]");
});

test("selection expansion selects a semantic form and contraction restores cursor", () => {
  const view = createView("[:amount {:limit 10}]", 4, {ednMode: "paredit"});
  expect(runEdnCommand(view, "edn-select-expand")).toBe(true);
  expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe(":amount");
  expect(runEdnCommand(view, "edn-select-contract")).toBe(true);
  expect(view.state.selection.main.empty).toBe(true);
  expect(view.state.selection.main.head).toBe(4);
});

test("forward slurp and barf move sibling forms without changing their contents", () => {
  const view = createView("[:amount] :order_id", 4, {ednMode: "paredit"});
  expect(runEdnCommand(view, "edn-slurp-forward")).toBe(true);
  expect(text(view).replace(/\s+/g, " ").trim()).toBe("[:amount :order_id]");
  expect(view.state.selection.main.head).toBe(4);
  expect(runEdnCommand(view, "edn-barf-forward")).toBe(true);
  expect(text(view).replace(/\s+/g, " ").trim()).toBe("[:amount] :order_id");
  expect(view.state.selection.main.head).toBe(4);
});

test("backward slurp and barf retain form order and cursor token", () => {
  const view = createView(":order_id [:amount]", 14, {ednMode: "paredit"});
  expect(runEdnCommand(view, "edn-slurp-backward")).toBe(true);
  expect(text(view).replace(/\s+/g, " ").trim()).toBe("[:order_id :amount]");
  expect(view.state.selection.main.head - text(view).indexOf(":amount")).toBe(3);
  expect(runEdnCommand(view, "edn-barf-backward")).toBe(true);
  expect(text(view).replace(/\s+/g, " ").trim()).toBe(":order_id [:amount]");
});

test("each structural action has its own undo step", () => {
  const view = createView("[:alpha] :beta", 4, {ednMode: "paredit"});
  const forms = () => text(view).replaceAll(/\s+/g, " ").trim();
  runEdnCommand(view, "edn-slurp-forward");
  expect(forms()).toBe("[:alpha :beta]");
  runEdnCommand(view, "edn-barf-forward");
  expect(forms()).toBe("[:alpha] :beta");
  expect(undo(view)).toBe(true);
  expect(forms()).toBe("[:alpha :beta]");
  expect(undo(view)).toBe(true);
  expect(forms()).toBe("[:alpha] :beta");
});

test("wrapping a partial token includes the whole form and preserves meaningful selection", () => {
  const view = createView(":amount :order_id", 2, {ednMode: "paredit"}, 5);
  expect(runEdnCommand(view, "edn-wrap-vector")).toBe(true);
  expect(text(view)).toBe("[:amount] :order_id");
  expect(view.state.sliceDoc(view.state.selection.main.from, view.state.selection.main.to)).toBe(":amount");
  expect(undo(view)).toBe(true);
  expect(text(view)).toBe(":amount :order_id");
});

test("empty wrapping and splicing preserve source values", () => {
  const view = createView("", 0, {ednMode: "paredit"});
  expect(runEdnCommand(view, "edn-wrap-map")).toBe(true);
  expect(text(view)).toBe("{}");
  expect(view.state.selection.main.head).toBe(1);
  const nested = createView("[[:amount :order_id]]", 5, {ednMode: "paredit"});
  expect(runEdnCommand(nested, "edn-splice")).toBe(true);
  expect(text(nested)).toBe("[:amount :order_id]");
});

test("readonly buffers reject structural mutations", () => {
  for (const command of ["edn-wrap-vector", "edn-wrap-map", "edn-wrap-list", "edn-slurp-forward", "edn-barf-forward", "edn-splice"]) {
    const view = createView("[:amount] :order_id", 4, {ednMode: "paredit", readOnly: true});
    runEdnCommand(view, command);
    expect(text(view)).toBe("[:amount] :order_id");
  }
});


test("EDN preference reconfiguration preserves text, selection and undo history", () => {
  const compartment = new Compartment();
  const view = createView("[:amount]", 4);
  view.state = EditorState.create({doc: "[:amount]", selection: {anchor: 4}, extensions: [history(), compartment.of(createEdnExtensions({ednMode: "paredit"}))]});
  type(view, "x");
  const before = text(view);
  const cursor = view.state.selection.main.head;
  view.dispatch({effects: compartment.reconfigure(createEdnExtensions({ednMode: "parinfer", rainbowEdn: true}))});
  expect(text(view)).toBe(before);
  expect(view.state.selection.main.head).toBe(cursor);
  expect(undo(view)).toBe(true);
  expect(text(view)).toBe("[:amount]");
});

test("rainbow decorations match bracket pairs and ignore strings, comments and discard", () => {
  const doc = '{:select ["[ignored]" ; {comment}\n :amount] #_[:discard] :from [:orders]}';
  const extensions = createEdnExtensions({ednMode: "off", rainbowEdn: true});
  const view = createView(doc, 0);
  view.state = EditorState.create({doc, extensions});
  // Instantiate the decoration plugin against a state-only view; no browser or DOM is needed.
  const plugin = extensions.find(extension => extension instanceof ViewPlugin);
  const value = plugin.create(view);
  const marks = [];
  value.decorations.between(0, doc.length, (from, to, decoration) => marks.push({text: doc.slice(from, to), from, className: decoration.spec.class}));
  expect(marks.map(mark => mark.text)).toEqual(["{", "[", "]", "[", "]", "}"]);
  expect(marks.map(mark => mark.className)).toEqual([
    "edn-bracket edn-bracket-0", "edn-bracket edn-bracket-1", "edn-bracket edn-bracket-1",
    "edn-bracket edn-bracket-1", "edn-bracket edn-bracket-1", "edn-bracket edn-bracket-0"
  ]);
  expect(marks.every(mark => mark.from < doc.indexOf('#_') || mark.from > doc.indexOf(':from'))).toBe(true);
});
