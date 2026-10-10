import {expect, test} from "bun:test";
import {COMMAND_EVENT_MARKER, SHORTCUT_STORAGE_KEY, normalizeShortcut, matchShortcut, validateShortcutAssignment,
  resolveShortcutKeymap, getCommandBindings, readShortcutOverrides, resolveCommandForEvent, filterCommandResults, filterFileResults, selectEnabledResult, captureShortcut, resolvePaletteKeyAction} from "./command-center.js";

function eventFor(key, modifiers = {}) { return {"key": key, "code": key.length === 1 ? `Key${key.toUpperCase()}` : key, ...modifiers}; }

test("Mod chords match either platform modifier with exact other modifiers", function verifyModifiers() {
  expect(matchShortcut(eventFor("p", {"metaKey": true}), "Mod+P")).toBe(true);
  expect(matchShortcut(eventFor("p", {"ctrlKey": true}), "Mod+P")).toBe(true);
  expect(matchShortcut(eventFor("p", {"metaKey": true, "shiftKey": true}), "Mod+P")).toBe(false);
  expect(matchShortcut(eventFor("p", {"metaKey": true, "ctrlKey": true}), "Mod+P")).toBe(false);
  expect(matchShortcut(eventFor("Enter", {"shiftKey": true}), "Shift+Enter")).toBe(true);
  expect(matchShortcut(eventFor("F5"), "F5")).toBe(true);
  expect(normalizeShortcut("shift+control+s")).toBe("Ctrl+Shift+S");
  expect(normalizeShortcut("Mod+Mod+S")).toBeNull();
});

test("physical letter keys handle Alt keyboard layouts without handling composition", function verifyLayoutAndComposition() {
  expect(matchShortcut({"key": "Ï", "code": "KeyF", "altKey": true, "shiftKey": true}, "Alt+Shift+F")).toBe(true);
  expect(matchShortcut(eventFor("p", {"metaKey": true, "isComposing": true}), "Mod+P")).toBe(false);
  expect(matchShortcut(eventFor("p", {"ctrlKey": true, "getModifierState": name => name === "AltGraph"}), "Mod+P")).toBe(false);
});

test("assignment rejects overlapping shortcuts, browser reservations and typing keys", function verifyAssignments() {
  expect(validateShortcutAssignment("new-file", "Mod+S")["conflict"]).toBe("save-file");
  expect(validateShortcutAssignment("new-file", "Ctrl+S")["valid"]).toBe(false);
  expect(validateShortcutAssignment("new-file", "Mod+L")["reason"]).toContain("reserved");
  expect(validateShortcutAssignment("new-file", "Escape")["valid"]).toBe(false);
  expect(validateShortcutAssignment("new-file", "Shift+A")["valid"]).toBe(false);
  expect(validateShortcutAssignment("new-file", "Mod+Z")["valid"]).toBe(false);
  expect(validateShortcutAssignment("new-file", "Mod+C")["valid"]).toBe(false);
  expect(validateShortcutAssignment("new-file", "Mod+Alt+N")["valid"]).toBe(true);
  expect(validateShortcutAssignment("close-tab", "Mod+W")["valid"]).toBe(true);
  expect(validateShortcutAssignment("unknown", "Mod+Alt+N")["valid"]).toBe(false);
});

test("replacement and clear overrides preserve unrelated defaults and restore aliases", function verifyKeymaps() {
  const keymap = resolveShortcutKeymap({"save-file": ["Mod+Alt+S"], "close-tab": []});
  expect(keymap["save-file"]).toEqual(["Mod+Alt+S"]);
  expect(keymap["close-tab"]).toEqual([]);
  expect(keymap["run-query"]).toEqual(["Shift+Enter", "F5", "Mod+Enter"]);
  expect(resolveShortcutKeymap({})["save-file"]).toEqual(["Mod+S"]);
  expect(getCommandBindings({}).find(binding => binding["key"] === "Mod+K")["id"]).toBe("search-mixed");
});

test("browser persistence rejects corrupt values and unsafe stored assignments", function verifyPersistence() {
  const storage = {"getItem": key => key === SHORTCUT_STORAGE_KEY ? JSON.stringify({"save-file": ["Mod+Alt+S"], "close-tab": [], "new-file": ["Mod+L"], "unknown": ["Mod+J"]}) : null};
  expect(readShortcutOverrides(storage)).toEqual({"save-file": ["Mod+Alt+S"], "close-tab": []});
  expect(readShortcutOverrides({"getItem": () => "invalid json"})).toEqual({});
  expect(readShortcutOverrides({"getItem": () => "[]"})).toEqual({});
  expect(readShortcutOverrides({"getItem": () => { throw new Error("blocked"); }})).toEqual({});
});

test("disabled close-tab and stale events cannot dispatch an app command", function verifyDisabledCommands() {
  const close = eventFor("w", {"metaKey": true});
  expect(resolveCommandForEvent(close, [{"id": "close-tab", "enabled": false, "run": () => {}}])).toBeNull();
  expect(resolveCommandForEvent(close, [])).toBeNull();
  expect(resolveCommandForEvent(close, [{"id": "close-tab", "enabled": true, "run": () => {}}])).toBe("close-tab");
  expect(close["defaultPrevented"]).toBeUndefined();
  expect(resolveCommandForEvent({...close, [COMMAND_EVENT_MARKER]: true}, [{"id": "close-tab", "run": () => {}}])).toBeNull();
  expect(resolveCommandForEvent({...close, "repeat": true}, [{"id": "close-tab", "run": () => {}}])).toBeNull();
});

test("typing and dialogs suppress actions but preserve palette navigation", function verifyRoutingContexts() {
  const commands = [{"id": "save-file", "run": () => {}}];
  expect(resolveCommandForEvent(eventFor("s", {"ctrlKey": true}), commands, {}, {"typingOutsideEditor": true})).toBeNull();
  expect(resolveCommandForEvent(eventFor("s", {"ctrlKey": true}), commands, {}, {"dialogOpen": true})).toBeNull();
  expect(resolveCommandForEvent(eventFor("p", {"ctrlKey": true}), commands, {}, {"typingOutsideEditor": true, "dialogOpen": true})).toBe("search-files");
  expect(resolveCommandForEvent(eventFor("p", {"ctrlKey": true}), commands, {}, {"capturing": true})).toBeNull();
});

test("fuzzy file matching uses basename and path; commands accept greater-than prefix", function verifySearch() {
  const files = [{"path": "models/staging/items.sql"}, {"path": "models/revenue.sql"}, {"path": "docs/readme.md"}];
  expect(filterFileResults(files, "msi").map(file => file["path"])).toEqual(["models/staging/items.sql"]);
  expect(filterFileResults(files, "revenue")[0]["path"]).toBe("models/revenue.sql");
  expect(filterFileResults(files, "missing")).toEqual([]);
  const commands = [{"id": "save-file", "label": "Save file", "description": "Write the active draft", "category": "Editor"}, {"id": "show-results", "label": "Show results", "category": "Navigation"}];
  expect(filterCommandResults(commands, "> write")[0]["id"]).toBe("save-file");
  expect(filterCommandResults(commands, "shres")[0]["id"]).toBe("show-results");
});


test("stored reassignment and clears resolve as a complete map in either order", function verifyPersistenceOrder() {
  function read(saved) { return readShortcutOverrides({"getItem": () => JSON.stringify(saved)}); }
  const reassigned = {"new-file": ["Mod+S", "Mod+Alt+N"], "save-file": []};
  expect(read(reassigned)).toEqual(reassigned);
  expect(read({"save-file": [], "new-file": ["Mod+S", "Mod+Alt+N"]})).toEqual(reassigned);
  const swapped = {"new-file": ["Mod+S"], "save-file": ["Mod+N"]};
  expect(read(swapped)).toEqual(swapped);
  expect(read({"save-file": ["Mod+N"], "new-file": ["Mod+S"]})).toEqual(swapped);
  expect(read({"new-file": ["Mod+Alt+J"], "save-file": ["Ctrl+Alt+J"]})).toEqual({});
  expect(read({"new-file": ["Mod+S"], "save-file": ["Mod+L"]})).toEqual({});
  expect(read({"new-file": ["Mod+S"], "save-file": ["Mod+W"], "close-tab": ["Mod+L"]})).toEqual({});
  expect(read({"run-query": ["F6", "Mod+Alt+Enter", "F6"]})).toEqual({"run-query": ["F6", "Mod+Alt+Enter"]});
});

test("every exposed workspace command accepts an unassigned shortcut", function verifyWorkspaceCommands() {
  const ids = ["preview-model", "build-project", "test-project", "parse-project", "show-overview", "show-documentation", "show-git", "show-connections", "show-routing", "show-activity", "show-changes", "show-runs", "switch-sql", "switch-edn", "toggle-theme", "reset-layout"];
  expect(ids.every(id => resolveShortcutKeymap()[id].length === 0)).toBe(true);
  expect(ids.every(id => validateShortcutAssignment(id, "Mod+Alt+J")["valid"])).toBe(true);
});

test("selection skips unavailable commands in both directions and retains files", function verifyEnabledSelection() {
  const unavailable = {"result_kind": "command", "enabled": false};
  const results = [unavailable, {"result_kind": "command"}, unavailable, {"result_kind": "file"}, unavailable];
  expect(selectEnabledResult(results, 0)).toBe(1);
  expect(selectEnabledResult(results, 2, 1)).toBe(3);
  expect(selectEnabledResult(results, 2, -1)).toBe(1);
  expect(selectEnabledResult(results, 4, 1)).toBe(3);
  expect(selectEnabledResult(results, 0, -1)).toBe(1);
  expect(selectEnabledResult([unavailable], 0)).toBe(-1);
  expect(selectEnabledResult([], 0)).toBe(-1);
});


test("palette navigation leaves dialog buttons and modified input keys native", function verifyPaletteKeyScope() {
  expect(resolvePaletteKeyAction(eventFor("Enter"), "commands", true)).toBe("choose");
  expect(resolvePaletteKeyAction(eventFor("ArrowDown"), "files", true)).toBe("next");
  expect(resolvePaletteKeyAction(eventFor("ArrowUp"), "mixed", true)).toBe("previous");
  expect(resolvePaletteKeyAction(eventFor("Enter"), "commands", false)).toBeNull();
  expect(resolvePaletteKeyAction(eventFor("ArrowDown"), "commands", false)).toBeNull();
  expect(resolvePaletteKeyAction(eventFor("Enter"), "settings", true)).toBeNull();
  expect(resolvePaletteKeyAction(eventFor("ArrowUp", {"shiftKey": true}), "commands", true)).toBeNull();
  expect(resolvePaletteKeyAction(eventFor("Enter", {"isComposing": true}), "commands", true)).toBeNull();
});

test("shortcut capture preserves Tab navigation and Escape dismissal", function verifyCaptureNavigation() {
  expect(captureShortcut(eventFor("Tab"))).toBeNull();
  expect(captureShortcut(eventFor("Tab", {"shiftKey": true}))).toBeNull();
  expect(captureShortcut(eventFor("Escape"))).toBeNull();
  expect(captureShortcut(eventFor("Meta"))).toBeNull();
  expect(captureShortcut(eventFor("s", {"metaKey": true, "altKey": true}))).toBe("Mod+Alt+S");
  expect(captureShortcut(eventFor("s", {"metaKey": true, "isComposing": true}))).toBeNull();
});
