"""Reproduce a PostgreSQL dbt baseline → Arrow/Parquet → DuckDB preview.

Requires running local Postgres per reproduce.sh. No remote warehouse access.
"""
import collections
import datetime
import decimal
import hashlib
import importlib.metadata
import json
import pathlib
import shutil
import subprocess
import sys

import duckdb
import psycopg
import pyarrow as pa
import pyarrow.parquet as pq
import sqlglot
from sqlglot import exp
from sqlglot.optimizer.annotate_types import annotate_types
from sqlglot.optimizer.qualify import qualify
from sqlglot.optimizer.scope import traverse_scope

ROOT = pathlib.Path(__file__).resolve().parent
PROJECT = ROOT / "project"
ARTIFACTS = ROOT / "artifacts"
SNAPSHOTS = ARTIFACTS / "snapshots"
STATE = ARTIFACTS / "baseline-state"
for p in (ARTIFACTS, SNAPSHOTS, STATE):
    p.mkdir(parents=True, exist_ok=True)
DBT = str(ROOT / "venv/bin/dbt")
results = {
    "project": "https://github.com/dbt-labs/jaffle-shop-classic",
    "project_commit": "fd7bfacae4f497ff044a6a0275268676bf1b64c3",
    "python": sys.version,
    "postgres_image": "postgres:16-bookworm@sha256:0ea6700a3b4f0ae6ce746519073558aed4d88a79d8d07622a9a644946c7319c4",
    "versions": {name: importlib.metadata.version(name) for name in
                 ["dbt-core", "dbt-postgres", "duckdb", "sqlglot", "pyarrow", "psycopg"]},
    "checks": [], "snapshots": [], "translations": [],
}


def check(name, ok, **evidence):
    entry = {"check": name, "pass": bool(ok), **evidence}
    results["checks"].append(entry)
    print(json.dumps(entry, default=str), flush=True)


def run_dbt(name, args):
    log = ARTIFACTS / f"{name}.log"
    with log.open("w") as fh:
        completed = subprocess.run([DBT, *args, "--project-dir", str(PROJECT),
            "--profiles-dir", str(ROOT), "--no-use-colors"], stdout=fh,
            stderr=subprocess.STDOUT)
    check(name, completed.returncode == 0, returncode=completed.returncode, log=str(log))
    if completed.returncode:
        raise RuntimeError(log.read_text()[-4000:])


def normalized(value):
    if isinstance(value, (int, float, decimal.Decimal)) and not isinstance(value, bool):
        # Compare logical numeric values; record engine types separately.
        return decimal.Decimal(str(value)).normalize().to_eng_string()
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.isoformat()
    return value


def canonical(rows):
    return collections.Counter(json.dumps([normalized(v) for v in row],
        sort_keys=True, default=str) for row in rows)


def query_pg(conn, query):
    cur = conn.execute(query)
    return cur.fetchall(), [col.name for col in cur.description], [col.type_code for col in cur.description]


def export(conn, relation, local_name):
    rows, cols, oids = query_pg(conn, f'SELECT * FROM baseline."{relation}"')
    columns = conn.execute("""SELECT column_name, data_type, numeric_precision,
        numeric_scale FROM information_schema.columns WHERE table_schema = 'baseline'
        AND table_name = %s ORDER BY ordinal_position""", (relation,)).fetchall()
    # Explicit types for null-only columns and DECIMAL precision, rather than inference.
    fields = []
    for name, typ, precision, scale in columns:
        arrow = {
            "integer": pa.int32(), "bigint": pa.int64(), "smallint": pa.int16(),
            "text": pa.string(), "character varying": pa.string(),
            "date": pa.date32(), "boolean": pa.bool_(),
            "timestamp without time zone": pa.timestamp("us"),
            "timestamp with time zone": pa.timestamp("us", tz="UTC"),
            "double precision": pa.float64(), "real": pa.float32(),
        }.get(typ)
        if typ == "numeric":
            arrow = pa.decimal128(precision, scale) if precision is not None else None
        if arrow is None:
            raise ValueError(f"An explicit export policy is required for {name}: {typ}")
        fields.append(pa.field(name, arrow))
    table = pa.Table.from_pylist([dict(zip(cols, row)) for row in rows], schema=pa.schema(fields))
    path = SNAPSHOTS / f"{local_name}.parquet"
    pq.write_table(table, path)
    entry = {"source": f"workbench.baseline.{relation}", "binding": f"snapshots.{local_name}",
        "rows": len(rows), "columns": columns, "arrow_schema": str(table.schema),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "file": str(path),
        "snapshot_transaction": results["snapshot_transaction"]}
    results["snapshots"].append(entry)
    return path


def translate(query, bindings, label, typed_schema=None, qualify_columns=False, integer_lowering=False):
    tree = sqlglot.parse_one(query, read="postgres")
    replacements, ctes = [], []
    # selected_sources distinguishes physical tables from same-named CTEs.
    for scope in traverse_scope(tree):
        for alias, (node, source) in scope.selected_sources.items():
            if isinstance(source, exp.Table):
                key = (source.catalog, source.db, source.name)
                if key not in bindings:
                    raise ValueError(f"Unbound physical relation {key}")
                replacement = exp.to_table(bindings[key])
                # Preserve implicit table alias because column references may use it.
                replacement.set("alias", source.args.get("alias") or exp.TableAlias(this=exp.to_identifier(alias)))
                source.replace(replacement)
                replacements.append({"from": key, "to": bindings[key], "alias": alias})
            else:
                ctes.append(alias)
    if typed_schema is not None:
        if qualify_columns:
            tree = qualify(tree, dialect="postgres", schema=typed_schema)
        tree = annotate_types(tree, schema=typed_schema, dialect="postgres")
    if integer_lowering:
        # Narrow source-semantic compatibility rule, not a general portability claim.
        integer_types = {exp.DataType.Type.SMALLINT, exp.DataType.Type.INT,
                         exp.DataType.Type.BIGINT}
        for division in list(tree.find_all(exp.Div)):
            if (division.args.get("typed") and division.left.type.this in integer_types
                    and division.right.type.this in integer_types):
                division.replace(exp.IntDiv(this=division.left.copy(), expression=division.right.copy()))
    native = tree.sql(dialect="duckdb", unsupported_level=sqlglot.ErrorLevel.RAISE)
    out = ARTIFACTS / f"{label}.duckdb.sql"
    out.write_text(native)
    results["translations"].append({"label": label, "physical_replacements": replacements,
        "preserved_cte_sources": ctes, "sql_file": str(out)})
    return native


run_dbt("native_baseline_build", ["build", "--target", "baseline"])
for filename in ("manifest.json", "run_results.json"):
    shutil.copy2(PROJECT / "target" / filename, STATE / filename)
manifest = json.loads((STATE / "manifest.json").read_text())
results["manifest_sha256"] = hashlib.sha256((STATE / "manifest.json").read_bytes()).hexdigest()
results["dbt_baseline_summary"] = collections.Counter(
    r["status"] for r in json.loads((STATE / "run_results.json").read_text())["results"])

conn = psycopg.connect("host=127.0.0.1 port=55432 dbname=workbench user=workbench password=workbench_local_spike")
results["postgres_version"] = conn.execute("SHOW server_version").fetchone()[0]
conn.commit()
conn.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
results["snapshot_transaction"] = conn.execute("SELECT txid_current_snapshot()::text, CURRENT_TIMESTAMP").fetchone()
duck = duckdb.connect(str(ARTIFACTS / "preview.duckdb"))
duck.execute("CREATE SCHEMA IF NOT EXISTS snapshots")
bindings = {}
relations = ["stg_customers", "stg_orders", "stg_payments", "raw_customers", "raw_orders", "raw_payments"]
for rel in relations:
    path = export(conn, rel, rel)
    duck.execute(f"CREATE OR REPLACE TABLE snapshots.{rel} AS SELECT * FROM read_parquet(?)", [str(path)])
    bindings[("workbench", "baseline", rel)] = f"snapshots.{rel}"

for model in ("orders", "customers"):
    node = manifest["nodes"][f"model.jaffle_shop.{model}"]
    sql = node["compiled_code"]
    (ARTIFACTS / f"{model}.postgres.sql").write_text(sql)
    actual, cols, oids = query_pg(conn, sql)
    translated = translate(sql, bindings, f"boundary-{model}")
    cursor = duck.execute(translated)
    preview = cursor.fetchall()
    check(f"boundary_export_then_translate_{model}", canonical(actual) == canonical(preview),
          native_rows=len(actual), preview_rows=len(preview), columns=cols,
          native_type_oids=oids, preview_types=[str(c[1]) for c in cursor.description],
          null_cells=sum(v is None for row in actual for v in row))

# Probe translating each upstream model from the raw seeds instead of freezing its output.
for model in ("stg_customers", "stg_orders", "stg_payments"):
    node = manifest["nodes"][f"model.jaffle_shop.{model}"]
    sql = node["compiled_code"]
    actual, cols, oids = query_pg(conn, sql)
    translated = translate(sql, bindings, f"raw-seed-{model}")
    cursor = duck.execute(translated)
    preview = cursor.fetchall()
    check(f"raw_seed_translate_{model}", canonical(actual) == canonical(preview),
          native_rows=len(actual), preview_rows=len(preview), native_type_oids=oids,
          preview_types=[str(c[1]) for c in cursor.description])
conn.commit()

# Stronger portability probe: cents, NULL, exact decimals and dates in one typed input.
conn.execute("CREATE TABLE IF NOT EXISTS baseline.bridge_edge (id integer, cents integer, price numeric(18,4), day date)")
conn.execute("TRUNCATE baseline.bridge_edge")
conn.execute("INSERT INTO baseline.bridge_edge VALUES (1, 105, 12.3456, DATE '2026-10-09'), (2, -105, -0.1250, DATE '2026-01-01'), (3, NULL, NULL, NULL), (4, 199, 99.0001, DATE '2026-10-09'), (5, -199, -99.0001, DATE '2026-10-09')")
conn.commit()
conn.execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
results["snapshot_transaction"] = conn.execute("SELECT txid_current_snapshot()::text, CURRENT_TIMESTAMP").fetchone()
path = export(conn, "bridge_edge", "bridge_edge")
duck.execute("CREATE OR REPLACE TABLE snapshots.bridge_edge AS SELECT * FROM read_parquet(?)", [str(path)])
bindings[("workbench", "baseline", "bridge_edge")] = "snapshots.bridge_edge"
edge_query = 'SELECT id, cents / 100 AS whole_dollars, price, day FROM "workbench"."baseline"."bridge_edge"'
actual, cols, oids = query_pg(conn, edge_query)
translated = translate(edge_query, bindings, "edge-untyped")
preview = duck.execute(translated).fetchall()
check("edge_untyped_translation_semantics", canonical(actual) == canonical(preview),
      native_rows=actual, preview_rows=preview, generated_sql=translated,
      expected_note="This should expose whether unknown integer division is preserved")
edge_schema = {"snapshots": {"bridge_edge": {
    "id": "INT", "cents": "INT", "price": "DECIMAL(18, 4)", "day": "DATE"}}}
typed = translate(edge_query, bindings, "edge-typed", edge_schema)
typed_preview = duck.execute(typed).fetchall()
check("edge_typed_translation_semantics", canonical(actual) == canonical(typed_preview),
      native_rows=actual, preview_rows=typed_preview, generated_sql=typed)
qualified = translate(edge_query, bindings, "edge-qualified-typed", edge_schema, qualify_columns=True)
qualified_preview = duck.execute(qualified).fetchall()
check("edge_qualified_typed_translation_semantics", canonical(actual) == canonical(qualified_preview),
      native_rows=actual, preview_rows=qualified_preview, generated_sql=qualified)
fixed = translate(edge_query, bindings, "edge-integer-lowering", edge_schema,
                  qualify_columns=True, integer_lowering=True)
fixed_preview = duck.execute(fixed).fetchall()
check("edge_explicit_integer_lowering_semantics", canonical(actual) == canonical(fixed_preview),
      native_rows=actual, preview_rows=fixed_preview, generated_sql=fixed)
check("edge_snapshot_exact_decimal_and_date", all(a[2:] == b[2:] for a,b in zip(actual, preview)),
      duckdb_types=[str(x[1]) for x in duck.execute('SELECT * FROM snapshots.bridge_edge').description])
conn.commit()

# Native dbt defer verifies metadata binding and the baseline relation remain separate.
run_dbt("native_deferred_dev", ["run", "--target", "dev", "--select", "orders", "customers",
    "--defer", "--favor-state", "--state", str(STATE)])
dev_manifest = json.loads((PROJECT / "target/manifest.json").read_text())
for model in ("orders", "customers"):
    native, _, _ = query_pg(conn, f"SELECT * FROM baseline.{model}")
    dev, _, _ = query_pg(conn, f"SELECT * FROM development.{model}")
    sql = dev_manifest["nodes"][f"model.jaffle_shop.{model}"]["compiled_code"]
    check(f"native_deferred_{model}_matches_baseline", canonical(native) == canonical(dev),
        compiled_uses_baseline='"baseline"' in sql, compiled_sql=sql)
conn.close()
duck.close()
(ARTIFACTS / "results.json").write_text(json.dumps(results, indent=2, default=str))
print("RESULTS", ARTIFACTS / "results.json", flush=True)
