import {parser} from "@nextjournal/clojure-mode";
import {snippetCompletion} from "@codemirror/autocomplete";
import {tokenizeDraft} from "./metadata-panel.js";
import {DBT_HELP, EDN_HELP, SQL_HELP} from "./editor-help.js";

const relationTypes = new Set(["model", "seed", "snapshot", "source", "relation"]);
const sqlReserved = new Set("select from where join left right full inner outer cross on as group order having limit offset union except intersect using qualify window with by and or not null asc desc".split(" "));

function normalizeColumn(column) {
  return typeof column === "string" ? {name: column} : {...column, data_type: column.data_type || column.type};
}

export function buildEditorIndex(intelligence = {}, schema = {}) {
  const resources = (intelligence.resources || []).map(resource => ({...resource,
    columns: (resource.columns || []).map(normalizeColumn), arguments: resource.arguments || []}));
  const observed = Object.entries(schema).map(([name, columns]) => ({name, resource_type: "relation", columns: columns.map(normalizeColumn)}));
  const relations = resources.filter(resource => relationTypes.has(resource.resource_type));
  for (const table of observed) {
    const candidates = relations.filter(resource => [resource.name, resource.alias, resource.identifier, resource.relation_name].includes(table.name));
    if (candidates.length === 1) {
      const declared = new Map(candidates[0].columns.map(column => [column.name, column]));
      table.columns.forEach(column => declared.set(column.name, {...column, ...declared.get(column.name),
        data_type: declared.get(column.name)?.data_type || column.data_type}));
      candidates[0].columns = [...declared.values()];
    } else if (!candidates.length) relations.push(table);
  }
  return {resources, relations, macros: resources.filter(resource => resource.resource_type === "macro"),
    emptyReason: intelligence.empty_reason || "No project metadata is available. Choose a project to discover models and macros."};
}

function findRelations(index, name, packageName) {
  const key = normalizeRelationName(name);
  return index.relations.filter(resource => (!packageName || resource.package_name === packageName) &&
    [resource.name, resource.alias, resource.identifier, resource.relation_name,
      resource.source_name && `${resource.source_name}.${resource.name}`,
      resource.package_name && `${resource.package_name}.${resource.name}`].filter(Boolean).some(value => normalizeRelationName(value) === key));
}

function resolveRelation(index, name, packageName) {
  const resources = findRelations(index, name, packageName);
  return resources.length === 1 ? resources[0] : null;
}

function readCall(tokens, start) {
  const name = tokens[start];
  if (name.kind !== "identifier" || tokens[start + 1]?.value !== "(") return null;
  let callee = name.value;
  let from = name.start;
  if (tokens[start - 1]?.value === "." && tokens[start - 2]?.kind === "identifier") {
    callee = `${tokens[start - 2].value}.${callee}`;
    from = tokens[start - 2].start;
  }
  const args = [];
  let group = [];
  let depth = 1;
  let end = start + 2;
  for (; end < tokens.length; end += 1) {
    const token = tokens[end];
    if (token.kind === "symbol" && token.value === "(") depth += 1;
    if (token.kind === "symbol" && token.value === ")") depth -= 1;
    if (!depth) break;
    if (depth === 1 && token.kind === "symbol" && token.value === ",") { args.push(group); group = []; }
    else group.push(token);
  }
  args.push(group);
  return {name: callee, from, nameTo: name.end, to: tokens[end]?.end || tokens.at(-1)?.end || name.end,
    args, closed: depth === 0};
}

function readTemplate(text, template) {
  const offset = template.start + 2;
  const tokens = tokenizeDraft(template.value, false).tokens.map(token => ({...token, start: token.start + offset, end: token.end + offset}));
  const calls = tokens.flatMap((_token, position) => {
    const call = readCall(tokens, position);
    if (!call) return [];
    const outside = tokens.filter(token => token.end <= call.from || token.start >= call.to);
    const direct = template.kind === "template" && outside.every(token => token.value === "-" &&
      (token.start === template.start + 2 || token.end === template.end - 2 && text.slice(template.end - 2, template.end) === "}}"));
    return [{...call, direct}];
  });
  return {tokens, calls, template};
}

function readLiteralArguments(call) {
  const positional = call.args.filter(group => group[1]?.value !== "=");
  return positional.every(group => group.length === 1 && group[0].kind === "string") ? positional.map(group => group[0].value) : null;
}

function resolveCall(index, call) {
  const args = readLiteralArguments(call);
  if (!call.closed || !call.direct || !args) return null;
  if (call.name === "ref" || call.name === "builtins.ref") {
    if (![1, 2].includes(args.length)) return null;
    const candidates = findRelations(index, args.at(-1), args.length === 2 ? args[0] : null).filter(resource => ["model", "seed", "snapshot"].includes(resource.resource_type));
    return candidates.length === 1 ? candidates[0] : null;
  }
  if (call.name === "source" || call.name === "builtins.source") {
    if (args.length !== 2) return null;
    const sources = index.relations.filter(resource => resource.resource_type === "source" && resource.source_name === args[0] && resource.name === args[1]);
    return sources.length === 1 ? sources[0] : null;
  }
  return null;
}

function readSqlName(tokens, start) {
  if (tokens[start]?.kind !== "identifier") return null;
  let end = start;
  const parts = [tokens[start].value];
  while (tokens[end + 1]?.value === "." && tokens[end + 2]?.kind === "identifier") { end += 2; parts.push(tokens[end].value); }
  return {name: parts.join("."), from: tokens[start].start, to: tokens[end].end, end};
}

function readSqlGroups(tokens, start, end) {
  const root = {from: start, to: end, tokens: [], parent: null};
  const groups = [root];
  let group = root;
  tokens.forEach(token => {
    if (token.kind === "symbol" && token.value === "(") {
      group.tokens.push(token);
      const child = {from: token.end, to: end, tokens: [], parent: group};
      groups.push(child);
      group = child;
    } else if (token.kind === "symbol" && token.value === ")" && group.parent) {
      group.to = token.start;
      group = group.parent;
      group.tokens.push(token);
    } else group.tokens.push(token);
  });
  return groups;
}

function isSqlWord(token, word) {
  return token?.kind === "identifier" && !token.quoted && token.value.toLowerCase() === word;
}

function readCteNames(tokens) {
  const names = [];
  if (!isSqlWord(tokens[0], "with")) return names;
  let offset = isSqlWord(tokens[1], "recursive") ? 2 : 1;
  while (tokens[offset]?.kind === "identifier") {
    const name = tokens[offset].value.toLowerCase();
    offset += 1;
    // Group contents are omitted, including optional CTE output-column lists.
    if (tokens[offset]?.value === "(" && tokens[offset + 1]?.value === ")") offset += 2;
    if (!isSqlWord(tokens[offset], "as")) break;
    offset += 1;
    if (isSqlWord(tokens[offset], "not")) offset += 1;
    if (isSqlWord(tokens[offset], "materialized")) offset += 1;
    if (tokens[offset]?.value !== "(" || tokens[offset + 1]?.value !== ")") break;
    names.push(name);
    offset += 2;
    if (tokens[offset]?.value !== ",") break;
    offset += 1;
  }
  return names;
}

function readQueryTokens(group, position) {
  const boundaries = group.tokens.filter(token => ["union", "except", "intersect"].some(word => isSqlWord(token, word)));
  const start = boundaries.findLast(token => token.end <= position)?.end ?? group.from;
  const end = boundaries.find(token => token.start >= position)?.start ?? group.to;
  return group.tokens.filter(token => token.start >= start && token.end <= end);
}

function readSqlScope(text, position, index) {
  const parsed = tokenizeDraft(text);
  const all = parsed.tokens;
  const start = all.findLast(token => token.kind === "symbol" && token.value === ";" && token.end <= position)?.end || 0;
  const end = all.find(token => token.kind === "symbol" && token.value === ";" && token.start >= position)?.start || text.length;
  const tokens = all.filter(token => token.start >= start && token.end <= end);
  const groups = readSqlGroups(tokens, start, end);
  const group = groups.filter(entry => entry.from <= position && entry.to >= position &&
    (entry === groups[0] || isSqlWord(entry.tokens[0], "select") || isSqlWord(entry.tokens[0], "with"))).at(-1) || groups[0];
  const cteNames = new Set();
  for (let ancestor = group; ancestor; ancestor = ancestor.parent) readCteNames(ancestor.tokens).forEach(name => cteNames.add(name));
  const scopedTokens = readQueryTokens(group, position);
  const templates = tokens.filter(token => token.kind.startsWith("template")).map(token => readTemplate(text, token));
  const bindings = [];
  let hasRelationClause = false;
  let hasUnresolvedRelation = false;
  scopedTokens.forEach((token, offset) => {
    if (!["from", "join"].some(word => isSqlWord(token, word))) return;
    hasRelationClause = true;
    const target = scopedTokens[offset + 1];
    if (!target) return;
    let name;
    let resource;
    let last = offset + 1;
    let targetEnd = target.end;
    if (target.kind === "template") {
      resource = templates.find(entry => entry.template === target)?.calls.map(call => resolveCall(index, call)).find(Boolean);
      name = resource?.name;
    } else {
      const qualified = readSqlName(scopedTokens, offset + 1);
      if (!qualified) { hasUnresolvedRelation = true; return; }
      name = qualified.name;
      last = qualified.end;
      targetEnd = qualified.to;
      if (!cteNames.has(name.toLowerCase())) resource = resolveRelation(index, name);
    }
    if (!resource) { hasUnresolvedRelation = true; return; }
    if (isSqlWord(scopedTokens[last + 1], "as")) last += 1;
    const alias = scopedTokens[last + 1]?.kind === "identifier" && (scopedTokens[last + 1].quoted || !sqlReserved.has(scopedTokens[last + 1].value.toLowerCase())) ? scopedTokens[last + 1].value : null;
    bindings.push({resource, name, alias, from: target.start, to: targetEnd});
  });
  const blocked = parsed.comments.some(comment => position > comment.start && position <= comment.end);
  return {tokens, templates, bindings, blocked, cteNames, hasRelationClause, hasUnresolvedRelation};
}

function resolveSqlQualifier(index, scope, qualifier) {
  const bound = findBoundRelation(scope.bindings, qualifier);
  if (bound) return bound;
  // Derived relations and unresolved aliases must not fall back to similarly named catalog entries.
  return scope.hasRelationClause || scope.cteNames.has(qualifier.toLowerCase()) ? null : resolveRelation(index, qualifier);
}

function readEdnForm(text, node) {
  const children = [];
  for (let child = node.firstChild; child; child = child.nextSibling) {
    if (!["LineComment", "Discard", "(", ")", "[", "]", "{", "}"].includes(child.name)) children.push(readEdnForm(text, child));
  }
  return {kind: node.name, text: text.slice(node.from, node.to), from: node.from, to: node.to, children};
}

function readEdnScope(text, position, index) {
  const tree = parser.parse(text);
  const root = readEdnForm(text, tree.topNode);
  let node = tree.resolveInner(position, -1);
  let blocked = false;
  while (node) {
    if (["Discard", "LineComment", "String", "RegExp", "Character"].includes(node.name)) blocked = true;
    node = node.parent;
  }
  let map;
  function findQuery(form) {
    if (form.from > position || form.to < position) return;
    if (form.kind === "Map") map = form;
    form.children.forEach(findQuery);
  }
  findQuery(root);
  const bindings = [];
  const clauses = [];
  for (let offset = 0; offset < (map?.children.length || 0); offset += 2) {
    const key = map.children[offset];
    const value = map.children[offset + 1];
    if (key.kind !== "Keyword" || !value) continue;
    clauses.push({key, value});
    if (![":from", ":join", ":left-join", ":right-join", ":full-join", ":cross-join"].includes(key.text)) continue;
    const forms = key.text.endsWith("join") && key.text !== ":cross-join" ? value.children.filter((_form, i) => i % 2 === 0) : value.children;
    forms.forEach(form => {
      const relation = form.kind === "Vector" ? form.children[0] : form;
      if (relation?.kind !== "Keyword") return;
      const name = relation.text.slice(1);
      const resource = resolveRelation(index, name);
      if (resource) bindings.push({resource, name, alias: form.kind === "Vector" && form.children[1]?.kind === "Keyword" ? form.children[1].text.slice(1) : null,
        from: relation.from, to: relation.to});
    });
  }
  const leaves = [];
  function visit(form) {
    if (["Keyword", "Symbol", "String", "Number", "Boolean", "Nil", "Character"].includes(form.kind)) leaves.push(form);
    else form.children.forEach(visit);
  }
  visit(root);
  const clause = clauses.find(entry => position >= entry.value.from && position <= entry.value.to)?.key.text;
  return {bindings, leaves, clause, blocked};
}

function describeResource(resource) {
  return {title: resource.name, kind: resource.resource_type, signature: resource.resource_type === "macro" ?
    `${resource.name}(${resource.arguments.map(argument => argument.name).join(", ")})` : resource.relation_name || resource.name,
    description: resource.description || "No description is declared for this resource.",
    path: resource.path, package: resource.package_name, columns: resource.columns,
    arguments: resource.arguments, resource};
}

function describeField(resource, column) {
  return {title: column.name, kind: "column", signature: `${resource.name}.${column.name}`, dataType: column.data_type,
    description: column.description || "No description is declared for this field.", path: resource.path, resource};
}

function findBoundRelation(bindings, qualifier) {
  const matching = bindings.filter(binding => [binding.alias, binding.name, binding.resource.name].filter(Boolean).some(name => name.toLowerCase() === qualifier.toLowerCase()));
  return matching.length === 1 ? matching[0].resource : null;
}

function findField(index, bindings, name, resolveUnbound = true) {
  const parts = name.split(".");
  const field = parts.pop();
  const qualifier = parts.join(".");
  const candidates = qualifier ? [findBoundRelation(bindings, qualifier) || (resolveUnbound ? resolveRelation(index, qualifier) : null)].filter(Boolean) : bindings.map(binding => binding.resource);
  const matches = candidates.flatMap(resource => resource.columns.filter(column => column.name.toLowerCase() === field.toLowerCase()).map(column => ({resource, column})));
  return matches.length === 1 ? describeField(matches[0].resource, matches[0].column) : null;
}

function isNamedParameter(text, from) {
  return text[from - 1] === ":" && text[from - 2] !== ":";
}

export function readEditorReference(text, position, format, index) {
  if (format === "text") return null;
  if (format === "edn") {
    const scope = readEdnScope(text, position, index);
    if (scope.blocked) return null;
    const token = scope.leaves.find(form => form.kind === "Keyword" && position >= form.from && position <= form.to);
    if (!token) return null;
    const binding = scope.bindings.find(entry => token.from === entry.from);
    const entry = binding ? describeResource(binding.resource) :
      EDN_HELP.find(help => help.name === token.text) || findField(index, scope.bindings, token.text.slice(1));
    return entry ? {...entry, from: token.from, to: token.to} : null;
  }
  const scope = readSqlScope(text, position, index);
  if (scope.blocked) return null;
  const template = scope.templates.find(entry => position >= entry.template.start && position <= entry.template.end);
  if (template) {
    const call = template.calls.filter(call => position >= call.from && position <= call.to).sort((a, b) => (a.to - a.from) - (b.to - b.from))[0];
    if (call) {
      const resource = resolveCall(index, call);
      if (resource && position > call.nameTo) {
        const argument = call.args.flat().find(token => token.kind === "string" && position >= token.start && position <= token.end);
        if (argument) return {...describeResource(resource), from: argument.start + 1, to: argument.end - 1};
      }
      const macros = index.macros.filter(macro => call.name === macro.name || call.name === `${macro.package_name}.${macro.name}`);
      const help = DBT_HELP.find(entry => entry.name === call.name || `builtins.${entry.name}` === call.name);
      const entry = macros.length === 1 ? describeResource(macros[0]) : help;
      if (entry && position <= call.nameTo) return {...entry, title: call.name, from: call.from, to: call.nameTo};
    }
    const token = template.tokens.find(token => token.kind === "identifier" && position >= token.start && position <= token.end);
    const help = DBT_HELP.find(entry => entry.name === token?.value);
    return help ? {...help, title: help.name, from: token.start, to: token.end} : null;
  }
  const binding = scope.bindings.find(entry => position >= entry.from && position <= entry.to);
  if (binding) return {...describeResource(binding.resource), from: binding.from, to: binding.to};
  const offset = scope.tokens.findIndex(token => token.kind === "identifier" && position >= token.start && position <= token.end);
  if (offset < 0) return null;
  let start = offset;
  while (scope.tokens[start - 1]?.value === "." && scope.tokens[start - 2]?.kind === "identifier") start -= 2;
  const token = readSqlName(scope.tokens, start);
  if (isNamedParameter(text, token.from)) return null;
  const help = SQL_HELP.find(entry => entry.name.toLowerCase() === token.name.toLowerCase());
  const field = scope.hasUnresolvedRelation && !token.name.includes(".") ? null :
    findField(index, scope.bindings, token.name, !scope.hasRelationClause && scope.cteNames.size === 0);
  const entry = field || help;
  return entry ? {...entry, title: entry.title || entry.name, from: token.from, to: token.to} : null;
}

function createCompletionInfo(entry) {
  return function showInfo() { return renderEditorDocumentation(entry); };
}

export function renderEditorDocumentation(entry, onNavigate) {
  const dom = document.createElement("section");
  dom.className = "editor-documentation";
  dom.setAttribute("aria-label", "Editor documentation");
  function append(tag, text, className) {
    if (!text) return;
    const element = document.createElement(tag);
    element.textContent = text;
    if (className) element.className = className;
    dom.append(element);
    return element;
  }
  append("div", entry.kind, "editor-documentation-kind");
  append("strong", entry.title || entry.name);
  append("code", entry.signature);
  append("p", entry.description);
  if (entry.dataType) append("span", entry.dataType, "editor-documentation-type");
  if (entry.example) append("pre", entry.example);
  if (entry.columns?.length) append("p", entry.columns.slice(0, 6).map(column => `${column.name}${column.data_type ? `: ${column.data_type}` : ""}`).join("\n"), "editor-documentation-columns");
  if (entry.arguments?.length) append("p", entry.arguments.map(argument => `${argument.name}${argument.type ? ` (${argument.type})` : ""}${argument.description ? `: ${argument.description}` : ""}`).join("\n"), "editor-documentation-columns");
  if (entry.package) append("small", entry.package);
  if (entry.path) {
    append("small", entry.path);
    if (onNavigate) {
      const open = append("button", "Open definition");
      open.type = "button";
      open.className = "button small";
      open.addEventListener("click", () => onNavigate(entry.path));
    } else append("small", "Ctrl/Cmd-click or F12 to open definition");
  }
  if (entry.url) {
    const link = append("a", "Reference documentation");
    link.href = entry.url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
  }
  return dom;
}

function createResourceCompletion(resource, label, apply = label, detail = resource.resource_type) {
  const documentation = describeResource(resource);
  return {label, apply, type: resource.resource_type === "macro" ? "function" : "class", detail: resource.package_name ? `${detail} · ${resource.package_name}` : detail,
    info: createCompletionInfo(documentation), boost: resource.path ? 5 : 0};
}

function createHelpCompletion(entry, snippet = entry.name) {
  return snippetCompletion(snippet, {label: entry.name, type: entry.kind.includes("clause") ? "keyword" : "function", detail: entry.kind, info: createCompletionInfo(entry)});
}

function createDbtCompletions(index) {
  const counts = new Map();
  index.macros.forEach(macro => counts.set(macro.name, (counts.get(macro.name) || 0) + 1));
  const builtins = DBT_HELP.map(entry => {
    let snippet = entry.name;
    if (entry.name === "ref") snippet = "ref('${model}')";
    else if (entry.name === "source") snippet = "source('${source}', '${table}')";
    else if (entry.name === "config") snippet = "config(materialized='${view}')";
    else if (entry.name === "var" || entry.name === "env_var" || entry.name === "doc") snippet = `${entry.name}('\${name}')`;
    else if (entry.name === "is_incremental") snippet = "is_incremental()";
    else if (entry.kind === "dbt function") snippet = `${entry.name}(\${value})`;
    return createHelpCompletion(entry, snippet);
  });
  const macros = index.macros.flatMap(macro => {
    const labels = macro.package_name ? [`${macro.package_name}.${macro.name}`] : [];
    if (counts.get(macro.name) === 1 && !DBT_HELP.some(help => help.name === macro.name)) labels.unshift(macro.name);
    return labels.map(label => snippetCompletion(`${label}(${macro.arguments.map(argument => `\${${argument.name}}`).join(", ")})`, createResourceCompletion(macro, label)));
  });
  return [...builtins, ...macros];
}

function completeTemplate(context, template, index, dbtOptions) {
  const position = context.pos;
  const call = template.calls.filter(call => position > call.nameTo && position <= call.to).sort((a, b) => (a.to - a.from) - (b.to - b.from))[0];
  const argument = call?.args.findIndex(group => group.some(token => token.kind === "string" && position > token.start && position <= token.end));
  const string = argument >= 0 ? call.args[argument].find(token => token.kind === "string" && position > token.start && position <= token.end) : null;
  if (string && ["ref", "builtins.ref", "source", "builtins.source"].includes(call.name)) {
    let resources;
    let labels;
    if (call.name.endsWith("source")) {
      const sourceName = call.args[0]?.length === 1 ? call.args[0][0].value : null;
      resources = index.relations.filter(resource => resource.resource_type === "source" && (argument === 0 || resource.source_name === sourceName));
      labels = resources.map(resource => argument === 0 ? resource.source_name : resource.name);
    } else {
      const packageName = argument === 1 && call.args[0]?.length === 1 ? call.args[0][0].value : null;
      resources = index.relations.filter(resource => resource.resource_type !== "source" && resource.resource_type !== "relation" && (!packageName || resource.package_name === packageName));
      labels = resources.map(resource => resource.name);
    }
    const options = resources.map((resource, i) => createResourceCompletion(resource, labels[i]));
    const unique = options.filter((option, i) => options.findIndex(other => other.label === option.label) === i);
    return {from: string.start + 1, options: unique, validFor: /^[\w.-]*$/};
  }
  const word = context.matchBefore(/[\w.]*$/);
  if (!word || (!context.explicit && !word.text)) return null;
  const token = template.tokens.find(token => token.kind === "string" && position > token.start && position <= token.end);
  if (token) return null;
  return {from: word.from, options: dbtOptions, validFor: /^[\w.]*$/};
}

function quoteSqlName(name) {
  return /^[a-zA-Z_][\w$]*$/.test(name) && !sqlReserved.has(name.toLowerCase()) ? name : `"${name.replaceAll('"', '""')}"`;
}

function splitSqlRelation(name) {
  const parts = [];
  let start = 0;
  let quoted = false;
  for (let offset = 0; offset < name.length; offset += 1) {
    if (name[offset] === '"') {
      if (quoted && name[offset + 1] === '"') offset += 1;
      else quoted = !quoted;
    } else if (name[offset] === "." && !quoted) { parts.push(name.slice(start, offset)); start = offset + 1; }
  }
  parts.push(name.slice(start));
  return parts.map(part => part.trim());
}

function quoteSqlRelation(name) {
  return splitSqlRelation(name).map(part => /^"(?:[^"]|"")*"$/.test(part) ? part : quoteSqlName(part)).join(".");
}

function normalizeRelationName(name) {
  return splitSqlRelation(name).map(part => /^"(?:[^"]|"")*"$/.test(part) ? part.slice(1, -1).replaceAll('""', '"') : part).join(".").toLowerCase();
}

function createFieldCompletions(resources, format) {
  return resources.flatMap(resource => resource.columns.map(column => ({label: format === "edn" ? `:${column.name}` : column.name,
    apply: format === "edn" ? `:${column.name}` : quoteSqlName(column.name), type: "property",
    detail: `${column.data_type || "column"} · ${resource.name}`, info: createCompletionInfo(describeField(resource, column)), boost: 4})));
}

export function createProjectCompletionSource(index, format) {
  const dbtOptions = createDbtCompletions(index);
  return function completeProject(context) {
    const text = context.state.doc.toString();
    if (format === "sql") {
      const scope = readSqlScope(text, context.pos, index);
      if (scope.blocked) return null;
      const template = scope.templates.find(entry => context.pos > entry.template.start + 1 && context.pos <= entry.template.end);
      if (template) {
        const result = completeTemplate(context, template, index, dbtOptions);
        return result ? {...result, template: true} : null;
      }
      const word = context.matchBefore(/[\w$.]*$/);
      if (!word || (!context.explicit && !word.text)) return null;
      if (isNamedParameter(text, word.from)) return null;
      // Dropped comment tokens cannot offer completions; SQL string literals are never schema references.
      if (word.text && !scope.tokens.some(token => token.kind === "identifier" && token.start >= word.from && token.start < context.pos)) return null;
      if (scope.tokens.some(token => token.kind === "string" && context.pos > token.start && context.pos < token.end)) return null;
      const dot = word.text.lastIndexOf(".");
      if (dot !== -1) {
        const qualifier = word.text.slice(0, dot);
        const resource = resolveSqlQualifier(index, scope, qualifier);
        return resource ? {from: word.from + dot + 1, options: createFieldCompletions([resource], format), validFor: /^[\w$]*$/} : null;
      }
      const fields = createFieldCompletions(scope.hasUnresolvedRelation ? [] : scope.hasRelationClause ? scope.bindings.map(binding => binding.resource) : index.relations, format);
      const relations = index.relations.map(resource => {
        const name = resource.relation_name || resource.alias || resource.identifier || resource.name;
        return createResourceCompletion(resource, name, quoteSqlRelation(name));
      });
      return {from: word.from, options: [...fields, ...relations, ...SQL_HELP.map(entry => createHelpCompletion(entry, `${entry.name}(\${expression})`))], validFor: /^[\w$]*$/};
    }
    if (format !== "edn") return null;
    const scope = readEdnScope(text, context.pos, index);
    if (scope.blocked) return null;
    if (scope.leaves.some(form => ["String", "Character"].includes(form.kind) && context.pos > form.from && context.pos < form.to)) return null;
    const word = context.matchBefore(/:[\w./!*+?=<>-]*$/);
    if (!word && !context.explicit) return null;
    if (word && !scope.leaves.some(form => form.kind === "Keyword" && context.pos >= form.from && context.pos <= form.to)) return null;
    const dot = word?.text.lastIndexOf(".") ?? -1;
    if (dot !== -1) {
      const qualifier = word.text.slice(1, dot);
      const resource = findBoundRelation(scope.bindings, qualifier) || resolveRelation(index, qualifier);
      const options = resource ? createFieldCompletions([resource], format).map(option => ({...option, label: option.label.slice(1), apply: option.apply.slice(1)})) : [];
      return {from: word.from + dot + 1, options, validFor: /^[\w-]*$/};
    }
    const fields = createFieldCompletions(scope.bindings.length ? scope.bindings.map(binding => binding.resource) : index.relations, format);
    const relations = index.relations.map(resource => createResourceCompletion(resource, `:${resource.name}`));
    const options = [...EDN_HELP.map(entry => createHelpCompletion(entry)), ...fields, ...relations];
    return {from: word?.from ?? context.pos, options, validFor: /^:[\w-]*$/};
  };
}

export function readTemplateHighlights(text) {
  return tokenizeDraft(text).tokens.filter(token => token.kind.startsWith("template")).flatMap(template => {
    const {tokens} = readTemplate(text, template);
    return [{from: template.start, to: template.start + 2, className: "syntax-template"},
      ...(text.slice(template.end - 2, template.end).match(/^(}}|%})$/) ? [{from: template.end - 2, to: template.end, className: "syntax-template"}] : []),
      ...tokens.filter(token => token.kind === "identifier" || token.kind === "string").map(token => ({from: token.start, to: token.end,
        className: token.kind === "string" ? "syntax-template-string" : "syntax-template-function"}))];
  });
}
