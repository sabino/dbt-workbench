import json
from pathlib import Path
import threading

import duckdb
import pytest
import yaml

from dbt_workbench.query import QueryCancelled, QueryEngine, QueryError


class Workspaces:
    def __init__(self, root):
        self.root = root
        self.current = self._manifest("dev")
        (root / "dbt_project.yml").write_text("name: shop\nprofile: shop\n")
        (root / "profiles.yml").write_text(yaml.safe_dump({"shop": {"target": "dev", "outputs": {
            "dev": {"type": "duckdb", "path": str(root / "dev.duckdb"), "attach": [
                {"path": str(root / "baseline.duckdb"), "alias": "baseline", "read_only": True}]},
            "baseline": {"type": "duckdb", "path": str(root / "baseline.duckdb")}}}}))
        for target, rows in [("dev", [(1, 10.0, "completed", 2), (2, 20.0, "pending", 0), (3, None, "completed", -2)]),
                             ("baseline", [(1, 5.0, "completed", 2)])]:
            con = duckdb.connect(str(root / f"{target}.duckdb"))
            con.execute("CREATE TABLE orders(id INTEGER, amount DOUBLE, status VARCHAR, qty INTEGER)")
            con.executemany("INSERT INTO orders VALUES (?,?,?,?)", rows)
            con.close()
        self.baseline_path = root / "manifest.json"
        self.baseline_path.write_text(json.dumps(self._manifest("baseline")))

    @staticmethod
    def _manifest(target):
        return {"nodes": {"model.shop.orders": {"unique_id": "model.shop.orders", "resource_type": "model", "name": "orders",
                "alias": "orders", "schema": "main", "database": target, "config": {"materialized": "table"},
                "compiled_code": f'SELECT * FROM "{target}"."main"."orders"'}}}

    def get_project(self, project_id):
        return {"path": str(self.root), "profiles_dir": str(self.root)}

    def manifest(self, project_id):
        return self.current

    def baseline(self, project_id):
        return {"target": "baseline", "manifest_path": str(self.baseline_path)}


@pytest.fixture
def engine(tmp_path):
    return QueryEngine(Workspaces(tmp_path))


def run(engine, **options):
    return engine.query("project", options, threading.Event(), lambda *a, **k: None)


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
def test_preview_native_and_actual_sqlite(engine, dialect):
    result = engine.preview("project", {"model_id": "model.shop.orders", "dialect": dialect}, threading.Event(), lambda *a: None)
    assert result["table"].num_rows == 3
    assert result["dialect"] == dialect
    assert result["provenance"]["native_sql"]
    assert result["provenance"]["translated_sql"] == result["sql"]
    if dialect == "sqlite":
        assert result["provenance"]["captured_relations"][0]["rows"] == 3
        assert "__wb_capture_" in result["sql"]


def test_preview_saved_baseline(engine):
    result = engine.preview("project", {"model_id": "orders", "baseline": True}, threading.Event(), lambda *a: None)
    assert result["table"].to_pylist()[0]["amount"] == 5.0
    assert result["provenance"]["target"] == "baseline"


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
def test_edn_parameters_grouping(engine, dialect):
    content = '''{:query/version 1 :from {:model "model.shop.orders" :as "o"}
     :select [{:as "status" :expr [:field "o" "status"]}
              {:as "revenue" :expr [:sum [:field "o" "amount"]]}]
     :where [:eq [:field "o" "status"] [:param "status" :text]]
     :group-by [[:field "o" "status"]] :order-by [[:field "o" "status"]]}'''
    result = run(engine, content=content, format="edn", dialect=dialect, parameters={"status": "completed"})
    assert result["table"].to_pylist() == [{"status": "completed", "revenue": 10.0}]
    assert result["provenance"]["bindings"][0]["binding"] == "dev"


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
def test_edn_division_contract(engine, dialect):
    content = '''{:query/version 1 :from {:model "orders" :as "o"}
      :select [{:as "id" :expr [:field "o" "id"]}
               {:as "ratio" :expr [:div 3 [:field "o" "qty"]]}]
      :order-by [[:field "o" "id"]]}'''
    result = run(engine, content=content, format="edn", dialect=dialect)
    assert result["table"].to_pylist() == [{"id": 1, "ratio": 1.5}, {"id": 2, "ratio": None}, {"id": 3, "ratio": -1.5}]


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
def test_edn_explicit_baseline_binding(engine, dialect):
    content = '''{:query/version 1 :from {:model "orders" :as "o" :binding :baseline}
      :select [{:as "amount" :expr [:field "o" "amount"]}]}'''
    result = run(engine, content=content, format="edn", dialect=dialect)
    assert result["table"].to_pylist() == [{"amount": 5.0}]
    assert result["provenance"]["bindings"][0]["binding"] == "baseline"


def test_scope_aware_cte_shadows_model(engine):
    result = run(engine, content="WITH orders AS (SELECT 42 AS answer) SELECT answer FROM orders")
    assert result["table"].to_pylist() == [{"answer": 42}]
    assert result["provenance"]["bindings"] == []


def test_scope_aware_physical_model_in_cte(engine):
    result = run(engine, content="WITH chosen AS (SELECT id FROM orders WHERE id = 2) SELECT id FROM chosen", dialect="sqlite")
    assert result["table"].to_pylist() == [{"id": 2}]
    assert len(result["provenance"]["captured_relations"]) == 1


def test_sqlite_self_join_captures_relation_once(engine):
    result = run(engine, content="SELECT a.id, b.amount FROM orders a JOIN orders b ON a.id = b.id ORDER BY a.id", dialect="sqlite")
    assert result["table"].to_pylist() == [{"id": 1, "amount": 10.0}, {"id": 2, "amount": 20.0}, {"id": 3, "amount": None}]
    assert len(result["provenance"]["captured_relations"]) == 1


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
def test_named_parameter_is_data(engine, dialect):
    payload = "O'Reilly ☃; DROP TABLE orders; --"
    result = run(engine, content="SELECT $value AS value, $value AS again", parameters={"value": payload}, dialect=dialect)
    assert result["table"].to_pylist() == [{"value": payload, "again": payload}]
    assert run(engine, content="SELECT COUNT(*) AS n FROM orders")["table"].to_pylist() == [{"n": 3}]


@pytest.mark.parametrize("sql", [
    "DELETE FROM orders", "SELECT 1; DROP TABLE orders", "COPY orders TO '/tmp/unsafe.csv'",
    "SELECT * FROM read_csv('/etc/passwd')", "SELECT * FROM range(100)",
    "ATTACH '/tmp/external.db' AS external", "SELECT * INTO new_table FROM orders",
    "SELECT getenv('HOME')", "SELECT * FROM {{ ref('orders') }}",
    "SELECT * FROM '/tmp/a.parquet'", 'SELECT * FROM "orders.csv"',
])
def test_closed_read_only_sql(engine, sql):
    with pytest.raises(QueryError):
        run(engine, content=sql)
    assert run(engine, content="SELECT COUNT(*) AS n FROM orders")["table"].to_pylist() == [{"n": 3}]


@pytest.mark.parametrize("sql, message", [
    ("SELECT 3 / 2", "division"), ("SELECT TRY_CAST('abc' AS INTEGER)", "TryCast"),
    ("SELECT DATE_TRUNC('month', DATE '2024-01-01')", "DateTrunc"),
])
def test_uncertified_translation_rejected(engine, sql, message):
    with pytest.raises(QueryError, match=message):
        run(engine, content=sql, dialect="sqlite")


def test_result_bound(engine):
    result = run(engine, content="SELECT * FROM orders ORDER BY id", max_rows=2)
    assert result["table"].num_rows == 2
    assert result["provenance"]["truncated"] is True


@pytest.mark.parametrize("content, parameters, message", [
    ('{:query/version 2}', {}, "version"),
    ('{:query/version 1 :from {:model "orders"} :select [{:as "x" :expr [:unknown 1]}]}', {}, "operator"),
    ('{:query/version 1 :from {:model "orders"} :select [{:as "x" :expr [:param "x" :integer]}]}', {"x": "3"}, "type"),
    ('{:query/version 1 :from {:model "orders"} :select [{:as "x" :expr [:param "x" :text]}]}', {}, "missing"),
])
def test_invalid_edn_contract(engine, content, parameters, message):
    with pytest.raises(QueryError, match=message):
        run(engine, content=content, format="edn", parameters=parameters)


def test_repeated_parameter_conflicting_types(engine):
    content = '''{:query/version 1 :from {:model "orders"} :select [
        {:as "a" :expr [:param "x" :text]} {:as "b" :expr [:param "x" :integer]}]}'''
    with pytest.raises(QueryError, match="conflicting"):
        run(engine, content=content, format="edn", parameters={"x": 3})


def test_native_query_can_cancel_and_reconnect(engine):
    cancel = threading.Event()
    timer = threading.Timer(0.15, cancel.set)
    timer.start()
    try:
        with pytest.raises(QueryCancelled):
            engine.query("project", {"content": "WITH RECURSIVE t(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM t WHERE x < 1000000000) SELECT SUM(x) FROM t"}, cancel, lambda *a: None)
    finally:
        timer.cancel()
    assert run(engine, content="SELECT 42 AS n")["table"].to_pylist() == [{"n": 42}]


def test_native_timeout(engine):
    with pytest.raises(QueryError, match="time limit"):
        run(engine, content="WITH RECURSIVE t(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM t WHERE x < 1000000000) SELECT SUM(x) FROM t", timeout_seconds=0.05)


def test_sqlite_rejects_decimal_instead_of_lexical_numeric_predicate(engine):
    con = duckdb.connect(str(engine.workspaces.root / "dev.duckdb"))
    con.execute("CREATE TABLE money(value DECIMAL(12, 2))")
    con.execute("INSERT INTO money VALUES (2), (12)")
    con.close()
    assert run(engine, content="SELECT value FROM money WHERE value > 10")["table"].num_rows == 1
    with pytest.raises(QueryError, match="DECIMAL semantics.*DuckDB"):
        run(engine, content="SELECT value FROM money WHERE value > 10", dialect="sqlite")


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
@pytest.mark.parametrize("sql", [
    "SELECT main.orders.id FROM main.orders ORDER BY main.orders.id",
    'SELECT "MAIN"."ORDERS"."id" FROM "MAIN"."ORDERS" ORDER BY "MAIN"."ORDERS"."id"',
    "SELECT main.orders.id, (SELECT MAX(x.id) FROM main.orders x WHERE x.id = main.orders.id) AS n FROM main.orders ORDER BY main.orders.id",
])
def test_qualified_columns_follow_scoped_relation_rewrite(engine, dialect, sql):
    result = run(engine, content=sql, dialect=dialect)
    rows = result["table"].to_pylist()
    assert [row["id"] for row in rows] == [1, 2, 3]
    if "MAX" in sql:
        assert [row["n"] for row in rows] == [1, 2, 3]


@pytest.mark.parametrize("sql", ["SELECT * FROM ORDERS", "SELECT * FROM MAIN.ORDERS"])
def test_case_insensitive_relation_still_uses_baseline(engine, sql):
    result = run(engine, content=sql, baseline=True)
    assert result["table"].num_rows == 1
    assert result["table"].to_pylist()[0]["amount"] == 5.0
    assert result["provenance"]["bindings"][0]["binding"] == "baseline"


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
def test_profile_default_schema_applies_to_native_relations(engine, dialect):
    root = engine.workspaces.root
    con = duckdb.connect(str(root / "dev.duckdb"))
    con.execute("CREATE SCHEMA analytics")
    con.execute("CREATE TABLE analytics.raw_input AS SELECT 7 AS id")
    con.close()
    profiles = yaml.safe_load((root / "profiles.yml").read_text())
    profiles["shop"]["outputs"]["dev"]["schema"] = "analytics"
    (root / "profiles.yml").write_text(yaml.safe_dump(profiles))
    result = run(engine, content="SELECT raw_input.id FROM raw_input", dialect=dialect)
    assert result["table"].to_pylist() == [{"id": 7}]


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
@pytest.mark.parametrize("sql", [
    "WITH orders AS (SELECT 99 AS id) SELECT * FROM ORDERS",
    "WITH ORDERS AS (SELECT 99 AS id) SELECT * FROM orders",
    'WITH "Orders" AS (SELECT 99 AS id) SELECT * FROM "ORDERS"',
    "WITH ORDERS AS (SELECT 99 AS id), x AS (SELECT * FROM orders) SELECT * FROM x",
    "WITH orders AS (SELECT 99 AS id) SELECT (SELECT MAX(id) FROM ORDERS) AS id",
    "WITH orders AS (SELECT 99 AS id) SELECT O.id FROM ORDERS o",
])
def test_case_variant_cte_never_becomes_a_physical_model(engine, dialect, sql):
    result = run(engine, content=sql, dialect=dialect, baseline=True)
    assert result["table"].to_pylist() == [{"id": 99}]
    assert result["provenance"]["bindings"] == []
    assert result["provenance"]["captured_relations"] == []


@pytest.mark.parametrize("dialect", ["duckdb", "sqlite"])
def test_case_normalized_scope_keeps_quoted_result_labels(engine, dialect):
    result = run(engine, content='WITH "Orders" AS (SELECT 99 AS "MiXeD") SELECT O."MiXeD" AS "ResultLabel" FROM "ORDERS" o', dialect=dialect)
    assert result["table"].to_pylist() == [{"ResultLabel": 99}]
    assert '"MiXeD"' in result["sql"]
    assert '"ResultLabel"' in result["sql"]


def test_schema_qualified_relation_stays_physical_when_cte_shadows_name(engine):
    result = run(engine, content="WITH ORDERS AS (SELECT 99 AS id) SELECT id FROM main.orders ORDER BY id")
    assert result["table"].to_pylist() == [{"id": 1}, {"id": 2}, {"id": 3}]
    assert result["provenance"]["bindings"][0]["binding"] == "dev"
