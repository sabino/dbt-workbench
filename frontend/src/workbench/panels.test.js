import {test, expect} from "bun:test";
import {aggregateChart, layoutLineage} from "./panels.js";
import {readSavedQueries} from "./query-library.js";
import {aggregateSeries} from "./charts.js";

test("chart averages exclude NULLs and invalid numbers while row counts retain them", () => {
  const rows = [["a", 4], ["a", null], ["a", "6"], ["b", "not numeric"]];
  expect(aggregateChart(rows, 0, 1, "average")).toEqual([{label: "a", value: 5}, {label: "b", value: null}]);
  expect(aggregateChart(rows, 0, 1, "count")).toEqual([{label: "a", value: 3}, {label: "b", value: 1}]);
});

test("lineage positions dependencies before their consumers and terminates on cycles", () => {
  const nodes = [{unique_id: "source"}, {unique_id: "stage"}, {unique_id: "mart"}];
  const edges = [{source: "source", target: "stage"}, {source: "stage", target: "mart"}];
  const layout = layoutLineage(nodes, edges);
  expect(layout[0].x).toBeLessThan(layout[1].x);
  expect(layout[1].x).toBeLessThan(layout[2].x);
  expect(layoutLineage(nodes, [...edges, {source: "mart", target: "source"}]).every(node => Number.isFinite(node.x))).toBe(true);
});

test("query library ignores corrupt browser data and accepts saved formats", () => {
  expect(readSavedQueries({getItem: () => "not JSON"}, "project")).toEqual([]);
  expect(readSavedQueries({getItem: () => JSON.stringify([{id: "q", name: "Revenue", content: "SELECT 1", format: "sql"}, {id: "bad", name: "invalid"}])}, "project")).toHaveLength(1);
});

test("chart series retain metric aggregates and breakdowns without counting NULL as distinct", () => {
  const rows = [["Jan", "US", 2, 100], ["Jan", "US", null, 100], ["Jan", "CA", 4, 300], ["Feb", "US", 3, 200]];
  const columns = [{name: "month"}, {name: "country"}, {name: "orders"}, {name: "revenue"}];
  const series = aggregateSeries(rows, columns, 0, [{column: 2, operation: "sum"}, {column: 3, operation: "distinct"}], "1");
  expect(series.map(item => item.label)).toEqual(["orders · US", "orders · CA", "revenue · US", "revenue · CA"]);
  expect(series[0].points).toEqual([{label: "Jan", value: 2}, {label: "Feb", value: 3}]);
  expect(series[2].points).toEqual([{label: "Jan", value: 1}, {label: "Feb", value: 1}]);
  expect(aggregateChart([["a", null], ["a", null], ["a", "x"], ["a", "x"]], 0, 1, "distinct")).toEqual([{label: "a", value: 1}]);
});
