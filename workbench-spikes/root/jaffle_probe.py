"""Pinned classic Jaffle Shop: native dbt, deferral, SQL bridge, query-as-data."""
from pathlib import Path
from decimal import Decimal
from datetime import date, datetime
import hashlib
import importlib.metadata
import json
import math
import shutil
import sqlite3
import subprocess
import sys

import duckdb
import edn_format
import sqlglot
from sqlglot import exp
from sqlglot.optimizer.scope import traverse_scope

import probe as ir

ROOT = Path(__file__).resolve().parent / 'jaffle'
SOURCE = Path('/tmp/workbench-spikes/jaffle-shop-classic')
ROOT.mkdir(exist_ok=True)
PROJECT = ROOT / 'project'
if not PROJECT.exists():
    shutil.copytree(SOURCE, PROJECT, ignore=shutil.ignore_patterns('.git'))
PROD = ROOT / 'jaffle_prod.duckdb'
DEV = ROOT / 'jaffle_dev.duckdb'
SQLITE = ROOT / 'snapshot.sqlite'
PROFILE = ROOT / 'profiles'
PROFILE.mkdir(exist_ok=True)
(PROFILE / 'profiles.yml').write_text(f'''jaffle_shop:
  target: prod
  outputs:
    prod:
      type: duckdb
      path: {PROD}
      threads: 1
    dev:
      type: duckdb
      path: {DEV}
      threads: 1
      attach:
        - path: {PROD}
          read_only: true
''')
OUTCOMES = []


def invocation(label, args):
    command = [str(ir.DBT), '--no-use-colors', *args,
               '--project-dir', str(PROJECT), '--profiles-dir', str(PROFILE),
               '--target-path', str(ROOT / 'artifacts' / label),
               '--log-path', str(ROOT / 'logs' / label)]
    result = subprocess.run(command, text=True, capture_output=True, timeout=90)
    (ROOT / f'{label}.stdout').write_text(result.stdout)
    (ROOT / f'{label}.stderr').write_text(result.stderr)
    if result.returncode:
        raise RuntimeError(result.stdout[-4000:] + result.stderr[-1000:])
    manifest = json.loads((ROOT / 'artifacts' / label / 'manifest.json').read_text())
    runs = json.loads((ROOT / 'artifacts' / label / 'run_results.json').read_text())
    assert all(r['status'] in ['success', 'pass'] for r in runs['results'])
    return manifest, runs


def normalized(value):
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    return value


def normalize(rows):
    return [[normalized(x) for x in row] for row in rows]


def equivalent(actual, expected):
    actual, expected = normalize(actual), normalize(expected)
    assert len(actual) == len(expected), (len(actual), len(expected))
    for arow, erow in zip(actual, expected):
        assert len(arow) == len(erow)
        for a, e in zip(arow, erow):
            if isinstance(a, (int, float)) and isinstance(e, (int, float)):
                assert math.isclose(a, e, abs_tol=1e-9, rel_tol=1e-9), (a, e)
            else:
                assert a == e, (a, e)


def physical_rewrite(sql, table_mapping):
    tree = sqlglot.parse_one(sql, read='duckdb', error_level=sqlglot.ErrorLevel.RAISE)
    rewritten = []
    for scope in traverse_scope(tree):
        for _, source in scope.sources.items():
            if isinstance(source, exp.Table):
                identity = '.'.join(x.name for x in source.parts)
                if identity in table_mapping:
                    new = exp.to_table(table_mapping[identity])
                    if source.args.get('alias'):
                        new.set('alias', source.args['alias'].copy())
                    source.replace(new)
                    rewritten.append(identity)
    return tree, rewritten


def snapshot_baseline():
    metadata = {}
    target = sqlite3.connect(SQLITE)
    with duckdb.connect(str(PROD), read_only=True) as source:
        for table in ['stg_customers', 'stg_orders', 'stg_payments', 'orders']:
            cursor = source.execute(f'SELECT * FROM "{table}"')
            schema = [(d[0], str(d[1])) for d in cursor.description]
            rows = normalize(cursor.fetchall())
            types = []
            for name, dtype in schema:
                mapped = 'INTEGER' if 'INT' in dtype else 'REAL' if any(s in dtype for s in ['DECIMAL', 'DOUBLE', 'FLOAT']) else 'TEXT'
                types.append(f'"{name}" {mapped}')
            target.execute(f'DROP TABLE IF EXISTS "{table}"')
            target.execute(f'CREATE TABLE "{table}" ({", ".join(types)})')
            target.executemany(f'INSERT INTO "{table}" VALUES ({", ".join("?" for _ in schema)})', rows)
            metadata[table] = {'columns': schema, 'rows': len(rows),
                               'dataset_sha256': hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()}
    target.commit()
    target.close()
    return metadata


def main():
    commit = subprocess.check_output(['git', '-C', str(SOURCE), 'rev-parse', 'HEAD'], text=True).strip()
    # Reset our experimental copy before each revalidation.
    (PROJECT / 'models' / 'orders.sql').write_text((SOURCE / 'models' / 'orders.sql').read_text())
    native_manifest, baseline_run = invocation('baseline', ['build', '--target', 'prod'])
    model_count = sum(n['resource_type'] == 'model' for n in native_manifest['nodes'].values())
    test_count = sum(n['resource_type'] == 'test' for n in native_manifest['nodes'].values())
    seed_count = sum(n['resource_type'] == 'seed' for n in native_manifest['nodes'].values())
    with duckdb.connect(str(PROD), read_only=True) as c:
        orders = c.execute('SELECT * FROM orders ORDER BY order_id').fetchall()
        customers = c.execute('SELECT * FROM customers ORDER BY customer_id').fetchall()
    OUTCOMES.append({'case': 'classic_dbt_baseline', 'status': 'pass',
                     'models': model_count, 'tests': test_count, 'seeds': seed_count,
                     'orders': len(orders), 'customers': len(customers)})
    state = ROOT / 'state' / 'baseline'
    state.mkdir(parents=True, exist_ok=True)
    shutil.copy(ROOT / 'artifacts' / 'baseline' / 'manifest.json', state / 'manifest.json')
    state_hash = hashlib.sha256((state / 'manifest.json').read_bytes()).hexdigest()
    original = (PROJECT / 'models' / 'orders.sql').read_text()
    assert 'order_payments.total_amount as amount' in original
    changed = original.replace('order_payments.total_amount as amount', 'order_payments.total_amount * 1.10 as amount')
    (PROJECT / 'models' / 'orders.sql').write_text(changed)
    manifest, run = invocation('edited', ['build', '--target', 'dev', '--select', 'orders',
                                         '--defer', '--state', str(state), '--favor-state'])
    node = manifest['nodes']['model.jaffle_shop.orders']
    with duckdb.connect(str(DEV), read_only=True) as c:
        edited = c.execute('SELECT * FROM orders ORDER BY order_id').fetchall()
    expected = [list(row[:-1]) + [None if row[-1] is None else row[-1] * 1.10] for row in orders]
    equivalent(edited, expected)
    assert '"jaffle_prod"."main"."stg_orders"' in node['compiled_code']
    assert '"jaffle_prod"."main"."stg_payments"' in node['compiled_code']
    assert state_hash == hashlib.sha256((state / 'manifest.json').read_bytes()).hexdigest()
    OUTCOMES.append({'case': 'classic_edit_selected_build_defer_tests', 'status': 'pass',
                     'result_rows': len(edited), 'test_statuses': [r['status'] for r in run['results']],
                     'deferred_inputs': ['stg_orders', 'stg_payments'], 'baseline_unchanged': True})
    snapshots = snapshot_baseline()
    target = sqlite3.connect(SQLITE)
    mapping = {f'jaffle_prod.main.{table}': table for table in snapshots}
    for model, expected_rows, key in [('orders', orders, 'order_id'), ('customers', customers, 'customer_id')]:
        sql = native_manifest['nodes'][f'model.jaffle_shop.{model}']['compiled_code']
        tree, rewritten = physical_rewrite(sql, mapping)
        target_sql = tree.sql(dialect='sqlite', unsupported_level=sqlglot.ErrorLevel.RAISE)
        rows = target.execute(f'SELECT * FROM ({target_sql}) ORDER BY {key}').fetchall()
        equivalent(rows, expected_rows)
        OUTCOMES.append({'case': f'classic_compiled_{model}_duckdb_to_sqlite', 'status': 'pass',
                         'rows': len(rows), 'rewritten_inputs': rewritten, 'sql': target_sql})
    target.close()
    # Same readable query bound to two different physical datasets.
    edn = '''{:query/version 1
      :from {:model "model.jaffle_shop.orders" :as "o"}
      :select [{:as "customer_id" :expr [:field "o" "customer_id"]}
               {:as "revenue" :expr [:sum [:field "o" "amount"]]}]
      :where [:eq [:field "o" "status"] [:param "status" :text]]
      :group-by [[:field "o" "customer_id"]]
      :order-by [[:field "o" "customer_id"]]}'''
    (ROOT / 'query.edn').write_text(edn)
    query = ir.plain(edn_format.loads(edn))
    ir.SCHEMA['model.jaffle_shop.orders'] = {'customer_id': 'int64', 'amount': 'float64', 'status': 'text'}
    outputs = {}
    for dialect in ['sqlite', 'duckdb']:
        conn = sqlite3.connect(SQLITE) if dialect == 'sqlite' else duckdb.connect(str(PROD), read_only=True)
        sql = ir.lower(query, {'model.jaffle_shop.orders': 'orders'}).sql(dialect=dialect)
        rows = conn.execute(sql, {'status': 'completed'}).fetchall()
        outputs[dialect] = {'sql': sql, 'rows': normalize(rows)}
        conn.close()
    equivalent(outputs['sqlite']['rows'], outputs['duckdb']['rows'])
    OUTCOMES.append({'case': 'classic_edn_named_bindings_two_engines', 'status': 'pass', 'outputs': outputs})
    # Revalidate after a new interpreter reopens the persisted SQLite snapshot.
    code = f'import sqlite3,json; c=sqlite3.connect({str(SQLITE)!r}); print(json.dumps(c.execute({outputs["sqlite"]["sql"]!r},{{"status":"completed"}}).fetchall()))'
    restarted = json.loads(subprocess.check_output([sys.executable, '-c', code], text=True))
    equivalent(restarted, outputs['duckdb']['rows'])
    OUTCOMES.append({'case': 'classic_restart_revalidation', 'status': 'pass', 'rows': len(restarted)})
    payload = {'source': {'url': 'https://github.com/dbt-labs/jaffle-shop-classic', 'commit': commit},
               'versions': {'sqlglot': sqlglot.__version__, 'duckdb': duckdb.__version__,
                            'sqlite': sqlite3.sqlite_version, 'dbt-core': importlib.metadata.version('dbt-core') if False else '1.12.5'},
               'snapshots': snapshots, 'numeric_comparison': {'absolute_tolerance': 1e-9, 'relative_tolerance': 1e-9},
               'results': OUTCOMES}
    (ROOT / 'results.json').write_text(json.dumps(payload, default=str, indent=2))
    print(json.dumps({'source': payload['source'], 'results': OUTCOMES}, default=str), flush=True)


if __name__ == '__main__':
    main()
