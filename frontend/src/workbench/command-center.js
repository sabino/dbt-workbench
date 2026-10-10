import React from "react";
import {MagnifyingGlass} from "@phosphor-icons/react/dist/csr/MagnifyingGlass";
import {Command} from "@phosphor-icons/react/dist/csr/Command";
import {Keyboard} from "@phosphor-icons/react/dist/csr/Keyboard";
import {File} from "@phosphor-icons/react/dist/csr/File";

const h = React["createElement"];
export const COMMAND_EVENT_MARKER = "workbenchCommandHandled";
export const SHORTCUT_STORAGE_KEY = "workbench.command-shortcuts.v1";
const DEFAULT_BINDINGS = {
  "search-files": ["Mod+P"], "search-commands": ["Mod+Shift+P"], "search-mixed": ["Mod+K"], "keyboard-shortcuts": [],
  "new-file": ["Mod+N"], "save-file": ["Mod+S"], "close-tab": ["Mod+W"],
  "run-query": ["Shift+Enter", "F5", "Mod+Enter"], "run-all": ["Mod+Shift+Enter"],
  "format-document": ["Alt+Shift+F"], "toggle-files": ["Mod+Shift+E"], "toggle-sidebar": ["Mod+B"],
  "show-results": [], "show-lineage": [], "show-metadata": [],
  "preview-model": [], "build-project": [], "test-project": [], "parse-project": [],
  "show-overview": [], "show-documentation": [], "show-git": [], "show-connections": [], "show-routing": [],
  "show-activity": [], "show-changes": [], "show-runs": [], "switch-sql": [], "switch-edn": [],
  "toggle-theme": [], "reset-layout": [],
  "trigger-completion": ["Ctrl+Space"], "editor-documentation": ["Ctrl+Shift+Space"], "goto-definition": ["F12"],
  "edn-forward": ["Alt+ArrowRight"], "edn-backward": ["Alt+ArrowLeft"], "edn-up": ["Alt+ArrowUp"], "edn-down": ["Alt+ArrowDown"],
  "edn-select-expand": ["Alt+Shift+ArrowUp"], "edn-select-contract": ["Alt+Shift+ArrowDown"],
  "edn-slurp-forward": ["Ctrl+Alt+ArrowRight"], "edn-barf-forward": ["Ctrl+Alt+ArrowLeft"],
  "edn-slurp-backward": ["Ctrl+Alt+Shift+ArrowRight"], "edn-barf-backward": ["Ctrl+Alt+Shift+ArrowLeft"],
  "edn-wrap-vector": [], "edn-wrap-map": [], "edn-wrap-list": [], "edn-splice": []
};
const INTELLIGENCE_COMMANDS = new Set(["trigger-completion", "editor-documentation", "goto-definition", "edn-forward", "edn-backward", "edn-up", "edn-down", "edn-select-expand", "edn-select-contract", "edn-slurp-forward", "edn-barf-forward", "edn-slurp-backward", "edn-barf-backward", "edn-wrap-vector", "edn-wrap-map", "edn-wrap-list", "edn-splice"]);
const EDITOR_COMMANDS = new Set(["new-file", "save-file", "close-tab", "run-query", "run-all", "format-document", ...INTELLIGENCE_COMMANDS]);
const MODIFIERS = ["Mod", "Ctrl", "Meta", "Alt", "Shift"];
const RESERVED = new Set(["Mod+L", "Mod+R", "Mod+Shift+R", "Mod+T", "Mod+Shift+T", "Mod+Q", "Mod+H", "Mod+Tab", "Mod+Shift+Tab", "Mod+Alt+I", "Mod+Z", "Mod+Shift+Z", "Mod+Y", "Mod+C", "Mod+V", "Mod+X", "Mod+A", "Mod+F", "Mod+G", "Alt+F4", "F11", "F12"]);
const INTERNAL_COMMANDS = [
  {"id": "search-files", "label": "Go to file", "description": "Find a project file by name or path", "category": "Navigation"},
  {"id": "search-commands", "label": "Show all commands", "description": "Search available editor and workspace actions", "category": "Navigation"},
  {"id": "search-mixed", "label": "Search files and commands", "description": "Find a file or workspace action together", "category": "Navigation"},
  {"id": "keyboard-shortcuts", "label": "Keyboard shortcuts", "description": "Assign keys or restore defaults in this browser", "category": "Settings"}
];

export function normalizeShortcut(chord) {
  if (typeof chord !== "string") return null;
  const parts = chord.split("+").map(part => part.trim());
  if (!parts.length || parts.some(part => !part)) return null;
  const aliases = {"cmd": "Meta", "command": "Meta", "ctrl": "Ctrl", "control": "Ctrl", "meta": "Meta", "mod": "Mod", "alt": "Alt", "option": "Alt", "shift": "Shift"};
  const modifiers = parts.slice(0, -1).map(part => aliases[part.toLowerCase()]);
  if (modifiers.some(part => !part) || new Set(modifiers).size !== modifiers.length || modifiers.includes("Mod") && modifiers.some(part => ["Ctrl", "Meta"].includes(part))) return null;
  const rawKey = parts.at(-1);
  const keyAliases = {"esc": "Escape", "escape": "Escape", "return": "Enter", "enter": "Enter", "space": "Space", "tab": "Tab", "backspace": "Backspace", "delete": "Delete", "arrowup": "ArrowUp", "arrowdown": "ArrowDown", "arrowleft": "ArrowLeft", "arrowright": "ArrowRight", "home": "Home", "end": "End", "pageup": "PageUp", "pagedown": "PageDown"};
  const key = /^[a-z0-9]$/i.test(rawKey) ? rawKey.toUpperCase() : /^f(?:[1-9]|1\d|2[0-4])$/i.test(rawKey) ? rawKey.toUpperCase() : keyAliases[rawKey.toLowerCase()];
  if (!key) return null;
  return [...MODIFIERS.filter(modifier => modifiers.includes(modifier)), key].join("+");
}

function eventKey(event) {
  const code = event["code"] || "";
  if (/^(?:Key[A-Z]|Digit[0-9])$/.test(code)) return code.slice(code.startsWith("Key") ? 3 : 5);
  return event["key"] === " " ? "Space" : event["key"];
}

export function matchShortcut(event, chord) {
  const normalized = normalizeShortcut(chord);
  if (!normalized || event["isComposing"] || event["keyCode"] === 229 || event["getModifierState"]?.("AltGraph")) return false;
  const parts = normalized.split("+");
  const modifiers = parts.slice(0, -1);
  const mod = modifiers.includes("Mod");
  const control = Boolean(event["ctrlKey"]);
  const meta = Boolean(event["metaKey"]);
  if (mod ? !(control !== meta) : control !== modifiers.includes("Ctrl") || meta !== modifiers.includes("Meta")) return false;
  if (Boolean(event["altKey"]) !== modifiers.includes("Alt") || Boolean(event["shiftKey"]) !== modifiers.includes("Shift")) return false;
  return normalizeShortcut(String(eventKey(event))) === parts.at(-1);
}

function conflictChord(chord) {
  return chord.replace(/(?:Ctrl|Meta)/g, "Mod");
}

export function resolveShortcutKeymap(overrides = {}) {
  return Object.fromEntries(Object.entries(DEFAULT_BINDINGS).map(([id, defaults]) => {
    const values = Object.hasOwn(overrides || {}, id) ? overrides[id] : defaults;
    const chords = (Array.isArray(values) ? values : typeof values === "string" ? [values] : defaults).map(normalizeShortcut).filter(Boolean);
    return [id, Array.from(new Set(chords))];
  }));
}

export function validateShortcutAssignment(commandId, chord, overrides = {}) {
  const normalized = normalizeShortcut(chord);
  if (!Object.hasOwn(DEFAULT_BINDINGS, commandId)) return {"valid": false, "chord": normalized, "reason": "This command does not support shortcut assignment."};
  if (!normalized) return {"valid": false, "chord": null, "reason": "Choose a key with Ctrl, Command, Alt or Shift, or a function key."};
  const parts = normalized.split("+");
  const key = parts.at(-1);
  const reserved = RESERVED.has(conflictChord(normalized)) && !(commandId === "goto-definition" && normalized === "F12");
  if (["Escape", "Tab"].includes(key) || parts.length === 1 && !/^F\d+$/.test(key) || /^[A-Z0-9]$/.test(key) && !parts.some(part => ["Mod", "Ctrl", "Meta", "Alt"].includes(part)) || reserved) return {"valid": false, "chord": normalized, "reason": "This shortcut is reserved for browser navigation or normal typing."};
  const conflict = Object.entries(resolveShortcutKeymap(overrides)).find(([id, chords]) => id !== commandId && chords.some(value => conflictChord(value) === conflictChord(normalized)));
  return conflict ? {"valid": false, "chord": normalized, "reason": `Already assigned to ${conflict[0]}.`, "conflict": conflict[0]} : {"valid": true, "chord": normalized, "reason": null};
}

function validateSavedOverrides(saved) {
  const emptyBindings = Object.fromEntries(Object.keys(DEFAULT_BINDINGS).map(id => [id, []]));
  const candidates = Object.entries(saved).filter(([id, values]) => Object.hasOwn(DEFAULT_BINDINGS, id) && Array.isArray(values));
  let accepted = Object.fromEntries(candidates.flatMap(([id, values]) => {
    const chords = values.map(normalizeShortcut);
    return chords.every(chord => validateShortcutAssignment(id, chord, emptyBindings)["valid"]) ? [[id, Array.from(new Set(chords))]] : [];
  }));
  // Validate the complete map so a clear or reassignment can free another command's default.
  // Recheck after rejection because restoring a default can expose another conflict.
  for (let pass = 0; pass <= candidates.length; pass += 1) {
    const rejected = Object.entries(accepted).filter(([id, chords]) => chords.some(chord => !validateShortcutAssignment(id, chord, accepted)["valid"])).map(([id]) => id);
    if (!rejected.length) return accepted;
    accepted = Object.fromEntries(Object.entries(accepted).filter(([id]) => !rejected.includes(id)));
  }
  return accepted;
}

export function readShortcutOverrides(storage) {
  try {
    const source = storage || (typeof localStorage !== "undefined" ? localStorage : null);
    const saved = JSON.parse(source?.["getItem"](SHORTCUT_STORAGE_KEY) || "{}");
    if (!saved || typeof saved !== "object" || Array.isArray(saved)) return {};
    return validateSavedOverrides(saved);
  } catch { return {}; }
}

export function getCommandBindings(overrides) {
  return Object.entries(resolveShortcutKeymap(overrides === undefined ? readShortcutOverrides() : overrides)).flatMap(([id, chords]) => chords.map(chord => ({"id": id, "key": chord, "chord": chord})));
}

export function resolveCommandForEvent(event, commands, overrides = {}, context = {}) {
  if (event[COMMAND_EVENT_MARKER] || event["defaultPrevented"] || event["isComposing"] || event["repeat"] || context["capturing"]) return null;
  const found = getCommandBindings(overrides).find(binding => matchShortcut(event, binding["chord"]));
  if (!found) return null;
  const id = found["id"];
  const palette = ["search-files", "search-commands", "search-mixed"].includes(id);
  if (!palette && (context["dialogOpen"] || context["typingOutsideEditor"])) return null;
  if (INTELLIGENCE_COMMANDS.has(id) && !context["editorFocused"]) return null;
  if (palette || id === "keyboard-shortcuts") return id;
  const command = commands.find(item => item["id"] === id);
  return command && command["enabled"] !== false && typeof command["run"] === "function" ? id : null;
}

function fuzzyScore(text, query) {
  const candidate = text.toLowerCase();
  const needle = query.trim().toLowerCase();
  if (!needle) return 0;
  const exact = candidate.indexOf(needle);
  if (exact !== -1) return 1000 - exact - candidate.length / 100;
  let position = -1;
  let score = 0;
  for (const char of needle) {
    const next = candidate.indexOf(char, position + 1);
    if (next === -1) return null;
    score += next === position + 1 ? 12 : 2;
    if (next === 0 || /[\/_.\s-]/.test(candidate[next - 1])) score += 8;
    position = next;
  }
  return score - candidate.length / 100;
}

export function filterCommandResults(commands, query) {
  const needle = query.replace(/^\s*>\s*/, "");
  return commands.map(command => ({"item": command, "score": fuzzyScore(`${command["label"]} ${command["description"] || ""} ${command["category"] || ""}`, needle)}))
    .filter(result => result["score"] !== null).sort((left, right) => right["score"] - left["score"] || left["item"]["label"].localeCompare(right["item"]["label"]))
    .map(result => result["item"]);
}

export function filterFileResults(files, query) {
  return files.map(file => ({"item": file, "score": fuzzyScore(`${file["path"].split("/").at(-1)} ${file["path"]}`, query)}))
    .filter(result => result["score"] !== null).sort((left, right) => right["score"] - left["score"] || left["item"]["path"].localeCompare(right["item"]["path"]))
    .map(result => result["item"]);
}

export function selectEnabledResult(results, index, direction = 0) {
  const enabled = results.map((item, position) => item["result_kind"] === "command" && item["enabled"] === false ? -1 : position).filter(position => position >= 0);
  if (!enabled.length) return -1;
  if (direction < 0) return enabled.filter(position => position <= index).at(-1) ?? enabled[0];
  return enabled.find(position => position >= index) ?? enabled.at(-1);
}

export function captureShortcut(event) {
  if (event["isComposing"] || event["keyCode"] === 229 || ["Escape", "Tab"].includes(event["key"]) || event["getModifierState"]?.("AltGraph")) return null;
  const key = eventKey(event);
  if (!key || ["Control", "Meta", "Alt", "Shift", "Dead", "Unidentified"].includes(key)) return null;
  return normalizeShortcut([...(event["metaKey"] || event["ctrlKey"] ? ["Mod"] : []), ...(event["altKey"] ? ["Alt"] : []), ...(event["shiftKey"] ? ["Shift"] : []), key].join("+"));
}

export function resolvePaletteKeyAction(event, mode, searchInteraction) {
  if (!searchInteraction || mode === "settings" || event["isComposing"] || event["keyCode"] === 229 || event["defaultPrevented"] || event["altKey"] || event["ctrlKey"] || event["metaKey"] || event["shiftKey"]) return null;
  switch (event["key"]) {
    case "ArrowDown": return "next";
    case "ArrowUp": return "previous";
    case "Enter": return "choose";
    default: return null;
  }
}

function isTypingOutsideEditor(target) {
  return Boolean(target?.["closest"]?.("input, textarea, select, [contenteditable='true']")) && !target?.["closest"]?.(".cm-editor");
}

export function CommandCenter(props) {
  const dialog = React["useRef"](null);
  const trigger = React["useRef"](null);
  const input = React["useRef"](null);
  const returnFocus = React["useRef"](null);
  const shouldRestore = React["useRef"](true);
  const latest = React["useRef"](props);
  latest["current"] = props;
  const [mode, setMode] = React["useState"]("files");
  const [query, setQuery] = React["useState"]("");
  const [index, setIndex] = React["useState"](0);
  const [overrides, setOverrides] = React["useState"](() => readShortcutOverrides());
  const [assigning, setAssigning] = React["useState"]("");
  const [pending, setPending] = React["useState"]("");
  const [feedback, setFeedback] = React["useState"]("");
  const [error, setError] = React["useState"]("");
  const keymap = resolveShortcutKeymap(overrides);
  const keymapRef = React["useRef"](keymap);
  keymapRef["current"] = keymap;
  const commandMode = mode === "commands" || /^\s*>/.test(query);
  const supplied = props["commands"] || [];
  const commands = [...INTERNAL_COMMANDS, ...supplied.filter(command => !INTERNAL_COMMANDS.some(item => item["id"] === command["id"]))];
  const commandResults = filterCommandResults(commands, query).map(item => ({...item, "result_kind": "command"}));
  const fileResults = filterFileResults(props["files"] || [], query).map(item => ({...item, "result_kind": "file"}));
  const results = mode === "settings" ? [] : (commandMode ? commandResults : mode === "mixed" ? [...commandResults, ...fileResults] : fileResults).slice(0, 60);
  const chosen = selectEnabledResult(results, index);
  const actions = React["useRef"](null);

  function openPalette(nextMode) {
    if (!dialog["current"]["open"]) returnFocus["current"] = document["activeElement"];
    shouldRestore["current"] = true;
    setMode(nextMode); setQuery(""); setIndex(0); setAssigning(""); setPending(""); setFeedback("");
    if (!dialog["current"]["open"]) dialog["current"]["showModal"]();
    setTimeout(() => { const target = input["current"] || dialog["current"]?.["querySelector"]("button"); target?.["focus"](); }, 0);
  }

  async function executeCommand(id) {
    if (id === "search-files") { openPalette("files"); return; }
    if (id === "search-commands") { openPalette("commands"); return; }
    if (id === "search-mixed") { openPalette("mixed"); return; }
    if (id === "keyboard-shortcuts") { openPalette("settings"); return; }
    const command = (latest["current"]["commands"] || []).find(item => item["id"] === id);
    if (!command || command["enabled"] === false || typeof command["run"] !== "function") return;
    shouldRestore["current"] = false;
    dialog["current"]?.["close"](); setError("");
    try {
      await command["run"]();
      if (EDITOR_COMMANDS.has(id)) latest["current"]["onFocusEditor"]?.();
      else returnFocus["current"]?.["isConnected"] && returnFocus["current"]["focus"]();
    } catch (failure) { setError(failure["message"] || "Command failed."); latest["current"]["onFocusEditor"]?.(); }
  }

  async function chooseResult(item) {
    if (!item) return;
    if (item["result_kind"] === "command") { await executeCommand(item["id"]); return; }
    if (!(latest["current"]["files"] || []).some(file => file["path"] === item["path"])) return;
    shouldRestore["current"] = false; dialog["current"]["close"](); setError("");
    try { await latest["current"]["onOpenFile"]?.(item["path"]); latest["current"]["onFocusEditor"]?.(); }
    catch (failure) { setError(failure["message"] || "File could not be opened."); }
  }

  function persist(next) {
    try { localStorage["setItem"](SHORTCUT_STORAGE_KEY, JSON.stringify(next)); setOverrides(next); setFeedback("Shortcuts saved in this browser."); }
    catch { setFeedback("Browser storage is unavailable. Shortcuts were not changed."); }
  }

  function assignShortcut(event, id) {
    const chord = captureShortcut(event);
    if (!chord) return;
    event[COMMAND_EVENT_MARKER] = true; event["preventDefault"](); event["stopPropagation"]();
    const validation = validateShortcutAssignment(id, chord, overrides);
    setPending(chord); setFeedback(validation["valid"] ? "Press Save to assign this shortcut." : validation["conflict"] ? `Already assigned to ${commands.find(command => command["id"] === validation["conflict"])?.["label"] || validation["conflict"]}.` : validation["reason"]);
  }

  actions["current"] = {"open": openPalette, "execute": executeCommand, "capturing": mode === "settings" && Boolean(assigning)};
  React["useEffect"](function bindShortcuts() {
    function handleKey(event) {
      if (event[COMMAND_EVENT_MARKER] || event["defaultPrevented"] || event["isComposing"] || event["repeat"]) return;
      const id = resolveCommandForEvent(event, latest["current"]["commands"] || [], keymapRef["current"],
        {"dialogOpen": Boolean(dialog["current"]?.["open"] || event["target"]?.["closest"]?.("dialog[open]")), "typingOutsideEditor": isTypingOutsideEditor(event["target"]), "editorFocused": Boolean(event["target"]?.["closest"]?.(".cm-editor")), "capturing": dialog["current"]?.["open"] && actions["current"]["capturing"]});
      if (!id) return;
      if (!dialog["current"]?.["open"]) returnFocus["current"] = document["activeElement"];
      event[COMMAND_EVENT_MARKER] = true; event["preventDefault"](); event["stopPropagation"]();
      actions["current"]["execute"](id);
    }
    function handleStorage(event) { if (event["key"] === SHORTCUT_STORAGE_KEY || event["key"] === null) setOverrides(readShortcutOverrides()); }
    document["addEventListener"]("keydown", handleKey, true);
    window["addEventListener"]("storage", handleStorage);
    return function unbindShortcuts() { document["removeEventListener"]("keydown", handleKey, true); window["removeEventListener"]("storage", handleStorage); };
  }, []);
  React["useEffect"](function revealSelection() { dialog["current"]?.["querySelector"]?.(`[data-command-index='${chosen}']`)?.["scrollIntoView"]?.({"block": "nearest"}); }, [chosen, query]);

  function onDialogKey(event) {
    const action = resolvePaletteKeyAction(event, mode, Boolean(event["target"]?.["closest"]?.(".command-center-input, [role='listbox']")));
    if (!action) return;
    event["preventDefault"]();
    if (action === "choose") { chooseResult(results[chosen]); return; }
    const direction = action === "next" ? 1 : -1;
    setIndex(selectEnabledResult(results, chosen + direction, direction));
  }

  return h(React["Fragment"], null,
    h("button", {"ref": trigger, "className": "command-center-trigger", "onClick": () => openPalette("mixed"), "aria-label": "Search files and commands", "aria-haspopup": "dialog"}, h(MagnifyingGlass, {"size": 16, "aria-hidden": true}), h("span", null, "Search files and commands"), h("kbd", null, keymap["search-mixed"][0] || "Search")),
    error ? h("span", {"className": "command-center-error", "role": "alert"}, error) : null,
    h("dialog", {"ref": dialog, "className": "command-center-dialog", "aria-labelledby": "command-center-title", "onKeyDown": onDialogKey,
      "onClose": () => { if (shouldRestore["current"]) (returnFocus["current"]?.["isConnected"] ? returnFocus["current"] : trigger["current"])?.["focus"](); },
      "onClick": event => { if (event["target"] === dialog["current"]) { const box = dialog["current"]["getBoundingClientRect"](); if (event["clientX"] < box["left"] || event["clientX"] > box["right"] || event["clientY"] < box["top"] || event["clientY"] > box["bottom"]) dialog["current"]["close"](); } }},
      h("div", {"className": "command-center-heading"}, h(mode === "settings" ? Keyboard : commandMode ? Command : File, {"size": 18, "aria-hidden": true}), h("h2", {"id": "command-center-title"}, mode === "settings" ? "Keyboard shortcuts" : commandMode ? "Commands" : mode === "mixed" ? "Files and commands" : "Go to file"), h("button", {"className": "command-center-close", "aria-label": "Close command center", "onClick": () => dialog["current"]["close"]()}, "Esc")),
      mode === "settings" ? h("div", {"className": "shortcut-settings"}, h("p", {"className": "shortcut-help"}, "Assign a replacement shortcut. Mod means Command on macOS or Ctrl on other keyboards. Changes stay in this browser."),
        commands.filter(command => Object.hasOwn(DEFAULT_BINDINGS, command["id"])).map(command => h("div", {"className": "shortcut-row", "key": command["id"]},
          h("div", {"className": "shortcut-name"}, h("strong", null, command["label"]), h("small", null, command["category"] || "Command")),
          h("div", {"className": "shortcut-keys"}, keymap[command["id"]].length ? keymap[command["id"]].map(chord => h("kbd", {"key": chord}, chord)) : h("span", null, "Unassigned")),
          h("button", {"className": "shortcut-button", "onClick": () => { setAssigning(command["id"]); setPending(""); setFeedback("Focus the capture field and press the desired keys."); }}, "Assign"),
          h("button", {"className": "shortcut-button", "disabled": !keymap[command["id"]].length, "onClick": () => persist({...overrides, [command["id"]]: []})}, "Clear"),
          assigning === command["id"] ? h("div", {"className": "shortcut-capture"}, h("input", {"ref": input, "autoFocus": true, "readOnly": true, "value": pending, "placeholder": "Press a shortcut", "aria-label": `Capture shortcut for ${command["label"]}`, "onKeyDown": event => assignShortcut(event, command["id"])}),
            h("button", {"className": "shortcut-button", "disabled": !validateShortcutAssignment(command["id"], pending, overrides)["valid"], "onClick": () => { persist({...overrides, [command["id"]]: [pending]}); setAssigning(""); }}, "Save")) : null)),
        h("p", {"className": "shortcut-feedback", "role": "status"}, feedback),
        h("button", {"className": "shortcut-button shortcut-reset", "onClick": () => { persist({}); setAssigning(""); }}, "Restore all defaults")) : h(React["Fragment"], null,
        h("input", {"ref": input, "className": "command-center-input", "role": "combobox", "aria-label": "Search commands", "aria-controls": "command-center-results", "aria-expanded": true, "aria-autocomplete": "list", "aria-activedescendant": chosen >= 0 ? `command-center-result-${chosen}` : undefined, "autoComplete": "off", "spellCheck": false, "value": query, "placeholder": commandMode ? "Type a command…" : "Search files, or type > for commands…", "onChange": event => { setQuery(event["target"]["value"]); setIndex(0); }}),
        h("div", {"className": "command-center-results", "id": "command-center-results", "role": "listbox", "aria-label": commandMode ? "Matching commands" : mode === "mixed" ? "Matching files and commands" : "Matching files"}, results.length ? results.map((item, resultIndex) => h("button", {"className": `command-center-result ${resultIndex === chosen ? "selected" : ""}`, "id": `command-center-result-${resultIndex}`, "data-command-index": resultIndex, "key": item["result_kind"] === "command" ? item["id"] : item["path"], "role": "option", "aria-selected": resultIndex === chosen, "disabled": item["result_kind"] === "command" && item["enabled"] === false, "tabIndex": -1, "onMouseMove": () => { if (item["result_kind"] !== "command" || item["enabled"] !== false) setIndex(resultIndex); }, "onClick": () => chooseResult(item)},
          h("span", {"className": "command-result-icon"}, h(item["result_kind"] === "command" ? Command : File, {"size": 16, "aria-hidden": true})), h("span", {"className": "command-result-text"}, h("strong", null, item["result_kind"] === "command" ? item["label"] : item["path"].split("/").at(-1)), h("small", null, item["result_kind"] === "command" ? item["description"] || item["category"] || "" : item["path"])),
          item["result_kind"] === "command" ? h("span", {"className": "command-result-binding"}, (keymap[item["id"]] || []).map(chord => h("kbd", {"key": chord}, chord)), item["enabled"] === false ? h("small", null, "Unavailable") : null) : null)) : h("p", {"className": "command-center-empty", "role": "status"}, commandMode ? "No matching commands." : "No matching project files.")),
        h("footer", {"className": "command-center-footer"}, h("span", null, "↑ ↓ choose · Enter open · Esc close"), h("button", {"className": "shortcut-button", "onClick": () => openPalette("settings")}, "Keyboard shortcuts")))));
}
