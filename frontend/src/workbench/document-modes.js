function readVersion(conversion, mode) {
  if (!conversion) return null;
  if (conversion["sourceMode"] === mode) return {"content": conversion["sourceContent"], "parameters": conversion["sourceParameters"], "selection": conversion["sourceSelection"]};
  if (conversion["targetMode"] === mode) return {"content": conversion["targetContent"], "parameters": conversion["targetParameters"], "selection": conversion["targetSelection"]};
  return null;
}

export function planModeChange(document, mode) {
  if (document["mode"] === mode) return {"kind": "none"};
  if (mode === "text" || document["mode"] === "text" || !document["content"].trim()) return {"kind": "mode", "mode": mode, "content": document["content"]};
  const current = readVersion(document["conversion"], document["mode"]);
  const target = readVersion(document["conversion"], mode);
  const restoreSql = mode === "sql" && document["conversion"]?.["sourceMode"] === "sql";
  if (current && target && current["content"] === document["content"] && (restoreSql || current["parameters"] === document["parameters"])) {
    return {"kind": "restore", "mode": mode, ...target, "parameters": restoreSql ? document["parameters"] : target["parameters"]};
  }
  return {"kind": "convert", "mode": mode};
}

export function recordConversion(document, converted) {
  return {"sourceMode": document["mode"], "sourceContent": document["content"], "sourceParameters": document["parameters"], "sourceSelection": document["selection"],
    "targetMode": converted["mode"], "targetContent": converted["content"], "targetParameters": converted["parameters"], "targetSelection": converted["selection"]};
}

export function canonicalContent(document, nativeMode) {
  if (!document["content"].trim()) return document["content"];
  if (document["mode"] === nativeMode || document["mode"] === "text" || (nativeMode === "text" && document["mode"] === "sql")) return document["content"];
  const current = readVersion(document["conversion"], document["mode"]);
  const canonical = readVersion(document["conversion"], nativeMode === "text" ? "sql" : nativeMode);
  const bindingsMatch = nativeMode !== "edn" || current?.["parameters"] === document["parameters"];
  if (current && canonical && bindingsMatch && current["content"] === document["content"]) return canonical["content"];
  return null;
}

export function isDocumentDirty(document) {
  if (document["untitled"]) return true;
  const canonical = canonicalContent(document, document["nativeMode"]);
  return canonical === null || canonical !== document["savedContent"];
}

export function matchesConversionRequest(document, requested) {
  return Boolean(document) && document["mode"] === requested["mode"] && document["content"] === requested["content"] && document["parameters"] === requested["parameters"];
}
