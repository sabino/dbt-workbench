"""Synthetic workbench vertical slice. Not a production implementation."""
from pathlib import Path
from collections.abc import Mapping
import hashlib
import json
import sqlite3
import subprocess
import sys

import duckdb
import edn_format
import pyarrow as pa
import sqlglot
from sqlglot import exp
from sqlglot.optimizer.scope import traverse_scope

ROOT = Path(__file__).resolve().parent
RESULTS = []
DBT = Path('/tmp/dbt-workbench-feasibility-venv/bin/dbt')


def plain(value):
    if isinstance(value, edn_format.Keyword):
        return str(value)[1:]
    if isinstance(value, Mapping):
        return {plain(k): plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, edn_format.ImmutableList)):
        return [plain(v) for v in value]
    return value


QUERY_EDN = '''{:query/version 1
 :from {:model "model.demo.orders" :as "o"}
 :select [{:as "customer_id" :expr [:field "o" "customer_id"]}
          {:as "revenue" :expr [:sum [:field "o" "amount"]]}
          {:as "unit_price" :expr [:divide-float [:sum [:field "o" "amount"]]
                                  [:sum [:field "o" "quantity"]]]}]
 :where [:eq [:field "o" "status"] [:param "status" :text]]
 :group-by [[:field "o" "customer_id"]]
 :order-by [[:field "o" "customer_id"]]}'''

SCHEMA = {'model.demo.orders': {'customer_id': 'int64', 'amount': 'float64',
                               'quantity': 'int64', 'status': 'text'}}


def lower(query, bindings):
    if query.get('query/version') != 1:
        raise ValueError('Unsupported query version')
    model = query['from']['model']
    alias = query['from']['as']
    fields = SCHEMA[model]
    if model not in bindings:
        raise ValueError(f'Missing explicit binding: {model}')

    def scalar(value):
        if not isinstance(value, list) or not value:
            raise ValueError('Expression must be an operator vector')
        op, *args = value
        if op == 'field' and len(args) == 2:
            owner, name = args
            if owner != alias or name not in fields:
                raise ValueError(f'Unknown field {owner}.{name}')
            return exp.column(name, table=owner, quoted=True)
        if op == 'param' and len(args) == 2:
            name, declared_type = args
            if declared_type != 'text':
                raise ValueError('Probe parameter contract only supports text')
            return exp.Placeholder(this=name)
        if op == 'sum' and len(args) == 1:
            return exp.Sum(this=scalar(args[0]))
        if op == 'eq' and len(args) == 2:
            return exp.EQ(this=scalar(args[0]), expression=scalar(args[1]))
        if op == 'divide-float' and len(args) == 2:
            left = exp.Cast(this=scalar(args[0]), to=exp.DataType.build('double'))
            right = exp.Cast(this=scalar(args[1]), to=exp.DataType.build('double'))
            return exp.Div(this=left,
                           expression=exp.Nullif(this=right, expression=exp.Literal.number(0)),
                           safe=False, typed=False)
        raise ValueError(f'Unsupported operator/arity: {op}')

    ast = exp.Select(expressions=[exp.alias_(scalar(item['expr']), item['as'], quoted=True)
                                  for item in query['select']])
    ast = ast.from_(exp.to_table(bindings[model]).as_(alias, quoted=True))
    if 'where' in query:
        ast = ast.where(scalar(query['where']))
    if 'group-by' in query:
        ast = ast.group_by(*[scalar(item) for item in query['group-by']])
    if 'order-by' in query:
        ast = ast.order_by(*[scalar(item) for item in query['order-by']])
    return ast


def check(label, fn):
    try:
        result = fn()
        item = {'case': label, 'status': 'pass', 'detail': result}
    except Exception as exc:
        item = {'case': label, 'status': 'fail', 'error': f'{type(exc).__name__}: {exc}'}
    RESULTS.append(item)
    print(json.dumps(item, default=str), flush=True)


def run_dbt(project, label, args):
    cmd = [str(DBT), '--no-use-colors', *args, '--project-dir', str(project),
           '--profiles-dir', str(project), '--target-path', str(project / 'outputs' / label),
           '--log-path', str(project / 'logs' / label)]
    completed = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    (project / f'{label}.stdout').write_text(completed.stdout)
    (project / f'{label}.stderr').write_text(completed.stderr)
    if completed.returncode:
        raise RuntimeError(completed.stdout[-2500:] + completed.stderr[-1000:])
    return completed


query = plain(edn_format.loads(QUERY_EDN))
(ROOT / 'query.edn').write_text(QUERY_EDN)
ROWS = [(1, 10.0, 2, 'completed'), (1, 5.0, 1, 'completed'),
        (2, 7.0, 0, 'completed'), (3, 100.0, 1, 'cancelled')]
EXPECTED = [(1, 15.0, 5.0), (2, 7.0, None)]


def run_ir_both_engines():
    outputs = {}
    for dialect in ['sqlite', 'duckdb']:
        connection = sqlite3.connect(':memory:') if dialect == 'sqlite' else duckdb.connect()
        connection.execute('CREATE TABLE orders(customer_id INTEGER, amount DOUBLE, quantity INTEGER, status TEXT)')
        connection.executemany('INSERT INTO orders VALUES (?, ?, ?, ?)', ROWS)
        ast = lower(query, {'model.demo.orders': 'orders'})
        sql = ast.sql(dialect=dialect, unsupported_level=sqlglot.ErrorLevel.RAISE)
        rows = connection.execute(sql, {'status': 'completed'}).fetchall()
        assert rows == EXPECTED, rows
        outputs[dialect] = {'sql': sql, 'rows': rows}
        connection.close()
    return outputs


def parameter_injection():
    outputs = {}
    payload = "completed' OR 1=1 --"
    for dialect in ['sqlite', 'duckdb']:
        conn = sqlite3.connect(':memory:') if dialect == 'sqlite' else duckdb.connect()
        conn.execute('CREATE TABLE orders(customer_id INTEGER, amount DOUBLE, quantity INTEGER, status TEXT)')
        conn.executemany('INSERT INTO orders VALUES (?, ?, ?, ?)', ROWS)
        sql = lower(query, {'model.demo.orders': 'orders'}).sql(dialect=dialect)
        assert payload not in sql
        result = conn.execute(sql, {'status': payload}).fetchall()
        assert result == [], result
        outputs[dialect] = {'rows': result, 'parameter_stays_separate': True}
        conn.close()
    return outputs


def fail_closed():
    checks = {}
    for label, change in [('unknown_operator', lambda q: q.update(where=['mystery', 1])),
                          ('unknown_column', lambda q: q.update(where=['eq', ['field', 'o', 'missing'], ['param', 'status', 'text']])),
                          ('unknown_version', lambda q: q.update({'query/version': 99}))]:
        candidate = json.loads(json.dumps(query))
        change(candidate)
        try:
            lower(candidate, {'model.demo.orders': 'orders'})
        except ValueError as exc:
            checks[label] = str(exc)
        else:
            raise AssertionError(label)
    return checks


def dbt_vertical_slice():
    project = ROOT / 'dbt_project'
    (project / 'models').mkdir(parents=True, exist_ok=True)
    (project / 'seeds').mkdir(exist_ok=True)
    (project / 'dbt_project.yml').write_text('name: demo\nversion: "1.0"\nconfig-version: 2\nprofile: demo\nmodel-paths: [models]\nseed-paths: [seeds]\nmodels:\n  demo:\n    +materialized: table\n')
    (project / 'profiles.yml').write_text(f'demo:\n  target: local\n  outputs:\n    local:\n      type: duckdb\n      path: {project / "local.duckdb"}\n      threads: 1\n')
    (project / 'seeds' / 'raw_orders.csv').write_text('customer_id,amount,quantity,status\n' + '\n'.join(','.join(map(str, r)) for r in ROWS) + '\n')
    (project / 'models' / 'orders.sql').write_text("select * from {{ ref('raw_orders') }}\n")
    (project / 'models' / 'schema.yml').write_text('version: 2\nmodels:\n  - name: customer_revenue\n    columns:\n      - name: customer_id\n        data_tests: [unique, not_null]\n')
    def emit(candidate):
        template = lower(candidate, {'model.demo.orders': '__dbt_orders__'}).sql(dialect='duckdb')
        template = template.replace('__dbt_orders__', "{{ ref('orders') }}")
        # Runtime bind parameters have no dbt build binding channel: emit an explicitly
        # typed literal through the AST before formatting generated model SQL.
        ast = lower(candidate, {'model.demo.orders': '__dbt_orders__'})
        ast = ast.transform(lambda n: exp.Literal.string('completed') if isinstance(n, exp.Placeholder) else n)
        rendered = ast.sql(dialect='duckdb').replace('__dbt_orders__', "{{ ref('orders') }}")
        (project / 'models' / 'customer_revenue.sql').write_text('-- generated from query.edn\n' + rendered + '\n')
        return template, rendered
    before_template, before = emit(query)
    run_dbt(project, 'initial', ['build'])
    with duckdb.connect(str(project / 'local.duckdb'), read_only=True) as c:
        initial = c.execute('SELECT * FROM customer_revenue ORDER BY customer_id').fetchall()
    assert initial == EXPECTED, initial
    changed = json.loads(json.dumps(query))
    changed['select'][1]['expr'] = ['sum', ['field', 'o', 'quantity']]
    after_template, after = emit(changed)
    run_dbt(project, 'edited', ['build', '--select', 'customer_revenue'])
    with duckdb.connect(str(project / 'local.duckdb'), read_only=True) as c:
        edited = c.execute('SELECT * FROM customer_revenue ORDER BY customer_id').fetchall()
    assert edited == [(1, 3, 5.0), (2, 0, None)], edited
    manifest = json.loads((project / 'outputs' / 'edited' / 'manifest.json').read_text())
    node = manifest['nodes']['model.demo.customer_revenue']
    assert 'model.demo.orders' in node['depends_on']['nodes']
    run_results = json.loads((project / 'outputs' / 'edited' / 'run_results.json').read_text())
    assert all(r['status'] in ['success', 'pass'] for r in run_results['results'])
    return {'initial_rows': initial, 'edited_rows': edited,
            'dbt_dependencies': node['depends_on']['nodes'],
            'dbt_test_statuses': [r['status'] for r in run_results['results']],
            'bind_template': before_template, 'generated_sql': before,
            'edited_generated_sql': after}


def provenance_revalidation():
    def key(q, binding, snapshot, schema_rev, compiler_version):
        payload = [q, binding, snapshot, schema_rev, compiler_version]
        return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
    baseline = key(query, 'prod.orders', 's1', 'schema1', sqlglot.__version__)
    variants = [key(query, 'local.orders', 's1', 'schema1', sqlglot.__version__),
                key(query, 'prod.orders', 's2', 'schema1', sqlglot.__version__),
                key(query, 'prod.orders', 's1', 'schema2', sqlglot.__version__),
                key(query, 'prod.orders', 's1', 'schema1', 'next-version')]
    assert len(set([baseline, *variants])) == 5
    # An actual persisted dataset, not the hash alone, is reloaded in a new process.
    db = ROOT / 'persisted.duckdb'
    with duckdb.connect(str(db)) as c:
        c.execute('CREATE OR REPLACE TABLE orders(customer_id INTEGER, amount DOUBLE, quantity INTEGER, status TEXT)')
        c.executemany('INSERT INTO orders VALUES (?, ?, ?, ?)', ROWS)
    sql = lower(query, {'model.demo.orders': 'orders'}).sql(dialect='duckdb')
    code = ('import duckdb,json; '
            f'c=duckdb.connect({str(db)!r},read_only=True); '
            f'print(json.dumps(c.execute({sql!r},{{"status":"completed"}}).fetchall()))')
    output = subprocess.check_output([sys.executable, '-c', code], text=True)
    rows = json.loads(output)
    assert rows == [list(x) for x in EXPECTED]
    return {'restart_rows': rows, 'different_context_keys': len(set([baseline, *variants]))}


def arrow_result_roundtrip():
    table = pa.table({'id': pa.array([1, 2], pa.int64()),
                      'value': pa.array([5.0, None], pa.float64())})
    sink = pa.BufferOutputStream()
    with pa.ipc.new_stream(sink, table.schema) as writer:
        writer.write_table(table)
    data = sink.getvalue()
    decoded = pa.ipc.open_stream(data).read_all()
    assert decoded.equals(table)
    return {'bytes': len(data), 'schema': str(decoded.schema), 'rows': decoded.to_pylist()}


def existing_sql_scope_rewrite():
    source = 'WITH orders AS (SELECT * FROM prod.raw.orders) SELECT * FROM orders o WHERE EXISTS (SELECT 1 FROM prod.raw.customers c WHERE c.id=o.customer_id)'
    tree = sqlglot.parse_one(source, read='duckdb')
    physical = []
    mapping = {'prod.raw.orders': 'snapshot.orders', 'prod.raw.customers': 'snapshot.customers'}
    for scope in traverse_scope(tree):
        for _, node in scope.sources.items():
            if isinstance(node, exp.Table):
                identity = '.'.join(x.name for x in node.parts)
                if identity in mapping:
                    physical.append(identity)
                    replacement = exp.to_table(mapping[identity])
                    if node.args.get('alias'):
                        replacement.set('alias', node.args['alias'].copy())
                    node.replace(replacement)
    rendered = tree.sql(dialect='duckdb')
    assert sorted(physical) == sorted(mapping)
    assert 'FROM orders AS o' in rendered
    return {'physical_relations': physical, 'rewritten_sql': rendered}


if __name__ == '__main__':
    for name, fn in [('edn_to_sql_two_engines', run_ir_both_engines),
                     ('bound_parameters', parameter_injection),
                     ('unsupported_input_rejection', fail_closed),
                     ('dbt_build_edit_rebuild_tests', dbt_vertical_slice),
                     ('persisted_data_restart_and_contexts', provenance_revalidation),
                     ('arrow_result_interchange', arrow_result_roundtrip),
                     ('existing_sql_scope_rewrite', existing_sql_scope_rewrite)]:
        check(name, fn)

    payload = {'versions': {'python': sys.version.split()[0], 'sqlglot': sqlglot.__version__,
                            'duckdb': duckdb.__version__, 'pyarrow': pa.__version__,
                            'edn_format': __import__('importlib.metadata').metadata.version('edn-format')},
               'synthetic': True, 'results': RESULTS}
    (ROOT / 'results.json').write_text(json.dumps(payload, default=str, indent=2))
    sys.exit(1 if any(r['status'] != 'pass' for r in RESULTS) else 0)
