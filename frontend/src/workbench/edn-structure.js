import {default_extensions, paredit_keymap} from "@nextjournal/clojure-mode";
import parinfer from "parinfer";
import {EditorState, Transaction, Prec} from "@codemirror/state";
import {keymap, Decoration, ViewPlugin} from "@codemirror/view";
import {syntaxTree} from "@codemirror/language";
import {isolateHistory} from "@codemirror/commands";

const collections = new Set(["Map", "Vector", "List", "Set"]);

function readCollection(state, position) {
  let node = syntaxTree(state).resolveInner(position, -1);
  while (node && !collections.has(node.name)) node = node.parent;
  return node;
}

function moveCollection(view, direction) {
  const position = view.state.selection.main.head;
  let node = direction === "up" ? readCollection(view.state, position) : null;
  if (direction === "down") syntaxTree(view.state).iterate({
    from: position,
    enter(candidate) {
      if (!node && collections.has(candidate.name) && candidate.from >= position) node = candidate.node;
    }
  });
  if (!node) return false;
  const head = direction === "up" ? node.from : node.firstChild?.to ?? node.from + 1;
  view.dispatch({selection: {anchor: head}, scrollIntoView: true});
  return true;
}

function wrapSelection(view, open, close) {
  const range = view.state.selection.main;
  if (range.empty) {
    view.dispatch({changes: {from: range.from, insert: open + close}, selection: {anchor: range.from + 1}, userEvent: "input.edn"});
    return true;
  }
  // Whole forms are wrapped together. A partial token remains selected inside its containing form.
  const tree = syntaxTree(view.state);
  let first = tree.resolveInner(range.from, 1);
  let last = tree.resolveInner(range.to, -1);
  if (first.name === "StringContent") first = first.parent;
  if (last.name === "StringContent") last = last.parent;
  while (first.parent && first.parent.name !== "Program" && first.parent.to <= range.to) first = first.parent;
  while (last.parent && last.parent.name !== "Program" && last.parent.from >= range.from) last = last.parent;
  const from = Math.min(first.from, range.from);
  const to = Math.max(last.to, range.to);
  view.dispatch({changes: [{from, insert: open}, {from: to, insert: close}],
    selection: {anchor: from + 1, head: to + 1}, userEvent: "input.edn"});
  return true;
}

export function runEdnCommand(view, name) {
  const navigation = ["edn-forward", "edn-backward", "edn-up", "edn-down", "edn-select-expand", "edn-select-contract"];
  if (view.state.readOnly && !navigation.includes(name)) return false;
  if (name === "edn-up" || name === "edn-down") return moveCollection(view, name.slice(4));
  if (name === "edn-wrap-vector") return runIsolatedEdnEdit(view, () => wrapSelection(view, "[", "]"));
  if (name === "edn-wrap-map") return runIsolatedEdnEdit(view, () => wrapSelection(view, "{", "}"));
  if (name === "edn-wrap-list") return runIsolatedEdnEdit(view, () => wrapSelection(view, "(", ")"));
  // The published keymap is Nextjournal's public command interface. App shortcuts remain assignable independently.
  let key;
  switch (name) {
    case "edn-forward": key = "Alt-ArrowRight"; break;
    case "edn-backward": key = "Alt-ArrowLeft"; break;
    case "edn-select-expand": key = "Alt-ArrowUp"; break;
    case "edn-select-contract": key = "Alt-ArrowDown"; break;
    case "edn-slurp-forward": key = "Ctrl-ArrowRight"; break;
    case "edn-barf-forward": key = "Ctrl-ArrowLeft"; break;
    case "edn-slurp-backward": key = "Ctrl-Alt-ArrowLeft"; break;
    case "edn-barf-backward": key = "Ctrl-Alt-ArrowRight"; break;
    case "edn-splice": key = "Alt-s"; break;
    default: return false;
  }
  const command = paredit_keymap.find(binding => binding.key === key)?.run;
  if (!command) return false;
  return navigation.includes(name) ? command(view) : runIsolatedEdnEdit(view, () => command(view));
}

function runIsolatedEdnEdit(view, edit) {
  // Explicit structural actions must undo individually, even when they happen within the typing history window.
  view.dispatch({annotations: isolateHistory.of("before")});
  const changed = edit();
  view.dispatch({annotations: isolateHistory.of("after")});
  return changed;
}

function diffText(before, after) {
  let from = 0;
  while (from < before.length && from < after.length && before[from] === after[from]) from += 1;
  let oldEnd = before.length;
  let newEnd = after.length;
  while (oldEnd > from && newEnd > from && before[oldEnd - 1] === after[newEnd - 1]) { oldEnd -= 1; newEnd -= 1; }
  return {from, to: oldEnd, insert: after.slice(from, newEnd)};
}

export function inferParens(transaction) {
  // Reconfiguration, conversion, history, completion, paste, and selection must retain their exact text.
  if (!transaction.docChanged || transaction.startState.readOnly || transaction.newSelection.ranges.length > 1) return transaction;
  const event = transaction.annotation(Transaction.userEvent);
  if (!["input", "input.type", "input.indent"].includes(event) && !transaction.isUserEvent("delete")) return transaction;
  if (transaction.isUserEvent("delete.selection")) return transaction;
  const previous = transaction.startState.doc;
  const updated = transaction.newDoc;
  const previousCursor = previous.lineAt(transaction.startState.selection.main.head);
  const cursor = updated.lineAt(transaction.newSelection.main.head);
  const changes = [];
  transaction.changes.iterChanges((from, to, _newFrom, _newTo, inserted) => {
    const line = previous.lineAt(from);
    changes.push({lineNo: line.number - 1, x: from - line.from, oldText: previous.sliceString(from, to), newText: inserted.toString()});
  });
  const result = parinfer.smartMode(updated.toString(), {
    cursorLine: cursor.number - 1, cursorX: transaction.newSelection.main.head - cursor.from,
    prevCursorLine: previousCursor.number - 1, prevCursorX: transaction.startState.selection.main.head - previousCursor.from,
    changes, partialResult: false
  });
  if (!result.success || result.text === updated.toString()) return transaction;
  const correction = diffText(updated.toString(), result.text);
  const lines = result.text.split("\n");
  const head = lines.slice(0, result.cursorLine).reduce((length, line) => length + line.length + 1, 0) + result.cursorX;
  // Sequential specs put inference and the keystroke in one undoable transaction.
  return [transaction, {changes: correction, selection: {anchor: Math.min(head, result.text.length)}, sequential: true}];
}

function decorateBrackets(view) {
  const marks = [];
  let depth = 0;
  syntaxTree(view.state).iterate({
    enter(node) {
      if (["String", "RegExp", "LineComment", "Discard", "Character"].includes(node.name)) return false;
      if (["(", "[", "{"].includes(node.name)) {
        marks.push(Decoration.mark({class: `edn-bracket edn-bracket-${depth % 6}`}).range(node.from, node.to));
        depth += 1;
      } else if ([")", "]", "}"].includes(node.name)) {
        depth = Math.max(0, depth - 1);
        marks.push(Decoration.mark({class: `edn-bracket edn-bracket-${depth % 6}`}).range(node.from, node.to));
      }
    }
  });
  return Decoration.set(marks, true);
}

export function createEdnExtensions(options = {}) {
  // Omit the package's format-on-change extension. Format remains an explicit app command.
  const language = default_extensions[0];
  const selectionHistory = default_extensions[3];
  const extensions = [language, selectionHistory];
  if (options.ednMode === "paredit") {
    extensions.push(default_extensions[1], default_extensions[2], Prec.high(keymap.of(paredit_keymap.filter(binding => binding.key === "Enter"))));
  }
  if (options.ednMode === "parinfer") extensions.push(EditorState.transactionFilter.of(inferParens));
  if (options.rainbowEdn) extensions.push(ViewPlugin.define(view => ({
    decorations: decorateBrackets(view),
    update(update) { if (update.docChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) this.decorations = decorateBrackets(update.view); }
  }), {decorations: plugin => plugin.decorations}));
  return extensions;
}
