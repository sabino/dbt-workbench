from pathlib import Path
import os, json, shutil, importlib.metadata as md, traceback
from sqlmesh import Context
from sqlglot import exp

BASE=Path(__file__).resolve().parent
PROJECT=BASE/'sqlmesh-dbt-project'
RESULTS=BASE/'sqlmesh-results.json'

if PROJECT.exists(): shutil.rmtree(PROJECT)
(PROJECT/'models').mkdir(parents=True)
(PROJECT/'seeds').mkdir()
(PROJECT/'profiles.yml').write_text(f'''workbench:\n  target: local\n  outputs:\n    local:\n      type: duckdb\n      path: {PROJECT/'mesh.duckdb'}\n      schema: analytics\n      threads: 1\n''')
(PROJECT/'dbt_project.yml').write_text('''name: workbench\nversion: '1.0'\nconfig-version: 2\nprofile: workbench\nmodel-paths: [models]\nseed-paths: [seeds]\nmodels:\n  workbench:\n    +materialized: table\n''')
(PROJECT/'sqlmesh.yaml').write_text("model_defaults:\n  start: '2026-10-08'\n")
(PROJECT/'seeds'/'orders.csv').write_text('customer_id,amount,status\n1,20,completed\n1,5,completed\n2,10,cancelled\n2,7,completed\n')
ORIGINAL="select customer_id, sum(amount) as revenue from {{ ref('orders') }} where status = 'completed' group by customer_id"
CHANGED="select customer_id, sum(amount) * 2 as revenue from {{ ref('orders') }} where status = 'completed' group by customer_id"
(PROJECT/'models'/'revenue.sql').write_text(ORIGINAL)
(PROJECT/'models'/'schema.yml').write_text('''version: 2\nmodels:\n  - name: revenue\n    columns:\n      - name: customer_id\n        tests: [not_null, unique]\n''')
os.environ['DBT_PROFILES_DIR']=str(PROJECT)
res={'versions':{p:md.version(p) for p in ['sqlmesh','dbt-core','dbt-duckdb','sqlglot','duckdb']},'project':str(PROJECT),'checks':[]}

def rows(ctx,q): return ctx.fetchdf(q).to_dict('records')
def snapshot_tables(plan):
 return {s.name:s.table_name() for s in plan.snapshots.values()}
try:
 ctx=Context(paths=PROJECT)
 res['models']=list(ctx.models)
 prod=ctx.plan('prod', no_prompts=True, auto_apply=True, execution_time='2026-10-09',skip_tests=False)
 res['prod_physical_tables']=snapshot_tables(prod)
 res['prod_rows_before']=rows(ctx,'select * from analytics.revenue order by customer_id')
 res['checks'].append({'name':'dbt_import_seed_ref_generic_tests_and_build','pass':res['prod_rows_before']==[{'customer_id':1,'revenue':25},{'customer_id':2,'revenue':7}]})
 ctx.close()
 (PROJECT/'models'/'revenue.sql').write_text(CHANGED)
 ctx=Context(paths=PROJECT)
 dev=ctx.plan('dev',no_prompts=True,auto_apply=True,start='2026-10-08',end='2026-10-09',execution_time='2026-10-09',skip_tests=False,include_unmodified=True)
 res['dev_physical_tables']=snapshot_tables(dev)
 res['prod_rows_after']=rows(ctx,'select * from analytics.revenue order by customer_id')
 res['dev_rows']=rows(ctx,'select * from analytics__dev.revenue order by customer_id')
 res['checks'].append({'name':'dev_isolation','pass':res['prod_rows_after']==res['prod_rows_before'] and res['dev_rows']==[{'customer_id':1,'revenue':50},{'customer_id':2,'revenue':14}]})
 seed=[n for n in res['prod_physical_tables'] if n.endswith('"orders"')][0]
 child=[n for n in res['prod_physical_tables'] if n.endswith('"revenue"')][0]
 res['checks'].append({'name':'unchanged_upstream_physical_table_reused','pass':res['prod_physical_tables'][seed]==res['dev_physical_tables'][seed]})
 res['checks'].append({'name':'changed_model_gets_new_physical_table','pass':res['prod_physical_tables'][child]!=res['dev_physical_tables'][child]})
 res['state_tables']=rows(ctx,"select table_schema, table_name from information_schema.tables order by table_schema,table_name")
 res['rendered_dev']=ctx.render(child).sql(dialect='duckdb')
 ctx.close()
except Exception as exc:
 res['error']={'type':type(exc).__name__,'message':str(exc),'traceback':traceback.format_exc()}
RESULTS.write_text(json.dumps(res,indent=2,default=str))
print(json.dumps({'checks':res['checks'],'error':res.get('error')},indent=2))
