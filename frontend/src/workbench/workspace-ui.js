import React from "react";

const h = React.createElement;

export function clampSize(value, minimum, maximum) {
  return Math.max(minimum, Math.min(maximum, value));
}

export function placePopover(anchor, menu, viewport) {
  const left = clampSize(anchor.right - menu.width, 8, Math.max(8, viewport.width - menu.width - 8));
  const below = anchor.bottom + 6;
  const top = below + menu.height <= viewport.height - 8 ? below : anchor.top - menu.height - 6;
  return {"left": left, "top": clampSize(top, 8, Math.max(8, viewport.height - menu.height - 8))};
}

export function Popover(props) {
  const trigger = React.useRef(null);
  const menu = React.useRef(null);
  const id = React.useId();
  const [open, setOpen] = React.useState(false);
  function positionMenu() {
    const element = menu.current;
    const position = placePopover(trigger.current.getBoundingClientRect(), element.getBoundingClientRect(), {width: window.innerWidth, height: window.innerHeight});
    element.style.left = `${position["left"]}px`;
    element.style.top = `${position["top"]}px`;
  }
  function toggleMenu() {
    if (menu.current.matches(":popover-open")) menu.current.hidePopover();
    else { menu.current.showPopover(); positionMenu(); }
  }
  React.useEffect(function bindPopover() {
    const element = menu.current;
    function updateOpen(event) { setOpen(event["newState"] === "open"); }
    function reposition() { if (element.matches(":popover-open")) positionMenu(); }
    element.addEventListener("toggle", updateOpen);
    window.addEventListener("resize", reposition);
    document.addEventListener("scroll", reposition, true);
    return function unbindPopover() {
      element.removeEventListener("toggle", updateOpen);
      window.removeEventListener("resize", reposition);
      document.removeEventListener("scroll", reposition, true);
    };
  }, []);
  return h(React.Fragment, null,
    h("button", {"ref": trigger, "className": props["className"] || "icon-button", "aria-label": props["label"], "title": props["label"], "aria-haspopup": "dialog", "aria-expanded": open, "aria-controls": id, "onClick": toggleMenu}, props["trigger"] || props["label"]),
    h("div", {"ref": menu, "id": id, "popover": "auto", "role": "dialog", "aria-label": props["label"], "className": `floating-menu ${props["menuClass"] || ""}`}, props["children"]));
}

export function PaneResizer(props) {
  const horizontal = props["axis"] === "x";
  const direction = props["direction"] || 1;
  function commitSize(value) { props["onResize"](clampSize(value, props["min"], props["max"])); }
  function handleKey(event) {
    const decrease = horizontal ? "ArrowLeft" : "ArrowUp";
    const increase = horizontal ? "ArrowRight" : "ArrowDown";
    if (![decrease, increase, "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    if (event.key === "Home") commitSize(props["min"]);
    else if (event.key === "End") commitSize(props["max"]);
    else commitSize(props["value"] + (event.key === increase ? 1 : -1) * direction * props["step"]);
  }
  function startResize(event) {
    event.preventDefault();
    const handle = event.currentTarget;
    const container = handle.closest(props["scope"]);
    const bounds = container.getBoundingClientRect();
    const start = horizontal ? event.clientX : event.clientY;
    const pane = props["pane"] ? container.querySelector(props["pane"]) : null;
    const initial = pane ? pane.getBoundingClientRect().width : props["value"];
    let value = initial;
    function move(pointer) {
      const delta = (horizontal ? pointer.clientX : pointer.clientY) - start;
      const change = props["unit"] === "%" ? delta / bounds.height * 100 : delta;
      value = clampSize(initial + change * direction, props["min"], props["max"]);
      container.style.setProperty(props["variable"], `${value}${props["unit"]}`);
    }
    function finish() {
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", finish);
      handle.removeEventListener("pointercancel", finish);
      commitSize(Math.round(value));
    }
    handle.setPointerCapture(event.pointerId);
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", finish);
    handle.addEventListener("pointercancel", finish);
  }
  return h("div", {"className": `pane-resizer ${horizontal ? "column-resizer" : "row-resizer"} ${props["className"] || ""}`, "role": "separator", "aria-label": props["label"], "aria-orientation": horizontal ? "vertical" : "horizontal", "aria-valuemin": props["min"], "aria-valuemax": props["max"], "aria-valuenow": props["value"], "tabIndex": 0, "onKeyDown": handleKey, "onPointerDown": startResize, "onDoubleClick": () => commitSize(props["default"])});
}
