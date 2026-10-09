import React from "react";
const h = React.createElement;

export async function requestJson(url, method = "GET", body, signal) {
  const response = await fetch(url, {method, "headers": {"Content-Type": "application/json"}, "body": body ? JSON.stringify(body) : undefined, signal});
  const data = await response.json();
  if (!response.ok) throw new Error(data["detail"] || "Request failed");
  return data;
}

export function useResource(url) {
  const [state, setState] = React.useState({"data": null, "error": null, "loading": true});
  const [revision, setRevision] = React.useState(0);
  React.useEffect(function loadResource() {
    const controller = new AbortController();
    setState({"data": null, "error": null, "loading": true});
    requestJson(url, "GET", undefined, controller.signal).then(data => setState({"data": data, "error": null, "loading": false})).catch(error => {
      if (error.name !== "AbortError") setState({"data": null, "error": error.message, "loading": false});
    });
    return function cancelResource() { controller.abort(); };
  }, [url, revision]);
  return {...state, "reload": function reload() { setRevision(value => value + 1); }};
}

export function ResourceState(props) {
  const resource = props["resource"];
  if (resource["loading"]) return h("p", {"className": "panel-empty", "role": "status"}, "Loading project data…");
  if (resource["error"]) return h("div", {"className": "panel-empty"}, h("p", {"role": "alert"}, resource["error"]), h("button", {"className": "button small", "onClick": resource["reload"]}, "Retry"));
  return props["children"];
}
