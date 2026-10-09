import React from "react";
import {requestJson} from "./panel-utils.js";
import {GitBranch} from "@phosphor-icons/react/dist/csr/GitBranch";
import {GitCommit} from "@phosphor-icons/react/dist/csr/GitCommit";
import {GitPullRequest} from "@phosphor-icons/react/dist/csr/GitPullRequest";
import {ArrowClockwise} from "@phosphor-icons/react/dist/csr/ArrowClockwise";
import {CheckCircle} from "@phosphor-icons/react/dist/csr/CheckCircle";
import {WarningCircle} from "@phosphor-icons/react/dist/csr/WarningCircle";

const h = React.createElement;
const tabs = [["changes", "Changes"], ["commits", "Commits"], ["branches", "Branches"], ["pulls", "Pull requests"]];

function useLocalResource(url) {
  const [state, setState] = React.useState({"data": null, "error": null, "loading": true});
  const [revision, setRevision] = React.useState(0);
  React.useEffect(function loadLocalResource() {
    const controller = new AbortController();
    setState({"data": null, "error": null, "loading": true});
    requestJson(url, "GET", undefined, controller.signal).then(data => {
      if (!controller.signal.aborted) setState({"data": data, "error": null, "loading": false});
    }).catch(error => {
      if (error.name !== "AbortError") setState({"data": null, "error": error.message, "loading": false});
    });
    return function cancelLocalResource() { controller.abort(); };
  }, [url, revision]);
  return {...state, "replace": data => setState({"data": data, "error": null, "loading": false}),
    "reload": () => setRevision(value => value + 1)};
}

function renderIcon(Icon) { return h(Icon, {"size": 15, "aria-hidden": true}); }

function classifyDiffLine(line) {
  if (line.startsWith("+") && !line.startsWith("+++")) return "diff-added";
  if (line.startsWith("-") && !line.startsWith("---")) return "diff-removed";
  return "diff-context";
}

function flushDiffRows(state) {
  const count = Math.max(state["before"].length, state["after"].length);
  return {"rows": [...state["rows"], ...Array.from({"length": count}, (_, index) =>
    ({"before": state["before"][index] || "", "after": state["after"][index] || ""}))], "before": [], "after": []};
}

function buildDiffRows(diff) {
  const state = diff.split("\n").reduce((previous, line) => {
    const kind = classifyDiffLine(line);
    if (kind === "diff-removed") return {...previous, "before": [...previous["before"], line]};
    if (kind === "diff-added") return {...previous, "after": [...previous["after"], line]};
    const flushed = flushDiffRows(previous);
    return {...flushed, "rows": [...flushed["rows"], {"before": line, "after": line}]};
  }, {"rows": [], "before": [], "after": []});
  return flushDiffRows(state)["rows"];
}

function DiffView(props) {
  const diff = props["diff"];
  const [split, setSplit] = React.useState(false);
  const content = diff["diff"] || "No differences.";
  const rows = split ? buildDiffRows(content) : null;
  return h("section", {"className": "diff-view", "aria-label": `Diff for ${diff["path"]}`},
    h("div", {"className": "panel-toolbar"}, h("strong", {"style": {"overflowWrap": "anywhere", "flex": 1}}, diff["path"]),
      h("button", {"className": "button small", "onClick": () => setSplit(!split), "aria-pressed": split}, split ? "Unified" : "Side by side")),
    split ? h("div", {"className": "diff-columns"}, ["before", "after"].map(side => h("pre", {"key": side, "aria-label": side === "before" ? "Before changes" : "After changes"},
      rows.map((row, index) => h("div", {"key": index, "className": classifyDiffLine(row[side])}, row[side] || " "))))) :
      h("pre", {"aria-label": "Unified diff"}, content.split("\n").map((line, index) =>
        h("div", {"key": index, "className": classifyDiffLine(line)}, line || " "))));
}

function DraftDialog(props) {
  const dialog = React.useRef(null);
  const [title, setTitle] = React.useState("");
  const [body, setBody] = React.useState("");
  const [base, setBase] = React.useState(props["defaultBase"]);
  React.useEffect(function openDraftDialog() { dialog.current.showModal(); }, []);
  return h("dialog", {"ref": dialog, "className": "git-dialog", "aria-labelledby": "draft-pr-title",
    "onCancel": event => { event.preventDefault(); if (!props["busy"]) props["onClose"](); },
    "style": {"width": "min(560px, calc(100vw - 32px))", "background": "var(--surface)", "color": "var(--ink)", "border": "1px solid var(--line)", "borderRadius": "8px"}},
    h("form", {"onSubmit": event => { event.preventDefault(); props["onCreate"]({"title": title.trim(), "body": body, "base": base.trim()}); }},
      h("h2", {"id": "draft-pr-title"}, "Create draft pull request"),
      h("p", {"className": "hint"}, `${props["repository"]}: ${props["branch"]} → ${base || "choose a base"}`),
      h("label", null, "Title", h("input", {"autoFocus": true, "required": true, "maxLength": 256, "value": title,
        "disabled": props["busy"], "onChange": event => setTitle(event.target.value), "placeholder": "Describe the model change"})),
      h("label", {"className": "commit-message"}, "Description", h("textarea", {"value": body, "maxLength": 65536,
        "disabled": props["busy"], "onChange": event => setBody(event.target.value), "placeholder": "What changed and how you verified it"})),
      h("label", null, "Base branch", h("input", {"required": true, "value": base, "disabled": props["busy"],
        "onChange": event => setBase(event.target.value), "placeholder": "main", "aria-describedby": "draft-pr-note"})),
      h("p", {"className": "hint", "id": "draft-pr-note"}, "Creates a draft on GitHub from your published branch. Local changes must be committed."),
      props["error"] ? h("p", {"role": "alert", "className": "git-diagnostics"}, props["error"]) : null,
      h("div", {"className": "panel-toolbar"},
        h("button", {"type": "button", "className": "button", "disabled": props["busy"], "onClick": props["onClose"]}, "Cancel"),
        h("button", {"type": "submit", "className": "button primary", "disabled": props["busy"] || !title.trim() || !base.trim() || base.trim() === props["branch"]},
          props["busy"] ? "Creating draft…" : "Create draft on GitHub"))));
}

export function GitPanel(props) {
  const base = props["base"];
  const resource = useLocalResource(`${base}/git`);
  const github = useLocalResource(`${base}/github`);
  const [tab, setTab] = React.useState("changes");
  const [diff, setDiff] = React.useState(null);
  const [source, setSource] = React.useState("working");
  const [message, setMessage] = React.useState("");
  const [branch, setBranch] = React.useState("");
  const [compareBranch, setCompareBranch] = React.useState("");
  const [comparePath, setComparePath] = React.useState("");
  const [rebaseBranch, setRebaseBranch] = React.useState("");
  const [pickCommit, setPickCommit] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [diagnostics, setDiagnostics] = React.useState(null);
  const [pulls, setPulls] = React.useState(null);
  const [checks, setChecks] = React.useState(null);
  const [draftOpen, setDraftOpen] = React.useState(false);
  const [draftError, setDraftError] = React.useState(null);
  const activeBase = React.useRef(base);
  const mutationLock = React.useRef(false);
  const diffRevision = React.useRef(0);
  activeBase.current = base;
  const data = resource["data"];
  const changes = data?.["changes"] || [];
  const branches = data?.["branches"] || [];
  const commits = data?.["commits"] || [];
  const clean = changes.length === 0;
  const staged = changes.filter(change => change["staged"]);
  const conflicted = changes.some(change => change["index_status"] === "U" || change["working_status"] === "U" ||
    ["AA", "DD"].includes(`${change["index_status"]}${change["working_status"]}`));
  React.useEffect(function resetProjectState() {
    setDiff(null); setMessage(""); setBranch(""); setCompareBranch(""); setComparePath(""); setRebaseBranch(""); setPickCommit("");
    setPulls(null); setChecks(null); setDraftOpen(false); setDraftError(null); setDiagnostics(null);
    diffRevision.current += 1;
  }, [base]);

  function reportError(error) {
    setDiagnostics(error.message);
    props["onError"](error);
  }

  async function mutateGit(action, body) {
    if (mutationLock.current) return;
    mutationLock.current = true;
    setBusy(true);
    const requestBase = base;
    try {
      const result = await requestJson(`${base}/git/${action}`, "POST", body);
      if (activeBase.current !== requestBase) return;
      resource["replace"](result);
      setDiff(null);
      diffRevision.current += 1;
      if (result["operation_failed"]) { reportError(new Error(result["error"] || `Git ${action} failed.`)); return; }
      if (!["stage", "unstage"].includes(action)) setDiagnostics(null);
      if (action === "commit") setMessage("");
      if (action === "branch") setBranch("");
      if (action === "cherry-pick") setPickCommit("");
      props["onNotice"](`Git ${action} completed.`);
    } catch (error) { if (activeBase.current === requestBase) reportError(error); }
    finally { mutationLock.current = false; setBusy(false); }
  }

  async function loadDiff(path, mode = source) {
    const revision = ++diffRevision.current;
    const requestBase = base;
    try {
      const result = await requestJson(`${base}/git/diff?path=${encodeURIComponent(path)}&source=${encodeURIComponent(mode)}${compareBranch ? `&base_branch=${encodeURIComponent(compareBranch)}` : ""}`);
      if (activeBase.current === requestBase && diffRevision.current === revision) setDiff(result);
    } catch (error) { if (activeBase.current === requestBase && diffRevision.current === revision) reportError(error); }
  }

  async function loadGithub(kind, number) {
    if (mutationLock.current) return;
    mutationLock.current = true;
    setBusy(true);
    const requestBase = base;
    try {
      const result = await requestJson(`${base}/github/${kind}${number === undefined ? "" : `?number=${number}`}`);
      if (activeBase.current !== requestBase) return;
      if (kind === "pulls") { setPulls(result["pull_requests"]); setChecks(null); }
      else setChecks(result);
      setDiagnostics(null);
    } catch (error) { if (activeBase.current === requestBase) reportError(error); }
    finally { mutationLock.current = false; setBusy(false); }
  }

  async function createDraft(body) {
    if (mutationLock.current) return;
    mutationLock.current = true;
    setBusy(true); setDraftError(null);
    const requestBase = base;
    try {
      const result = await requestJson(`${base}/github`, "POST", body);
      if (activeBase.current !== requestBase) return;
      setPulls(previous => [result, ...(previous || []).filter(item => item["number"] !== result["number"])]);
      setDraftOpen(false);
      setDiagnostics(null);
      props["onNotice"](`Draft pull request #${result["number"]} created on GitHub.`);
    } catch (error) { if (activeBase.current === requestBase) { setDraftError(error.message); props["onError"](error); } }
    finally { mutationLock.current = false; setBusy(false); }
  }

  if (resource["loading"]) return h("p", {"className": "panel-empty", "role": "status"}, "Loading source control…");
  if (resource["error"]) return h("div", {"className": "panel-empty"}, h("p", {"role": "alert"}, resource["error"]),
    h("button", {"className": "button small", "onClick": resource["reload"]}, "Retry"));
  if (!data?.["available"]) return h("p", {"className": "panel-empty"}, data?.["reason"] || "Open a Git repository root to manage source control.");

  const defaultBase = branches.find(item => ["main", "master"].includes(item["name"]) && item["name"] !== data["branch"])?.["name"] || "";
  return h("div", {"className": "inspector-content git-panel"},
    h("div", {"className": "panel-toolbar"}, renderIcon(GitBranch), h("strong", {"style": {"flex": 1, "overflowWrap": "anywhere"}}, data["branch"] || "Detached HEAD"),
      h("span", {"className": "pill"}, `${changes.length} changes`),
      h("button", {"className": "button small", "disabled": busy, "aria-label": "Refresh source control", "onClick": () => { setDiff(null); diffRevision.current += 1; resource["reload"](); github["reload"](); }}, renderIcon(ArrowClockwise))),
    h("nav", {"className": "results-view-tabs", "aria-label": "Version control", "style": {"flexWrap": "wrap"}},
      tabs.map(([value, label]) => h("button", {"key": value, "className": tab === value ? "active" : "", "aria-pressed": tab === value, "onClick": () => setTab(value)}, label))),
    diagnostics ? h("div", {"role": "alert", "className": "git-diagnostics", "style": {"overflowWrap": "anywhere"}}, renderIcon(WarningCircle), h("p", null, diagnostics)) : null,
    conflicted ? h("p", {"className": "hint", "role": "status"}, "Conflicts remain on disk. Resolve or abort the Git operation in your terminal, then refresh.") : null,
    tab === "changes" ? h("section", {"className": "git-section"},
      h("div", {"className": "panel-toolbar"},
        h("label", null, "Diff source", h("select", {"aria-label": "Diff source", "value": source, "disabled": Boolean(compareBranch), "onChange": event => {
          setSource(event.target.value); setDiff(null); diffRevision.current += 1;
        }}, h("option", {"value": "working"}, "Working tree"), h("option", {"value": "index"}, "Staged"))),
        h("label", null, "Compare branch", h("select", {"aria-label": "Compare branch", "value": compareBranch, "onChange": event => {
          setCompareBranch(event.target.value); setDiff(null); diffRevision.current += 1;
        }}, h("option", {"value": ""}, "Working changes"), branches.map(item => h("option", {"key": item["name"], "value": item["name"]}, item["name"]))))),
      changes.length ? changes.map(change => h("div", {"className": "git-change", "key": change["path"]},
        h("button", {"className": "file-diff-button", "onClick": () => loadDiff(change["path"]), "aria-label": `View diff for ${change["path"]}`},
          h("span", {"className": "pill"}, change["untracked"] ? "NEW" : `${change["index_status"]}${change["working_status"]}`), change["path"]),
        change["unstaged"] || change["untracked"] ? h("button", {"className": "button small", "disabled": busy, "aria-label": `Stage ${change["path"]}`, "onClick": () => mutateGit("stage", {"path": change["path"]})}, "Stage") : null,
        change["staged"] ? h("button", {"className": "button small", "disabled": busy, "aria-label": `Unstage ${change["path"]}`, "onClick": () => mutateGit("unstage", {"path": change["path"]})}, "Unstage") : null)) :
        h("p", {"className": "hint"}, renderIcon(CheckCircle), " Working tree is clean."),
      compareBranch ? h("div", {"className": "panel-toolbar"},
        h("input", {"aria-label": "Branch diff file", "placeholder": "models/customers.sql", "value": comparePath, "onChange": event => setComparePath(event.target.value)}),
        h("button", {"className": "button small", "disabled": !comparePath.trim(), "onClick": () => loadDiff(comparePath.trim())}, "Show diff")) : null,
      diff ? h(DiffView, {"diff": diff}) : null,
      h("label", {"className": "commit-message"}, "Commit message", h("textarea", {"aria-label": "Commit message", "value": message, "maxLength": 10000,
        "onChange": event => setMessage(event.target.value), "placeholder": "Describe the change"})),
      h("button", {"className": "button primary", "disabled": busy || conflicted || !message.trim() || !staged.length,
        "onClick": () => mutateGit("commit", {"message": message})}, renderIcon(GitCommit), ` Commit ${staged.length} staged ${staged.length === 1 ? "file" : "files"}`),
      h("p", {"className": "hint"}, "Commits stay in the local repository.")) : null,
    tab === "commits" ? h("section", {"className": "git-section git-history"},
      h("label", null, "Local commit hash", h("input", {"aria-label": "Cherry-pick commit hash", "value": pickCommit,
        "placeholder": "Commit from another local branch", "onChange": event => setPickCommit(event.target.value)})),
      h("button", {"className": "button small", "disabled": busy || !clean || !data["branch"] || !/^[0-9a-fA-F]{7,40}$/.test(pickCommit.trim()),
        "onClick": () => mutateGit("cherry-pick", {"commit": pickCommit.trim()})}, "Cherry-pick commit"),
      commits.length ? commits.map(commit => h("article", {"className": "commit-entry", "key": commit["id"]},
        h("strong", null, commit["message"]),
        h("p", {"className": "hint"}, `${commit["short_id"]} · ${commit["author"]} · ${new Date(commit["committed_at"]).toLocaleString()}`),
        h("button", {"className": "button small", "disabled": busy || !clean || !data["branch"] || commit === commits[0],
          "onClick": () => mutateGit("cherry-pick", {"commit": commit["id"]}), "aria-label": `Cherry-pick ${commit["short_id"]}`}, "Cherry-pick"))) :
        h("p", {"className": "hint"}, "No commits recorded."),
      h("p", {"className": "hint"}, "Cherry-pick applies a local commit to this branch. It requires a clean working tree.")) : null,
    tab === "branches" ? h("section", {"className": "git-section"},
      h("label", null, "Switch branch", h("select", {"aria-label": "Git branch", "value": data["branch"] || "", "disabled": busy || !clean,
        "onChange": event => mutateGit("switch", {"branch": event.target.value})},
        !data["branch"] ? h("option", {"value": ""}, "Detached HEAD") : null,
        branches.map(item => h("option", {"key": item["name"], "value": item["name"]}, item["name"])))),
      h("label", null, "Branch name", h("input", {"aria-label": "New branch name", "value": branch, "disabled": busy,
        "placeholder": "feat/customer-metrics", "onChange": event => setBranch(event.target.value)})),
      h("button", {"className": "button small", "disabled": busy || !clean || !branch.trim(), "onClick": () => mutateGit("branch", {"branch": branch.trim()})}, "Create branch"),
      h("label", null, "Rebase onto", h("select", {"aria-label": "Rebase base branch", "value": rebaseBranch, "disabled": busy || !clean,
        "onChange": event => setRebaseBranch(event.target.value)}, h("option", {"value": ""}, "Choose a local branch"),
        branches.filter(item => item["name"] !== data["branch"]).map(item => h("option", {"key": item["name"], "value": item["name"]}, item["name"])))),
      h("button", {"className": "button small", "disabled": busy || !clean || !rebaseBranch || !data["branch"],
        "onClick": () => mutateGit("rebase", {"branch": rebaseBranch})}, "Rebase current branch"),
      h("p", {"className": "hint"}, "Rebase rewrites this branch's local commits onto the selected branch. Branch actions require a clean working tree.")) : null,
    tab === "pulls" ? h("section", {"className": "git-section"},
      github["loading"] ? h("p", {"className": "hint", "role": "status"}, "Checking local GitHub integration…") :
        !github["data"]?.["available"] ? h("div", null, h("p", {"className": "hint"}, github["error"] || github["data"]?.["reason"] || "GitHub integration is unavailable."),
          h("button", {"className": "button small", "disabled": busy, "onClick": github["reload"]}, "Check integration")) :
          h(React.Fragment, null,
            h("p", {"className": "hint"}, github["data"]["repository"]),
            h("div", {"className": "panel-toolbar"},
              h("button", {"className": "button small", "disabled": busy, "onClick": () => loadGithub("pulls")}, renderIcon(ArrowClockwise), pulls === null ? " Load pull requests" : " Reload pull requests"),
              h("button", {"className": "button primary small", "disabled": busy || !clean || !data["branch"], "onClick": () => { setDraftError(null); setDraftOpen(true); }}, renderIcon(GitPullRequest), " Draft pull request")),
            pulls === null ? h("p", {"className": "hint"}, "Load pull requests to read GitHub. Checks load when selected.") :
              pulls.length ? pulls.map(pull => h("article", {"className": "commit-entry", "key": pull["number"]},
                h("a", {"href": pull["url"], "target": "_blank", "rel": "noopener noreferrer", "aria-label": `Open pull request ${pull["number"]} on GitHub`},
                  h("strong", null, `#${pull["number"]} ${pull["title"]}`)),
                h("p", {"className": "hint"}, `${pull["draft"] ? "Draft" : pull["state"]} · ${pull["head"]} → ${pull["base"]}`),
                h("button", {"className": "button small", "disabled": busy, "onClick": () => loadGithub("checks", pull["number"])}, "Load checks"))) :
                h("p", {"className": "hint"}, "No open pull requests."),
            checks ? h("section", {"aria-label": `Checks for pull request ${checks["number"]}`}, h("h3", null, `Checks · #${checks["number"]}`),
              checks["checks"].length ? checks["checks"].map(check => h("div", {"className": "git-change", "key": check["id"]},
                renderIcon(check["conclusion"] === "success" ? CheckCircle : WarningCircle),
                h("span", {"style": {"flex": 1, "overflowWrap": "anywhere"}}, check["name"]),
                h("span", {"className": "pill"}, check["conclusion"] || check["status"]))) : h("p", {"className": "hint"}, "No check runs reported.")) : null)) : null,
    draftOpen ? h(DraftDialog, {"key": base, "repository": github["data"]["repository"], "branch": data["branch"], "defaultBase": defaultBase,
      "busy": busy, "error": draftError, "onClose": () => setDraftOpen(false), "onCreate": createDraft}) : null);
}
