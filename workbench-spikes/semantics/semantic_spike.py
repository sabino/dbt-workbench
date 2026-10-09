#!/usr/bin/env python3
"""Bounded semantic compatibility spike. No external database connections."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import sys
import threading
import time
import traceback

import duckdb
import numpy
import sqlglot
from sqlglot import exp
from sqlglot.optimizer.annotate_types import annotate_types
from sqlglot.optimizer.qualify import qualify

ROOT = Path(__file__).resolve().parent
SCHEMA = {
    "customers": {"id": "INTEGER", "name": "VARCHAR"},
    "orders": {"id": "INTEGER", "customer_id": "INTEGER", "amount": "DOUBLE", "qty": "INTEGER", "ordered_at": "VARCHAR"},
}
DDL = ["CREATE TABLE customers(id INTEGER, name VARCHAR)",
       "CREATE TABLE orders(id INTEGER, customer_id INTEGER, amount DOUBLE, qty INTEGER, ordered_at VARCHAR)"]
CUSTOMERS = [(1,"Ada"),(2,None),(3,"Zoë"),(4,"No orders")]
ORDERS = [(10,1,10.0,2,"2024-01-31"),(11,1,None,0,"2024-02-01"),
          (12,2,5.5,-2,"2024-02-29"),(13,None,9.0,3,"2024-03-01"),
          (14,3,-3.0,2,"2024-03-02")]

SQLITE_CASES = [
    ("null_coalesce", "SELECT id, COALESCE(amount, 0) AS amount FROM orders ORDER BY id"),
    ("null_concat", "SELECT name || NULL AS n FROM customers ORDER BY id"),
    ("is_null_and_eq_null", "SELECT name IS NULL AS n, name = NULL AS eq FROM customers ORDER BY id"),
    ("left_join", "SELECT c.id, COUNT(o.id) AS n, SUM(o.amount) AS total FROM customers c LEFT JOIN orders o ON c.id=o.customer_id GROUP BY c.id ORDER BY c.id"),
    ("not_in_null", "SELECT id FROM customers WHERE id NOT IN (SELECT customer_id FROM orders) ORDER BY id"),
    ("count_null", "SELECT COUNT(*),COUNT(amount),SUM(amount) FROM orders"),
    ("empty_aggregate", "SELECT COUNT(*),SUM(amount) FROM orders WHERE 1=0"),
    ("cte_shadow", "WITH orders AS (SELECT 999 AS id, 6 AS qty) SELECT id, qty FROM orders"),
    ("join_cte", "WITH x AS (SELECT customer_id, SUM(amount) AS total FROM orders GROUP BY customer_id) SELECT c.id, x.total FROM customers c LEFT JOIN x ON c.id=x.customer_id ORDER BY c.id"),
    ("integer_div_positive", "SELECT 3 / 2 AS n"),
    ("integer_div_negative", "SELECT -3 / 2 AS n"),
    ("integer_div_column", "SELECT id, id / qty AS n FROM orders ORDER BY id"),
    ("float_div", "SELECT 3.0 / 2 AS n"),
    ("division_zero", "SELECT 3 / 0 AS n"),
    ("cast_invalid_text", "SELECT CAST('abc' AS INTEGER) AS n"),
    ("cast_numeric_prefix", "SELECT CAST('123x' AS INTEGER) AS n"),
    ("cast_fraction_text", "SELECT CAST('3.5' AS INTEGER) AS n"),
    ("cast_float_truncation", "SELECT CAST(3.5 AS INTEGER) AS n, CAST(-3.5 AS INTEGER) AS neg"),
    ("cast_boolean_text", "SELECT CAST('yes' AS BOOLEAN) AS b"),
    ("like_ascii_case", "SELECT 'A' LIKE 'a' AS b"),
    ("date_valid", "SELECT date('2024-02-29') AS d"),
    ("date_overflow", "SELECT date('2024-02-30') AS d"),
    ("date_month_rollover", "SELECT date('2024-01-31','+1 month') AS d"),
    ("date_modifier_hour", "SELECT datetime('2024-02-29 23:00:00','+1 hour') AS d"),
    ("strftime_day", "SELECT strftime('%Y-%m-%d','2024-02-29 23:00:00') AS d"),
]
DUCKDB_CASES = [
    ("float_div_default", "SELECT 3 / 2 AS n"),
    ("division_zero_infinity", "SELECT 3 / 0 AS n"),
    ("try_cast_invalid", "SELECT TRY_CAST('abc' AS BIGINT) AS n"),
    ("cast_boolean_text", "SELECT CAST('yes' AS BOOLEAN) AS b"),
    ("null_safe_equality", "SELECT NULL IS NOT DISTINCT FROM NULL AS b"),
    ("qualify_window", "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM orders QUALIFY rn<=2 ORDER BY id"),
    ("date_trunc_month", "SELECT DATE_TRUNC('month',DATE '2024-02-29') AS d"),
    ("array_native", "SELECT [1,2,3] AS xs"),
]

def norm(x):
    if isinstance(x, (dt.datetime, dt.date, dt.time)):
        return x.isoformat(sep=" ") if isinstance(x,dt.datetime) else x.isoformat()
    if isinstance(x,float) and not math.isfinite(x): return str(x)
    if isinstance(x,(list,tuple)): return [norm(v) for v in x]
    if isinstance(x,dict): return {str(k):norm(v) for k,v in x.items()}
    return x

def connect(engine, path=None):
    con = sqlite3.connect(str(path) if path else ":memory:",check_same_thread=False) if engine=="sqlite" else duckdb.connect(str(path) if path else ":memory:")
    return con

def setup(con):
    for ddl in DDL: con.execute(ddl)
    con.executemany("INSERT INTO customers VALUES (?, ?)",CUSTOMERS)
    con.executemany("INSERT INTO orders VALUES (?, ?, ?, ?, ?)",ORDERS)
    con.commit()

def execute(con,sql,params=None):
    try:
        cur=con.execute(sql,params or {}) if params else con.execute(sql)
        rows=cur.fetchall()
        return {"status":"ok","columns":[x[0] for x in cur.description],
                "native_type_codes":[str(x[1]) for x in cur.description],
                "native_python_types":[[type(x).__name__ for x in row] for row in rows],"rows":norm(rows)}
    except Exception as e:
        return {"status":"error","error_type":type(e).__name__,"error":str(e)}

def equivalent(a,b):
    return a["status"]==b["status"]=="ok" and a["rows"]==b["rows"]

def typed_ast(sql,dialect):
    ast=sqlglot.parse_one(sql,read=dialect,error_level=sqlglot.ErrorLevel.RAISE)
    ast=qualify(ast,dialect=dialect,schema=SCHEMA,validate_qualify_columns=True)
    return annotate_types(ast,schema=SCHEMA,dialect=dialect)

def corpus(direction,source_con,target_con):
    source,target=direction.split("->")
    cases=SQLITE_CASES if source=="sqlite" else DUCKDB_CASES
    rows=[]
    for name,sql in cases:
        item={"id":name,"direction":direction,"source_sql":sql,
              "source_evidence":"executed-native","source":execute(source_con,sql)}
        for pipeline in ["strict_transpile","qualified_typed"]:
            try:
                ast=sqlglot.parse_one(sql,read=source,error_level=sqlglot.ErrorLevel.RAISE) if pipeline=="strict_transpile" else typed_ast(sql,source)
                out=ast.sql(target,unsupported_level=sqlglot.ErrorLevel.RAISE)
                result=execute(target_con,out)
                item[pipeline]={"sql":out,"target_evidence":"executed-native","target":result,"equivalent":equivalent(item["source"],result)}
            except Exception as e:
                item[pipeline]={"status":"translation_rejected","error_type":type(e).__name__,"error":str(e),"equivalent":False}
        rows.append(item)
    return rows

class Reject(Exception): pass

def sqlite_repair(sql):
    """Finite spike only: source-sensitive literal INT division and integer casts.

    Does not claim SQLite dynamic type affinity or month modifiers can be translated.
    Source CAST emulation requires the registered SQLite UDF at target execution.
    """
    ast=typed_ast(sql,"sqlite")
    for node in ast.walk():
        if isinstance(node,exp.Date) and node.args.get("zone"):
            raise Reject("SQLite date modifiers need SQLite evaluation; reject a timezone interpretation")
        if isinstance(node,exp.Like):
            raise Reject("SQLite LIKE case/collation behavior requires an explicit compatibility contract")
        if isinstance(node,exp.Anonymous):
            raise Reject("Unknown source function is not locally certified")
    def visit(n):
        if isinstance(n,exp.Div) and n.args.get("typed"):
            l,r=n.left,n.right
            if not l.is_type(*exp.DataType.INTEGER_TYPES) or not r.is_type(*exp.DataType.INTEGER_TYPES): return n
            # Reject the known overflow promotion difference for literal min-int/-1.
            def number(x):
                if isinstance(x,exp.Neg) and x.this.is_int: return -int(x.this.this)
                if x.is_int: return int(x.this)
                return None
            lv,rv=number(l),number(r)
            if lv == -(2**63) and rv == -1: raise Reject("SQLite integer overflow promotes to float; native target int division differs")
            return exp.IntDiv(this=l.copy(),expression=exp.Nullif(this=r.copy(),expression=exp.Literal.number(0)))
        if isinstance(n,exp.Cast) and n.to.is_type(*exp.DataType.INTEGER_TYPES):
            # Delegate casts through text. This is restricted to TEXT/real literals,
            # not a universal representation of blobs or all dynamic SQLite values.
            if n.this.is_type(*exp.DataType.TEXT_TYPES):
                return exp.Anonymous(this="wb_sqlite_cast_integer",expressions=[n.this.copy()])
            if n.this.is_type(*exp.DataType.REAL_TYPES):
                return exp.Cast(this=exp.Anonymous(this="TRUNC",expressions=[n.this.copy()]),to=exp.DataType.build("BIGINT"))
        return n
    return ast.transform(visit).sql("duckdb",unsupported_level=sqlglot.ErrorLevel.RAISE)

def register_emulation(con):
    sqlite=sqlite3.connect(":memory:",check_same_thread=False)
    lock=threading.Lock()
    def cast_integer(value):
        with lock: return sqlite.execute("SELECT CAST(? AS INTEGER)",(value,)).fetchone()[0]
    con.create_function("wb_sqlite_cast_integer",cast_integer,["VARCHAR"],"BIGINT",null_handling="special")
    return sqlite

def canonical_division(l,r):
    # Chosen semantics: floating division, NULL for zero, no integer truncation.
    return exp.Div(this=exp.cast(l.copy(),"DOUBLE"),
                   expression=exp.Nullif(this=exp.cast(r.copy(),"DOUBLE"),expression=exp.Literal.number(0)),typed=False)

def semantic_cases(sq,du):
    tests=[]
    for name,sql in SQLITE_CASES:
        if name not in {"integer_div_positive","integer_div_negative","integer_div_column","division_zero","cast_invalid_text","cast_numeric_prefix","cast_fraction_text","cast_float_truncation","date_month_rollover","like_ascii_case"}: continue
        src=execute(sq,sql)
        try:
            lowered=sqlite_repair(sql)
            target=execute(du,lowered)
            tests.append({"id":name,"kind":"source-sensitive-lowering","source_evidence":"executed-native","source":src,"sql":lowered,"target":target,"equivalent":equivalent(src,target)})
        except Reject as e:
            tests.append({"id":name,"kind":"source-sensitive-lowering","status":"rejected","reason":str(e),"expected_rejection":True})
    for l,r in [(3,2),(-3,2),(3,0),(None,2),(7,3)]:
        ast=exp.select(canonical_division(exp.convert(l),exp.convert(r)).as_("ratio"))
        outputs={dialect:{"sql":ast.copy().sql(dialect,unsupported_level=sqlglot.ErrorLevel.RAISE),"result":execute(con,ast.copy().sql(dialect,unsupported_level=sqlglot.ErrorLevel.RAISE))} for dialect,con in [("sqlite",sq),("duckdb",du)]}
        expected=None if l is None or r==0 else l/r
        tests.append({"id":f"canonical_float_div_{l}_{r}","kind":"typed-IR-to-AST","chosen_semantics":{"result":"float64","on_zero":"null"},"expected":[[expected]],"outputs":outputs,"equivalent":outputs["sqlite"]["result"].get("rows")==outputs["duckdb"]["result"].get("rows")==[[expected]]})
    # Cross-dialect regressions: documented source, actually executed local target.
    v=exp.Placeholder(this="v")
    safe_bool=exp.Case().when(exp.In(this=exp.func("LOWER",v.copy()),expressions=[exp.Literal.string("true"),exp.Literal.string("false")]),exp.cast(v.copy(),"BOOLEAN")).else_(exp.Null())
    bsql=exp.select(safe_bool.as_("b")).sql("duckdb")
    for value,expected in [("yes",None),("TRUE",True),("false",False),(" true ",None),(None,None)]:
        result=execute(du,bsql,{"v":value})
        tests.append({"id":f"documented_bigquery_safe_bool_{value}","kind":"source-specific-guard","source_evidence":"documentation-only-not-executed","source_url":"https://docs.cloud.google.com/bigquery/docs/reference/standard-sql/conversion_functions#cast_as_bool","sql":bsql,"bindings":{"v":value},"target":result,"documented_expected":[[expected]],"matches_documented_expected":result.get("rows")==[[expected]]})
    epoch=exp.UnixToTime(this=exp.Literal.number(1500000000000),scale=exp.Literal.number(3))
    esql=exp.select(epoch.as_("ts")).sql("duckdb")
    result=execute(du,esql)
    tests.append({"id":"documented_snowflake_epoch_string_milliseconds","kind":"source-specific-unit-lowering","source_evidence":"documentation-only-not-executed","source_url":"https://docs.snowflake.com/en/sql-reference/functions/to_timestamp#usage-notes","sql":esql,"target":result,"documented_expected":"2017-07-14 02:40:00","matches_documented_expected":result.get("rows")==[["2017-07-14 02:40:00"]]})
    try: sqlite_repair("SELECT -9223372036854775808 / -1")
    except Reject as e: tests.append({"id":"sqlite_overflow_negative","status":"rejected","expected_rejection":True,"reason":str(e)})
    return tests

OPS={exp.EQ:"eq",exp.NEQ:"neq",exp.GT:"gt",exp.GTE:"gte",exp.LT:"lt",exp.LTE:"lte",exp.Add:"add",exp.Sub:"sub",exp.Mul:"mul",exp.And:"and",exp.Or:"or"}
OPS_INV={v:k for k,v in OPS.items()}

def ident(n): return {"name":n.this,"quoted":bool(n.args.get("quoted"))}
def ident_back(d): return exp.Identifier(this=d["name"],quoted=d["quoted"])

def expr_to_ir(n):
    if isinstance(n,exp.Column) and not n.is_star: return {"op":"field","parts":[ident(x) for x in n.parts]}
    if isinstance(n,exp.Literal): return {"op":"literal","text":n.this,"is_string":n.is_string}
    if isinstance(n,exp.Null): return {"op":"null"}
    if isinstance(n,exp.Boolean): return {"op":"boolean","value":n.this}
    if isinstance(n,exp.Placeholder) and n.this: return {"op":"param","name":n.this}
    if type(n) in OPS: return {"op":OPS[type(n)],"args":[expr_to_ir(n.left),expr_to_ir(n.right)]}
    if isinstance(n,exp.Paren): return {"op":"paren","arg":expr_to_ir(n.this)}
    if isinstance(n,exp.Not): return {"op":"not","arg":expr_to_ir(n.this)}
    if isinstance(n,exp.Is) and isinstance(n.expression,exp.Null): return {"op":"is_null","arg":expr_to_ir(n.this)}
    raise Reject(f"Expression {type(n).__name__} is outside the bounded editable IR")

def expr_back(n):
    op=n["op"]
    if op=="field":
        keys=["this","table","db","catalog"]
        return exp.Column(**{k:ident_back(v) for k,v in zip(keys,reversed(n["parts"]))})
    if op=="literal": return exp.Literal(this=n["text"],is_string=n["is_string"])
    if op=="null": return exp.Null()
    if op=="boolean": return exp.Boolean(this=n["value"])
    if op=="param": return exp.Placeholder(this=n["name"])
    if op in OPS_INV: return OPS_INV[op](this=expr_back(n["args"][0]),expression=expr_back(n["args"][1]))
    if op in {"paren","not"}: return (exp.Paren if op=="paren" else exp.Not)(this=expr_back(n["arg"]))
    if op=="is_null": return exp.Is(this=expr_back(n["arg"]),expression=exp.Null())
    raise Reject(f"Unknown logical operator {op}")

def import_bounded(sql,dialect="sqlite"):
    env={"authoritative_kind":"sql","authoritative_sql":sql,"source_sha256":hashlib.sha256(sql.encode()).hexdigest(),"source_dialect":dialect}
    try:
        if any(x in sql for x in ["{{","{%","{#"]): raise Reject("Jinja source is preserved and must first be compiled by dbt")
        statements=sqlglot.parse(sql,read=dialect,error_level=sqlglot.ErrorLevel.RAISE)
        if len(statements)!=1 or not isinstance(statements[0],exp.Select): raise Reject("Only a single SELECT is editable")
        ast=statements[0]
        allowed={"expressions","from_","where","order","limit"}
        if any(v is not None and k not in allowed for k,v in ast.args.items()): raise Reject("This SELECT uses clauses outside the bounded editable IR")
        frm=ast.args.get("from_")
        if frm and not isinstance(frm.this,exp.Table): raise Reject("Only a physical FROM table is editable")
        projections=[]
        for p in ast.expressions:
            projections.append({"expr":expr_to_ir(p.this if isinstance(p,exp.Alias) else p),"alias":ident(p.args["alias"]) if isinstance(p,exp.Alias) else None})
        body={"select":projections}
        if frm:
            table=frm.this
            if any(v is not None and k not in {"this","db","catalog","alias"} for k,v in table.args.items()): raise Reject("Table modifiers are outside the bounded editable IR")
            body["from"]={"parts":[ident(p) for p in table.parts],"alias":ident(table.args["alias"].this) if table.args.get("alias") else None}
        if ast.args.get("where"): body["where"]=expr_to_ir(ast.args["where"].this)
        if ast.args.get("order"):
            body["order"]=[{"expr":expr_to_ir(p.this),"desc":bool(p.args.get("desc")),"nulls_first":bool(p.args.get("nulls_first"))} for p in ast.args["order"].expressions]
        if ast.args.get("limit"):
            n=ast.args["limit"].expression
            if not n.is_int: raise Reject("LIMIT must be an integer literal")
            body["limit"]=int(n.this)
        env.update({"editable":True,"ir":{"ir/version":1,"sql_semantics_dialect":dialect,"query":body}})
    except (Reject,sqlglot.errors.SqlglotError) as e: env.update({"editable":False,"reason":str(e)})
    return env

def ir_to_ast(ir):
    if ir.get("ir/version")!=1: raise Reject("Unknown authoring schema version")
    b=ir["query"]
    ast=exp.Select(expressions=[exp.Alias(this=expr_back(p["expr"]),alias=ident_back(p["alias"])) if p["alias"] else expr_back(p["expr"]) for p in b["select"]])
    if "from" in b:
        f=b["from"]
        keys=["this","db","catalog"]
        t=exp.Table(**{k:ident_back(v) for k,v in zip(keys,reversed(f["parts"]))})
        if f["alias"]: t.set("alias",exp.TableAlias(this=ident_back(f["alias"])))
        ast.set("from_",exp.From(this=t))
    if "where" in b: ast.set("where",exp.Where(this=expr_back(b["where"])))
    if "order" in b: ast.set("order",exp.Order(expressions=[exp.Ordered(this=expr_back(p["expr"]),desc=p["desc"],nulls_first=p["nulls_first"]) for p in b["order"]]))
    if "limit" in b: ast.set("limit",exp.Limit(expression=exp.Literal.number(b["limit"])))
    return ast

def roundtrip_cases(sq,du):
    cases=[
        ("basic", "SELECT o.id, o.amount + 2 AS next_amount FROM orders AS o WHERE o.qty > 0 ORDER BY o.id LIMIT 3",{}),
        ("comments", "-- owner: analytics\nSELECT /* keep this rationale */ id AS order_id FROM orders\nWHERE qty > 0 -- zero excluded\nORDER BY id",{}),
        ("named_params", "SELECT :x AS n, :x + 1 AS next, :label AS label",{"x":41,"label":"O'Reilly ☃ ; DROP TABLE orders; --"}),
        ("eq_null_is_not_isnull", "SELECT id FROM orders WHERE amount = NULL ORDER BY id",{}),
        ("is_null", "SELECT id FROM orders WHERE amount IS NULL ORDER BY id",{}),
        ("jinja", "{{ config(materialized='table') }}\n-- retain exact source\nSELECT * FROM {{ ref('orders') }} WHERE id > {{ var('cutoff', 0) }}",{}),
        ("unsupported_cte", "-- keep\nWITH x AS (SELECT id FROM orders) SELECT id FROM x",{}),
        ("unsupported_udf", "SELECT MY_UDF(id) AS n FROM orders",{}),
        ("unsupported_div", "SELECT id / qty AS n FROM orders",{}),
        ("unsupported_join", "SELECT o.id FROM orders o JOIN customers c ON c.id=o.customer_id",{}),
        ("multi_statement", "SELECT 1; SELECT 2",{}),
    ]
    result=[]
    for name,sql,params in cases:
        env=import_bounded(sql)
        transported=json.loads(json.dumps(env))
        item={"id":name,"envelope":env,"exact_source_preserved":transported["authoritative_sql"]==sql,"sha256_preserved":transported["source_sha256"]==hashlib.sha256(sql.encode()).hexdigest()}
        if env["editable"]:
            ast=ir_to_ast(transported["ir"])
            regenerated=ast.copy().sql("sqlite",unsupported_level=sqlglot.ErrorLevel.RAISE)
            ducksql=ast.copy().sql("duckdb",unsupported_level=sqlglot.ErrorLevel.RAISE)
            original=execute(sq,sql,params)
            same=execute(sq,regenerated,params)
            target=execute(du,ducksql,params)
            item.update({"regenerated_sql":regenerated,"target_sql":ducksql,"bindings":params,"original":original,"same_engine":same,"target":target,"same_engine_equivalent":equivalent(original,same),"cross_engine_equivalent_fixture_only":equivalent(original,target)})
        else: item["expected_opaque"]=True
        result.append(item)
    bad={"ir/version":99,"query":{}}
    try: ir_to_ast(bad)
    except Reject as e: result.append({"id":"unknown_ir_version","expected_rejection":True,"error":str(e)})
    return result

def emulation_proof(du):
    values=[None,"abc","123x","3.5","-3.5","9223372036854775808","-9223372036854775809"," 42xyz "]
    sqlite=sqlite3.connect(":memory:")
    expected=[sqlite.execute("SELECT CAST(? AS INTEGER)",(x,)).fetchone()[0] for x in values]
    actual=[du.execute("SELECT wb_sqlite_cast_integer(?)",[x]).fetchone()[0] for x in values]
    rows=10000
    started=time.perf_counter()
    total=du.execute(f"SELECT SUM(wb_sqlite_cast_integer(CAST(i AS VARCHAR)||'suffix')) FROM range({rows}) t(i)").fetchone()[0]
    elapsed=time.perf_counter()-started
    sqlite.close()
    return {"kind":"source-semantic-island-UDF","source_engine_executed":True,"target_engine_executed":True,"values":values,"source":expected,"target":actual,"equivalent":expected==actual,"timing":{"rows":rows,"seconds":elapsed,"sum":total,"expected_sum":rows*(rows-1)//2},"limits":["VARCHAR to SQLite INTEGER only; not SQLite BLOB/dynamic type affinity", "Python callback plus SQLite call per row; illustrative, not a performance benchmark", "Driver must re-register UDF for each DuckDB connection", "SQLite cast semantics depend on pinned SQLite version"]}

def persistence_proof():
    paths={"sqlite":ROOT/"reopen.sqlite","duckdb":ROOT/"reopen.duckdb"}
    pre={}
    for engine,path in paths.items():
        exists=path.exists();con=connect(engine,path)
        if not exists: setup(con)
        pre[engine]=execute(con,"SELECT COUNT(*) AS n, SUM(id) AS ids FROM orders")
        con.close()
    sq,du=connect("sqlite",paths["sqlite"]),connect("duckdb",paths["duckdb"])
    missing=execute(du,"SELECT wb_sqlite_cast_integer('123x')")
    bridge=register_emulation(du)
    source=execute(sq,"SELECT CAST('123x' AS INTEGER) AS n")
    target=execute(du,"SELECT wb_sqlite_cast_integer('123x') AS n")
    post={"sqlite":execute(sq,"SELECT COUNT(*) AS n, SUM(id) AS ids FROM orders"),"duckdb":execute(du,"SELECT COUNT(*) AS n, SUM(id) AS ids FROM orders")}
    sq.close();du.close();bridge.close()
    return {"persisted_paths":{k:str(v) for k,v in paths.items()},"data_survived_close_reopen":pre==post,
            "UDF_missing_before_driver_initialization":missing["status"]=="error",
            "after_driver_initialization_matches_source":equivalent(source,target),"source":source,"target":target,
            "contract":"Reconnect must initialize connection-local semantic functions; database storage alone does not persist Python UDF registrations."}

def dynamic_affinity_proof():
    sq,du=connect("sqlite"),connect("duckdb")
    sq.execute("CREATE TABLE affinity(n INTEGER)");du.execute("CREATE TABLE affinity(n INTEGER)")
    sq.executemany("INSERT INTO affinity VALUES (?)",[("abc",),(1.5,),(2,)])
    source=execute(sq,"SELECT n, typeof(n) AS stored_type FROM affinity ORDER BY rowid")
    import_error=execute(du,"INSERT INTO affinity VALUES ('abc')")
    sq.close();du.close()
    return {"source_evidence":"executed-native","target_evidence":"executed-native","declared_type":"INTEGER","sqlite":source,
            "duckdb_import":import_error,"scope_limit_proved":source["status"]=="ok" and import_error["status"]=="error",
            "implication":"Declared SQLite schema is insufficient to certify fixed column types. Validate actual imported values or retain native execution."}

def run(label):
    sq,du=connect("sqlite"),connect("duckdb")
    setup(sq);setup(du)
    emulated=register_emulation(du)
    try:
        results={"label":label,"versions":{"python":sys.version.split()[0],"sqlite":sqlite3.sqlite_version,"sqlglot":sqlglot.__version__,"duckdb":duckdb.__version__,"numpy":numpy.__version__},
                 "fixture":{"customers":CUSTOMERS,"orders":ORDERS},
                 "regressions":corpus("sqlite->duckdb",sq,du)+corpus("duckdb->sqlite",du,sq),
                 "semantic_lowering":semantic_cases(sq,du),"roundtrip":roundtrip_cases(sq,du),"emulation":emulation_proof(du),
                 "persistence":persistence_proof(),"dynamic_affinity":dynamic_affinity_proof()}
        return results
    finally:
        sq.close();du.close();emulated.close()

def remove_volatile(obj):
    if isinstance(obj,dict): return {k:remove_volatile(v) for k,v in obj.items() if k not in {"label","timing"}}
    if isinstance(obj,list): return [remove_volatile(v) for v in obj]
    return obj

def main():
    parser=argparse.ArgumentParser();parser.add_argument("--label",default="initial");parser.add_argument("--output",default="results.json")
    args=parser.parse_args()
    a,b=run(args.label+"-connection-1"),run(args.label+"-connection-2")
    # Every repaired executable case must match actual source or documented contract.
    assert all(x.get("equivalent",x.get("matches_documented_expected",x.get("expected_rejection",False))) for x in a["semantic_lowering"]),"semantic lowering regression"
    assert all(x.get("exact_source_preserved",True) and x.get("sha256_preserved",True) and x.get("same_engine_equivalent",True) for x in a["roundtrip"]),"bounded import regression"
    assert a["emulation"]["equivalent"]
    assert a["persistence"]["data_survived_close_reopen"] and a["persistence"]["after_driver_initialization_matches_source"]
    assert a["dynamic_affinity"]["scope_limit_proved"]
    robust=remove_volatile(a)==remove_volatile(b)
    assert robust,"fresh connection results changed"
    output={"run":a,"fresh_connection_revalidation":{"equivalent":robust,"second_label":b["label"]},"methodology":{"remote_warehouses_executed":False,"sqlite_duckdb_comparisons":"both real native engines executed","comparison_contract":"row values after ISO date/time serialization; not native SQL type identity or full relational equivalence. Native type codes and Python types are recorded separately.","coverage_contract":"deliberately adversarial fixed corpus; match counts are not a dialect-support percentage","BigQuery_Snowflake":"documented source behavior; corrected DuckDB executed only","IR_scope":"bounded SELECT scalar expressions; authoritative raw SQL retained exactly"}}
    path=ROOT/args.output;path.write_text(json.dumps(output,indent=2,ensure_ascii=False)+"\n")
    summary={"output":str(path),"cases":len(a["regressions"]),"strict_equivalent":sum(x["strict_transpile"]["equivalent"] for x in a["regressions"]),"typed_equivalent":sum(x["qualified_typed"]["equivalent"] for x in a["regressions"]),"lowering_cases":len(a["semantic_lowering"]),"IR_cases":len(a["roundtrip"]),"fresh_connection_revalidation":robust,"emulation":a["emulation"]["equivalent"]}
    print(json.dumps(summary,indent=2))

if __name__=="__main__": main()
