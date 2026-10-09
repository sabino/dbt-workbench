import React from "react";
import {useResource, ResourceState} from "./panel-utils.js";

const h = React.createElement;
const COLORS = ["var(--accent)", "#79adf4", "#d28ff0", "#e6b85a", "#7ed6d0", "#eb849a", "#a6c778", "#b9aafa"];

export function aggregateChart(rows, xIndex, yIndex, operation) {
  const groups = rows.reduce((result, row) => {
    const label = row[xIndex] === null ? "null" : String(row[xIndex]);
    const value = row[yIndex] === null ? null : Number(row[yIndex]);
    const numeric = value !== null && Number.isFinite(value);
    const previous = result.get(label) || {"label": label, "sum": 0, "count": 0, "rows": 0, "distinct": new Set()};
    result.set(label, {"label": label, "sum": previous["sum"] + (numeric ? value : 0), "count": previous["count"] + (numeric ? 1 : 0),
      "rows": previous["rows"] + 1, "distinct": row[yIndex] === null ? previous["distinct"] : new Set(previous["distinct"]).add(JSON.stringify(row[yIndex]))});
    return result;
  }, new Map());
  return Array.from(groups.values()).map(group => ({"label": group["label"], "value": operation === "count" ? group["rows"] : operation === "distinct" ? group["distinct"].size : operation === "average" ? (group["count"] ? group["sum"] / group["count"] : null) : group["count"] ? group["sum"] : null}));
}

export function aggregateSeries(rows, columns, xIndex, metrics, breakdownIndex) {
  const breakdowns = breakdownIndex === "" ? [null] : Array.from(new Set(rows.map(row => row[Number(breakdownIndex)]))).slice(0, 8);
  return metrics.flatMap((metric, metricIndex) => breakdowns.map((value, groupIndex) => {
    const filtered = breakdownIndex === "" ? rows : rows.filter(row => row[Number(breakdownIndex)] === value);
    const points = aggregateChart(filtered, xIndex, metric["column"], metric["operation"]).slice(0, 40);
    const name = columns[metric["column"]]?.["name"] || "Rows";
    return {"id": `${metricIndex}-${groupIndex}`, "label": `${name}${breakdownIndex === "" ? "" : ` · ${value === null ? "null" : value}`}`, "points": points};
  })).slice(0, 8);
}

function renderColumnOptions(columns) {
  return columns.map((column, index) => h("option", {"key": index, "value": index}, column["name"]));
}

function SeriesPlot(props) {
  const series = props["series"];
  const labels = Array.from(new Set(series.flatMap(item => item["points"].map(point => point["label"])))).slice(0, 40);
  const values = series.flatMap(item => item["points"].map(point => point["value"])).filter(value => value !== null);
  if (!labels.length || !values.length) return h("p", {"className": "panel-empty"}, "Choose a numeric metric, or Count to chart rows.");
  const min = Math.min(0, ...values);
  const max = Math.max(1, ...values);
  const px = index => 48 + (index + .5) * 612 / labels.length;
  const py = value => 215 - (value - min) * 185 / (max - min);
  const bars = props["kind"] === "bar";
  const seriesWidth = 490 / labels.length;
  return h("svg", {"viewBox": "0 0 700 255", "role": "img", "aria-label": `${props["kind"]} chart with ${series.length} series`},
    [min, (min + max) / 2, max].map(value => h("g", {"key": value}, h("line", {"x1": 48, "x2": 665, "y1": py(value), "y2": py(value), "className": "chart-grid"}), h("text", {"x": 40, "y": py(value) + 4, "textAnchor": "end", "className": "chart-label"}, value.toLocaleString(undefined, {"maximumFractionDigits": 2})))),
    series.map((item, seriesIndex) => {
      const indexed = new Map(item["points"].map(point => [point["label"], point["value"]]));
      const plotted = labels.map((label, index) => ({"label": label, "index": index, "value": indexed.get(label) ?? null})).filter(point => point["value"] !== null);
      const color = COLORS[seriesIndex];
      const segments = labels.reduce((parts, label, index) => {
        if (indexed.get(label) === null || indexed.get(label) === undefined) return [...parts, []];
        const previous = parts[parts.length - 1];
        return [...parts.slice(0, -1), [...previous, `${px(index)},${py(indexed.get(label))}`]];
      }, [[]]).filter(part => part.length);
      return h("g", {"key": item["id"]},
        !bars ? segments.map((points, index) => h("polyline", {"key": index, "points": points.join(" "), "fill": "none", "stroke": color, "strokeWidth": 2})) : null,
        plotted.map(point => h("g", {"key": point["label"]}, h("title", null, `${item["label"]} · ${point["label"]}: ${point["value"]}`),
          bars ? h("rect", {"x": px(point["index"]) - seriesWidth / 2 + seriesIndex * seriesWidth / series.length, "y": Math.min(py(point["value"]), py(0)), "width": Math.max(1, seriesWidth / series.length - 2), "height": Math.max(1, Math.abs(py(0) - py(point["value"]))), "fill": color}) :
            props["showPoints"] ? h("circle", {"cx": px(point["index"]), "cy": py(point["value"]), "r": 3, "fill": color}) : null)));
    }),
    labels.map((label, index) => index % Math.max(1, Math.ceil(labels.length / 6)) === 0 ? h("text", {"key": label, "x": px(index), "y": 239, "textAnchor": "middle", "className": "chart-label"}, label.slice(0, 12)) : null));
}

export function ResultChart(props) {
  const [x, setX] = React.useState(0);
  const [metrics, setMetrics] = React.useState([{"column": 1, "operation": "sum"}]);
  const [kind, setKind] = React.useState("bar");
  const [breakdown, setBreakdown] = React.useState("");
  const [legend, setLegend] = React.useState(true);
  const [points, setPoints] = React.useState(true);
  if (!props["result"]) return h("p", {"className": "panel-empty"}, "Run a query to chart its saved result.");
  return h(ChartData, {"result": props["result"], "x": x, "metrics": metrics, "kind": kind, "breakdown": breakdown, "legend": legend, "points": points,
    "setX": setX, "setMetrics": setMetrics, "setKind": setKind, "setBreakdown": setBreakdown, "setLegend": setLegend, "setPoints": setPoints});
}

function ChartData(props) {
  const result = props["result"];
  const resource = useResource(`/api/results/${result["id"]}/rows?limit=1000`);
  const page = resource["data"];
  const columns = page?.["columns"] || [];
  const x = Math.min(props["x"], Math.max(0, columns.length - 1));
  const metrics = props["metrics"].map(metric => ({...metric, "column": Math.min(metric["column"], Math.max(0, columns.length - 1))}));
  const breakdown = Number(props["breakdown"]) < columns.length ? props["breakdown"] : "";
  const series = aggregateSeries(page?.["rows"] || [], columns, x, metrics, breakdown);
  function updateMetric(index, change) { props["setMetrics"](metrics.map((metric, position) => position === index ? {...metric, ...change} : metric)); }
  return h(ResourceState, {"resource": resource}, h("div", {"className": "result-chart"},
    h("div", {"className": "chart-controls"},
      h("label", null, "Chart type", h("select", {"aria-label": "Chart type", "value": props["kind"], "onChange": event => props["setKind"](event.target.value)}, h("option", {"value": "bar"}, "Bar"), h("option", {"value": "line"}, "Line"))),
      h("label", null, "X axis", h("select", {"aria-label": "Chart X axis", "value": x, "onChange": event => props["setX"](Number(event.target.value))}, renderColumnOptions(columns))),
      h("label", null, "Break down by", h("select", {"aria-label": "Chart breakdown", "value": breakdown, "onChange": event => props["setBreakdown"](event.target.value)}, h("option", {"value": ""}, "No breakdown"), renderColumnOptions(columns)))),
    h("div", {"className": "chart-series-controls"}, metrics.map((metric, index) => h("div", {"className": "chart-metric", "key": index},
      h("label", null, `Y axis ${index + 1}`, h("select", {"aria-label": index === 0 ? "Chart Y axis" : `Chart Y axis ${index + 1}`, "value": metric["column"], "onChange": event => updateMetric(index, {"column": Number(event.target.value)})}, renderColumnOptions(columns))),
      h("label", null, "Aggregate", h("select", {"aria-label": index === 0 ? "Chart aggregation" : `Chart aggregation ${index + 1}`, "value": metric["operation"], "onChange": event => updateMetric(index, {"operation": event.target.value})},
        [["sum", "Sum"], ["average", "Average"], ["count", "Count rows"], ["distinct", "Count distinct"]].map(([value, name]) => h("option", {"key": value, "value": value}, name)))),
      metrics.length > 1 ? h("button", {"className": "button small", "aria-label": `Remove chart series ${index + 1}`, "onClick": () => props["setMetrics"](metrics.filter((_, position) => position !== index))}, "Remove") : null)),
      h("button", {"className": "button small", "disabled": metrics.length >= 4, "onClick": () => props["setMetrics"]([...metrics, {"column": 0, "operation": "count"}])}, "+ Add series")),
    h("div", {"className": "panel-toolbar"}, h("label", {"className": "check-control"}, h("input", {"type": "checkbox", "checked": props["legend"], "onChange": event => props["setLegend"](event.target.checked)}), "Show legend"),
      h("label", {"className": "check-control"}, h("input", {"type": "checkbox", "checked": props["points"], "onChange": event => props["setPoints"](event.target.checked)}), "Show data points")),
    h(SeriesPlot, {"series": series, "kind": props["kind"], "showPoints": props["points"]}),
    props["legend"] ? h("div", {"className": "chart-legend"}, series.map((item, index) => h("span", {"key": item["id"]}, h("i", {"style": {"backgroundColor": COLORS[index]}}), item["label"]))) : null,
    h("p", {"className": "hint"}, `Chart uses ${page?.["rows"].length || 0} of ${result["row_count"]} saved rows; up to 40 groups and 8 series. Sum and Average exclude null and nonnumeric values.`),
    h("details", null, h("summary", null, "Chart data"), h("div", {"className": "grid-wrap"}, h("table", {"className": "result-grid"}, h("thead", null, h("tr", null, h("th", null, "Series"), h("th", null, columns[x]?.["name"]), h("th", null, "Value"))),
      h("tbody", null, series.flatMap(item => item["points"].map(point => h("tr", {"key": `${item["id"]}-${point["label"]}`}, h("td", null, item["label"]), h("td", null, point["label"]), h("td", null, point["value"] === null ? "null" : String(point["value"])))))))))));
}
