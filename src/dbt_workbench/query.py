"""Bounded, read-only model previews and versioned data queries.

SQLGlot is a parser/compiler here, not a semantic equivalence oracle.  The
SQLite bridge therefore accepts a deliberately small expression vocabulary.
"""
from __future__ import annotations

import datetime as dt
import decimal
import json
import os
from pathlib import Path
import re
import sqlite3
import threading
import time

import duckdb
from edn_format import Keyword, loads as read_edn
import pyarrow as pa
import sqlglot
from sqlglot import exp
from sqlglot.errors import ErrorLevel, SqlglotError
from sqlglot.optimizer.normalize_identifiers import normalize_identifiers
from sqlglot.optimizer.scope import traverse_scope
import yaml


class QueryError(ValueError):
    """An actionable query or capability error safe to display to the user."""


class QueryCancelled(QueryError):
    pass


_SAFE_FUNCTIONS = {
    "Abs", "Avg", "Ceil", "Coalesce", "Concat", "Count", "Floor", "Greatest",
    "Least", "Length", "Lower", "Max", "Min", "Nullif", "Round", "Sum", "Upper",
    "Substring", "Trim", "Cast", "TryCast", "Date", "DateTrunc", "TimestampTrunc",
    "Extract", "RowNumber", "Rank", "DenseRank", "Lag", "Lead", "FirstValue",
    "LastValue", "If", "Case", "In", "And", "Or", "Not", "AnyValue",
}
_SQLITE_FUNCTIONS = {
    "Abs", "Avg", "Coalesce", "Count", "Length", "Lower", "Max", "Min", "Nullif",
    "Round", "Sum", "Upper", "Substring", "Trim", "If", "Case", "In", "And", "Or", "Not",
}
_FORBIDDEN = {
    "Insert", "Update", "Delete", "Merge", "Create", "Drop", "Alter", "Command",
    "Copy", "Attach", "Detach", "Pragma", "Transaction", "Commit", "Rollback",
    "Grant", "Revoke", "Set", "Use", "LoadData", "Into", "Lock", "Execute",
}


def _plain(value):
    if isinstance(value, Keyword):
        return str(value)[1:]
    if hasattr(value, "items"):
        return {_plain(k): _plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)) or type(value).__name__ in {"ImmutableList", "ImmutableVector"}:
        return [_plain(v) for v in value]
    return value


def _ident(value, label="identifier"):
    if not isinstance(value, str) or not value or "\x00" in value:
        raise QueryError(f"{label} must be a nonempty string without NUL bytes")
    return exp.to_identifier(value, quoted=True)


def _table(database, schema, name):
    return exp.Table(this=_ident(name), db=_ident(schema) if schema else None,
                     catalog=_ident(database) if database else None)


def _normalized_scopes(tree):
    """Analyze DuckDB's case-insensitive names without changing output labels."""
    normalized = tree.copy()
    originals = {id(copy): original for original, copy in zip(tree.walk(), normalized.walk())}
    normalize_identifiers(normalized, dialect="duckdb")
    for scope in traverse_scope(normalized):
        yield scope, originals


def _physical_tables(tree):
    """Only actual relations: CTE and derived-table references stay untouched."""
    seen = set()
    for scope, originals in _normalized_scopes(tree):
        for scoped_node in scope.tables:
            node = originals[id(scoped_node)]
            if not isinstance(node.this, exp.Identifier):
                raise QueryError("Table functions and external scans are not supported in read-only queries")
            if any(character in node.name for character in ("/", "\\", "*", "?")) or "://" in node.name:
                raise QueryError("File paths and external scans are not supported as query relations")
            source = scope.sources.get(scoped_node.alias_or_name)
            if isinstance(source, exp.Table) and id(node) not in seen:
                seen.add(id(node))
                yield node


def _replace_relation(tree, node, replacement):
    """Retarget the relation and its scoped columns before introducing an alias.

    A qualified column such as main.orders.id must become orders.id if the
    physical table is replaced by ``... AS orders``. Scope.columns also carries
    correlated references, while excluding columns owned by nested scopes.
    """
    alias = node.alias_or_name
    if not node.args.get("alias"):
        for scope, originals in _normalized_scopes(tree):
            if not any(originals[id(table)] is node for table in scope.tables):
                continue
            for scoped_column in scope.columns:
                column = originals[id(scoped_column)]
                if column.table.casefold() != node.name.casefold():
                    continue
                if column.db and node.db and column.db.casefold() != node.db.casefold():
                    continue
                catalogs = {part.casefold() for part in (node.catalog, replacement.catalog) if part}
                if column.catalog and catalogs and column.catalog.casefold() not in catalogs:
                    continue
                column.set("table", _ident(alias))
                column.set("db", None)
                column.set("catalog", None)
    replacement.set("alias", node.args.get("alias") or exp.TableAlias(this=_ident(alias)))
    node.replace(replacement)


def _parse_sql(sql):
    if not isinstance(sql, str) or not sql.strip():
        raise QueryError("SQL content is empty")
    if "{{" in sql or "{%" in sql or "{#" in sql:
        raise QueryError("SQL contains dbt Jinja; compile the model before previewing it")
    try:
        statements = sqlglot.parse(sql, read="duckdb", error_level=ErrorLevel.RAISE)
    except SqlglotError as exc:
        raise QueryError(f"Cannot parse DuckDB SQL: {exc}") from exc
    if len(statements) != 1 or not isinstance(statements[0], (exp.Select, exp.Union, exp.Intersect, exp.Except)):
        raise QueryError("Only one read-only SELECT or WITH query is allowed")
    tree = statements[0]
    for node in tree.walk():
        kind = type(node).__name__
        if kind in _FORBIDDEN:
            raise QueryError(f"Read-only queries do not allow {kind}")
        if isinstance(node, exp.Func) and kind not in _SAFE_FUNCTIONS:
            name = node.name if isinstance(node, exp.Anonymous) else kind
            raise QueryError(f"Function {name} is outside the supported read-only query vocabulary")
    list(_physical_tables(tree))
    return tree


class QueryEngine:
    def __init__(self, workspaces):
        self.workspaces = workspaces

    def _profile(self, project_id, target=None):
        project = self.workspaces.get_project(project_id)
        try:
            config = yaml.safe_load((Path(project["path"]) / "dbt_project.yml").read_text()) or {}
            profiles = yaml.safe_load((Path(project["profiles_dir"]) / "profiles.yml").read_text()) or {}
            profile = profiles[config["profile"]]
            target = target or profile.get("target", "dev")
            output = profile["outputs"][target]
        except (OSError, KeyError, TypeError, yaml.YAMLError) as exc:
            raise QueryError("Cannot read the selected dbt profile; check dbt_project.yml, profiles.yml and target") from exc
        if not isinstance(output, dict) or output.get("type") != "duckdb":
            raise QueryError("Local previews currently support DuckDB input profiles; use dbt for this adapter")
        raw_path = output.get("path")
        if not isinstance(raw_path, str) or raw_path == ":memory:":
            raise QueryError("Preview requires a persisted DuckDB profile path; run a build first")
        if "{{" in raw_path or "{%" in raw_path:
            # Only the familiar env_var form is interpreted, never arbitrary profile Jinja.
            pattern = r"\{\{\s*env_var\(['\"]([^'\"]+)['\"](?:\s*,\s*['\"]([^'\"]*)['\"])?\)\s*\}\}"
            def env(match):
                value = os.environ.get(match.group(1), match.group(2))
                if value is None:
                    raise QueryError("A DuckDB profile path environment variable is not set")
                return value
            raw_path = re.sub(pattern, env, raw_path)
            if "{{" in raw_path or "{%" in raw_path:
                raise QueryError("Preview supports literal DuckDB paths and env_var paths, not arbitrary profile Jinja")
        path = Path(raw_path).expanduser()
        if not path.is_absolute():
            path = Path(project["path"]) / path
        if not path.is_file():
            raise QueryError(f"Target {target!r} has no built local database; run Build first")
        return target, output, path.resolve()

    def _manifest(self, project_id, baseline=False):
        if not baseline:
            manifest = self.workspaces.manifest(project_id)
            if not manifest:
                raise QueryError("No parsed manifest is available; parse or build this project first")
            return manifest
        baseline = self.workspaces.baseline(project_id)
        if not baseline:
            raise QueryError("No baseline is available; build a baseline first")
        path = baseline.get("manifest_path") or str(Path(baseline.get("artifact_dir", baseline.get("path", ""))) / "manifest.json")
        try:
            return json.loads(Path(path).read_text())
        except (OSError, ValueError) as exc:
            raise QueryError("The saved baseline manifest cannot be read; rebuild the baseline") from exc

    @staticmethod
    def _model(manifest, model_id):
        nodes = {k: v for k, v in manifest.get("nodes", {}).items() if v.get("resource_type") == "model"}
        if model_id in nodes:
            return nodes[model_id]
        matches = [v for v in nodes.values() if isinstance(model_id, str) and v.get("name", "").casefold() == model_id.casefold()]
        if len(matches) != 1:
            raise QueryError(f"Model {model_id!r} is missing or ambiguous; use its full model unique_id")
        return matches[0]

    def _connect(self, project_id, target):
        target, profile, path = self._profile(project_id, target)
        con = None
        try:
            con = duckdb.connect(str(path), read_only=True)
            schema = profile.get("schema") or "main"
            _ident(schema, "profile schema")
            con.execute("SET schema = ?", [schema])
            aliases = {str(row[2]): row[1] for row in con.execute("PRAGMA database_list").fetchall()}
            for item in profile.get("attach", []):
                item = {"path": item} if isinstance(item, str) else item
                attachment = Path(item.get("path", "")).expanduser()
                if not attachment.is_absolute():
                    attachment = Path(self.workspaces.get_project(project_id)["path"]) / attachment
                if not attachment.is_file():
                    continue
                attachment = attachment.resolve()
                alias = item.get("alias") or attachment.stem
                if str(attachment) in aliases or alias in aliases.values():
                    continue
                con.execute(f"ATTACH {exp.Literal.string(str(attachment)).sql()} AS {_ident(alias).sql()} (READ_ONLY)")
                aliases[str(attachment)] = alias
            return con, target, path.stem, aliases
        except Exception as exc:
            if con is not None:
                con.close()
            if isinstance(exc, QueryError):
                raise
            raise QueryError(f"Cannot open target {target!r} read-only; finish any active dbt build and retry") from exc

    def _binding(self, project_id, model_id, binding, catalog, aliases, con, default_target):
        baseline = binding == "baseline"
        manifest = self._manifest(project_id, baseline)
        model = self._model(manifest, model_id)
        if model.get("config", {}).get("materialized") == "ephemeral":
            raise QueryError("Ephemeral models have no relation; preview their compiled SQL instead")
        if baseline:
            info = self.workspaces.baseline(project_id)
            _, _, path = self._profile(project_id, info.get("target", "baseline"))
            if str(path) not in aliases:
                alias = "wb_baseline"
                con.execute(f"ATTACH {exp.Literal.string(str(path)).sql()} AS {_ident(alias).sql()} (READ_ONLY)")
                aliases[str(path)] = alias
            db = aliases[str(path)]
        else:
            db = catalog
        schema = model.get("schema") or con.execute("SELECT current_schema()").fetchone()[0]
        relation = _table(db, schema, model.get("alias") or model["name"])
        provenance = {"model_id": model.get("unique_id", model_id), "binding": binding,
                      "target": info.get("target", "baseline") if baseline else default_target,
                      "relation": relation.sql(dialect="duckdb")}
        return relation, provenance

    def _rewrite_sql_bindings(self, project_id, tree, baseline, catalog, aliases, con, target):
        manifest = self._manifest(project_id, True) if baseline else self.workspaces.manifest(project_id) or {}
        models = [n for n in manifest.get("nodes", {}).values() if n.get("resource_type") == "model"]
        bindings = []
        for node in list(_physical_tables(tree)):
            candidates = [m for m in models if node.name.casefold() in {
                              value.casefold() for value in (m.get("name"), m.get("alias")) if value}
                          and (not node.db or node.db.casefold() == (m.get("schema") or "").casefold())]
            # Fully qualified compiled refs already encode dbt's deferral decision.
            # Only unqualified input is resolved from the chosen manifest here.
            if node.catalog or not candidates:
                bindings.append({"binding": "compiled" if node.catalog else "native", "relation": node.sql(dialect="duckdb")})
                continue
            if len(candidates) != 1:
                raise QueryError(f"Relation {node.name!r} matches multiple models; qualify the relation")
            replacement, binding = self._binding(project_id, candidates[0]["unique_id"], "baseline" if baseline else "dev", catalog, aliases, con, target)
            _replace_relation(tree, node, replacement)
            bindings.append(binding)
        return bindings

    def _data_query(self, project_id, content, parameters, catalog, aliases, con, target):
        try:
            data = _plain(read_edn(content)) if isinstance(content, str) else _plain(content)
        except Exception as exc:
            raise QueryError(f"Cannot read EDN query: {exc}") from exc
        if not isinstance(data, dict) or type(data.get("query/version")) is not int or data.get("query/version") != 1:
            raise QueryError("EDN query must declare :query/version 1")
        if not all(isinstance(key, str) for key in data):
            raise QueryError("EDN query keys must be keywords")
        allowed = {"query/version", "from", "select", "where", "group-by", "order-by", "limit"}
        if set(data) - allowed:
            raise QueryError(f"Unsupported EDN query keys: {', '.join(sorted(set(data) - allowed))}")
        source = data.get("from")
        if not isinstance(source, dict) or set(source) - {"model", "as", "binding"}:
            raise QueryError(":from must contain :model and optional :as/:binding")
        if not isinstance(source.get("model"), str):
            raise QueryError(":from :model must be a model unique_id or name")
        alias = source.get("as", "m")
        _ident(alias, "model alias")
        binding = source.get("binding", "dev")
        if binding not in ("dev", "baseline"):
            raise QueryError(":binding must be :dev or :baseline")
        relation, provenance = self._binding(project_id, source.get("model"), binding, catalog, aliases, con, target)
        relation.set("alias", exp.TableAlias(this=_ident(alias)))
        parameter_types = {}

        def expression(value):
            if value is None:
                return exp.Null()
            if isinstance(value, bool):
                return exp.Boolean(this=value)
            if isinstance(value, (int, float, decimal.Decimal)):
                return exp.Literal.number(value)
            if isinstance(value, str):
                return exp.Literal.string(value)
            if not isinstance(value, list) or not value:
                raise QueryError("Expressions must be literals or operator vectors")
            op, *args = value
            if not isinstance(op, str):
                raise QueryError("Expression operator must be a keyword")
            if op == "field":
                if len(args) != 2 or args[0] != alias:
                    raise QueryError(f":field must use the declared alias {alias!r} and a column name")
                return exp.Column(this=_ident(args[1], "column name"), table=_ident(args[0]))
            if op == "param":
                if len(args) != 2 or args[1] not in ("text", "integer", "float", "boolean", "date"):
                    raise QueryError(":param requires a name and :text/:integer/:float/:boolean/:date")
                name, kind = args
                if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                    raise QueryError("Parameter names must contain letters, digits and underscores")
                if name in parameter_types and parameter_types[name] != kind:
                    raise QueryError(f"Parameter {name!r} has conflicting declared types")
                parameter_types[name] = kind
                return exp.Placeholder(this=name)
            binary = {"eq": exp.EQ, "ne": exp.NEQ, "gt": exp.GT, "gte": exp.GTE, "lt": exp.LT,
                      "lte": exp.LTE, "add": exp.Add, "+": exp.Add, "sub": exp.Sub, "-": exp.Sub,
                      "mul": exp.Mul, "*": exp.Mul, "and": exp.And, "or": exp.Or}
            if op in binary and len(args) == 2:
                return binary[op](this=expression(args[0]), expression=expression(args[1]))
            if op in {"div", "/"} and len(args) == 2:
                left, right = [exp.Cast(this=expression(a), to=exp.DataType.build("DOUBLE")) for a in args]
                return exp.Div(this=left, expression=exp.Nullif(this=right, expression=exp.Literal.number(0)), typed=False, safe=True)
            if op == "not" and len(args) == 1:
                return exp.Not(this=expression(args[0]))
            if op == "is-null" and len(args) == 1:
                return exp.Is(this=expression(args[0]), expression=exp.Null())
            funcs = {"sum": exp.Sum, "count": exp.Count, "min": exp.Min, "max": exp.Max, "avg": exp.Avg}
            if op in funcs and (len(args) == 1 or op == "count" and not args):
                return funcs[op](this=expression(args[0]) if args else exp.Star())
            if op == "coalesce" and len(args) >= 2:
                return exp.Coalesce(this=expression(args[0]), expressions=[expression(a) for a in args[1:]])
            raise QueryError(f"Unsupported operator or arity: {op!r}")

        projections = data.get("select")
        if not isinstance(projections, list) or not projections:
            raise QueryError(":select requires a nonempty vector of {:as ... :expr ...} projections")
        selects = []
        for item in projections:
            if not isinstance(item, dict) or set(item) != {"as", "expr"}:
                raise QueryError("Each projection requires exactly :as and :expr")
            selects.append(exp.Alias(this=expression(item["expr"]), alias=_ident(item["as"], "projection alias")))
        tree = exp.Select(expressions=selects).from_(relation)
        if "where" in data:
            tree = tree.where(expression(data["where"]))
        if "group-by" in data:
            if not isinstance(data["group-by"], list):
                raise QueryError(":group-by must be a vector of expressions")
            tree = tree.group_by(*[expression(v) for v in data["group-by"]])
        if "order-by" in data:
            if not isinstance(data["order-by"], list):
                raise QueryError(":order-by must be a vector of expressions")
            orders = []
            for item in data["order-by"]:
                if isinstance(item, dict):
                    if set(item) - {"expr", "direction"} or item.get("direction", "asc") not in ("asc", "desc"):
                        raise QueryError("Order entries accept :expr and :direction :asc/:desc")
                    orders.append(exp.Ordered(this=expression(item["expr"]), desc=item.get("direction") == "desc", nulls_first=False))
                else:
                    orders.append(exp.Ordered(this=expression(item), desc=False, nulls_first=False))
            tree = tree.order_by(*orders)
        if "limit" in data:
            limit = data["limit"]
            if isinstance(limit, bool) or not isinstance(limit, int) or not 0 <= limit <= 100000:
                raise QueryError(":limit must be an integer between 0 and 100000")
            tree = tree.limit(limit)
        _check_parameters(parameters, parameter_types)
        return tree, [provenance]

    def preview(self, project_id, options, cancel_event, event):
        baseline = bool(options.get("baseline"))
        manifest = self._manifest(project_id, baseline)
        model = self._model(manifest, options.get("model_id"))
        sql = model.get("compiled_code") or model.get("compiled_sql")
        if not sql:
            raise QueryError("Compiled SQL is not available for this model; compile or build it first")
        options = dict(options, content=sql, format="sql", _preview=True)
        if baseline:
            options["target"] = self.workspaces.baseline(project_id).get("target", "baseline")
        payload = self.query(project_id, options, cancel_event, event)
        payload["provenance"].update(model_id=model.get("unique_id"), baseline=baseline)
        return payload

    def query(self, project_id, options, cancel_event, event):
        if cancel_event.is_set():
            raise QueryCancelled("Query cancelled")
        dialect = options.get("dialect", "duckdb")
        if dialect not in ("duckdb", "sqlite"):
            raise QueryError("Preview execution supports DuckDB and SQLite; other dialects require a driver")
        max_rows = options.get("max_rows", 10000)
        timeout = options.get("timeout_seconds", 30)
        if isinstance(max_rows, bool) or not isinstance(max_rows, int) or not 1 <= max_rows <= 100000:
            raise QueryError("max_rows must be between 1 and 100000")
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not 0 < timeout <= 120:
            raise QueryError("timeout_seconds must be greater than zero and at most 120")
        parameters = options.get("parameters") or {}
        if not isinstance(parameters, dict):
            raise QueryError("parameters must be a name-to-value map")
        con, target, catalog, aliases = self._connect(project_id, options.get("target"))
        stop = threading.Event()
        deadline = time.monotonic() + timeout
        timed_out = threading.Event()

        def watch():
            while not stop.wait(0.025):
                if cancel_event.is_set() or time.monotonic() > deadline:
                    if not cancel_event.is_set():
                        timed_out.set()
                    con.interrupt()
                    return

        watcher = threading.Thread(target=watch, daemon=True)
        watcher.start()
        try:
            kind = options.get("format", "sql")
            if kind == "edn":
                tree, bindings = self._data_query(project_id, options.get("content"), parameters, catalog, aliases, con, target)
                original = options.get("content")
            elif kind == "sql":
                original = options.get("content")
                tree = _parse_sql(original)
                bindings = self._rewrite_sql_bindings(project_id, tree, bool(options.get("baseline")), catalog, aliases, con, target)
                names = {node.name for node in tree.find_all(exp.Placeholder)}
                _check_parameters(parameters, {name: None for name in names})
            else:
                raise QueryError("format must be sql or edn")
            _validate_relations(con, tree, catalog)
            native_sql = tree.sql(dialect="duckdb", unsupported_level=ErrorLevel.RAISE)
            warnings = []
            if dialect == "sqlite":
                if kind == "sql":
                    _sqlite_capabilities(tree)
                table, executed, captured = self._sqlite(con, tree, parameters, max_rows, deadline, cancel_event)
                warnings.append("SQLite previews capture bounded DuckDB inputs. Numeric precision, collation and output types can differ from DuckDB.")
            else:
                executed = _bounded(tree, max_rows).sql(dialect="duckdb", unsupported_level=ErrorLevel.RAISE)
                table = con.execute(executed, parameters).to_arrow_table()
                captured = []
            if cancel_event.is_set():
                raise QueryCancelled("Query cancelled")
            if timed_out.is_set():
                raise QueryError(f"Query exceeded the {timeout:g}-second time limit")
            truncated = table.num_rows > max_rows
            table = table.slice(0, max_rows)
            event(f"Returned {table.num_rows} rows using {dialect}" + (" (row limit reached)" if truncated else ""))
            return {"table": table, "sql": executed, "dialect": dialect,
                    "provenance": {"target": target, "format": kind, "original_sql": original if kind == "sql" else None,
                                   "original_edn": original if kind == "edn" else None, "native_sql": native_sql,
                                   "translated_sql": executed, "bindings": bindings, "parameters": parameters,
                                   "captured_relations": captured, "warnings": warnings, "truncated": truncated,
                                   "row_limit": max_rows, "input_dialect": "duckdb"},
                    "summary": {"row_count": table.num_rows, "truncated": truncated}}
        except QueryError:
            raise
        except (duckdb.Error, sqlite3.Error, SqlglotError, pa.ArrowException) as exc:
            if cancel_event.is_set():
                raise QueryCancelled("Query cancelled") from exc
            if timed_out.is_set() or time.monotonic() > deadline:
                raise QueryError(f"Query exceeded the {timeout:g}-second time limit") from exc
            raise QueryError(f"{dialect} preview failed: {exc}") from exc
        finally:
            stop.set()
            watcher.join(timeout=1)
            con.close()

    def _sqlite(self, source, tree, parameters, max_rows, deadline, cancel_event):
        tree = tree.copy()
        target = sqlite3.connect(":memory:")
        target.set_progress_handler(lambda: int(cancel_event.is_set() or time.monotonic() > deadline), 1000)
        captures = {}
        try:
            for node in list(_physical_tables(tree)):
                relation = node.copy()
                relation.set("alias", None)
                key = relation.sql(dialect="duckdb")
                if key not in captures:
                    if cancel_event.is_set():
                        raise QueryCancelled("Query cancelled")
                    captured = source.execute(exp.select("*").from_(relation).limit(100001).sql(dialect="duckdb")).to_arrow_table()
                    if captured.num_rows > 100000:
                        raise QueryError("SQLite input exceeds 100000 rows for one relation; narrow the model or use DuckDB")
                    name = f"__wb_capture_{len(captures)}"
                    columns = []
                    for field in captured.schema:
                        typ = field.type
                        if pa.types.is_decimal(typ):
                            raise QueryError(f"SQLite bridge cannot preserve DECIMAL semantics for column {field.name!r}; use DuckDB or explicitly create a float-valued source model")
                        if pa.types.is_integer(typ) or pa.types.is_boolean(typ):
                            sqlite_type = "INTEGER"
                        elif pa.types.is_floating(typ):
                            sqlite_type = "REAL"
                        elif pa.types.is_string(typ) or pa.types.is_large_string(typ) or pa.types.is_temporal(typ) or pa.types.is_null(typ):
                            sqlite_type = "TEXT"
                        else:
                            raise QueryError(f"SQLite bridge does not support column {field.name!r} of type {typ}")
                        columns.append(f"{_ident(field.name).sql()} {sqlite_type}")
                    target.execute(f"CREATE TABLE {_ident(name).sql()} ({', '.join(columns)})")
                    names = captured.column_names
                    rows = [tuple(_sqlite_value(row[column]) for column in names) for row in captured.to_pylist()]
                    target.executemany(f"INSERT INTO {_ident(name).sql()} VALUES ({','.join('?' for _ in names)})", rows)
                    captures[key] = {"local_name": name, "relation": key, "rows": len(rows)}
                replacement = exp.Table(this=_ident(captures[key]["local_name"]))
                _replace_relation(tree, node, replacement)
            # Capture setup is complete; all subsequent statements are read-only.
            target.execute("PRAGMA query_only = ON")
            sql = _bounded(tree, max_rows).sql(dialect="sqlite", unsupported_level=ErrorLevel.RAISE)
            cursor = target.execute(sql, parameters)
            rows = cursor.fetchall()
            names = [column[0] for column in cursor.description]
            table = pa.Table.from_arrays([pa.array([row[i] for row in rows]) for i in range(len(names))], names=names)
            return table, sql, list(captures.values())
        finally:
            target.close()


def _bounded(tree, max_rows):
    return exp.select("*").from_(tree.copy().subquery("__wb_result")).limit(max_rows + 1)


def _validate_relations(connection, tree, catalog):
    """Prevent DuckDB's implicit filename scans, including bare quoted files."""
    available = {tuple(part.casefold() for part in row) for row in connection.execute(
        "SELECT database_name, schema_name, table_name FROM duckdb_tables() "
        "UNION ALL SELECT database_name, schema_name, view_name FROM duckdb_views()"
    ).fetchall()}
    default_schema = connection.execute("SELECT current_schema()").fetchone()[0]
    for node in _physical_tables(tree):
        key = (node.catalog or catalog, node.db or default_schema, node.name)
        if tuple(part.casefold() for part in key) not in available:
            raise QueryError(f"Relation {node.sql(dialect='duckdb')} is not an existing table or view in the selected target; build the model or check its binding")


def _sqlite_value(value):
    if isinstance(value, (dt.date, dt.time, dt.datetime)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return str(value)
    return value


def _check_parameters(parameters, declarations):
    missing, extra = set(declarations) - set(parameters), set(parameters) - set(declarations)
    if missing or extra:
        raise QueryError(f"Parameter mismatch: missing {sorted(missing)}, unexpected {sorted(extra)}")
    for name, kind in declarations.items():
        value = parameters[name]
        if value is None or kind is None:
            continue
        valid = {"text": isinstance(value, str), "integer": isinstance(value, int) and not isinstance(value, bool),
                 "float": isinstance(value, (int, float)) and not isinstance(value, bool), "boolean": isinstance(value, bool),
                 "date": isinstance(value, str)}[kind]
        if not valid:
            raise QueryError(f"Parameter {name!r} must have declared type {kind}")
        if kind == "date":
            try:
                dt.date.fromisoformat(value)
            except ValueError as exc:
                raise QueryError(f"Parameter {name!r} must be an ISO date") from exc


def _sqlite_capabilities(tree):
    for node in tree.walk():
        kind = type(node).__name__
        if isinstance(node, (exp.Div, exp.IntDiv, exp.Mod)):
            raise QueryError("Raw SQL division/remainder has engine-specific semantics; use EDN :div for float division with NULL on zero, or DuckDB")
        if isinstance(node, exp.Func) and kind not in _SQLITE_FUNCTIONS:
            raise QueryError(f"SQLite translation is not certified for {kind}; use DuckDB or an explicit EDN expression")
        if isinstance(node, (exp.Window, exp.Qualify, exp.Like, exp.ILike)):
            raise QueryError(f"SQLite translation does not support {kind} in this release; use DuckDB")
