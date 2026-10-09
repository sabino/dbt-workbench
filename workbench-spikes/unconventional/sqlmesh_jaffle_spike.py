from pathlib import Path
import os, json, shutil, importlib.metadata as md, traceback,subprocess,datetime,decimal
from sqlmesh import Context
import duckdb
BASE=Path(__file__).resolve().parent
SRC=Path('/tmp/workbench-spikes/jaffle-shop-classic')
PROJECT=BASE/'sqlmesh-jaffle-project'
if PROJECT.exists():shutil.rmtree(PROJECT)
shutil.copytree(SRC,PROJECT,ignore=shutil.ignore_patterns('.git','logs','target','*.duckdb','profiles.yml'))
profiles='jaffle_shop:\n  target: mesh\n  outputs:\n'
for target in ['native','mesh']:
 profiles+=f'    {target}:\n      type: duckdb\n      path: {PROJECT/f"{target}.duckdb"}\n      schema: analytics\n      threads: 1\n'
(PROJECT/'profiles.yml').write_text(profiles)
(PROJECT/'sqlmesh.yaml').write_text("model_defaults:\n  start: '2026-10-08'\n")
env=dict(os.environ,DBT_PROFILES_DIR=str(PROJECT),DBT_SEND_ANONYMOUS_USAGE_STATS='false')
os.environ['DBT_PROFILES_DIR']=str(PROJECT)
os.environ['DBT_SEND_ANONYMOUS_USAGE_STATS']='false'
source_commit=subprocess.check_output(['git','-C',str(SRC),'rev-parse','HEAD'],text=True).strip()
res={'source_commit':source_commit,'versions':{p:md.version(p) for p in ['sqlmesh','dbt-core','dbt-duckdb','sqlglot','duckdb']},'source_project':str(SRC),'project':str(PROJECT),'checks':[]}
def normalize(rows):
 def norm(v):
  if isinstance(v,(datetime.date,datetime.datetime)):return v.isoformat()
  if isinstance(v,decimal.Decimal):return float(v)
  return v
 return [[norm(v) for v in row] for row in rows]
try:
 dbt=subprocess.run([str(BASE/'venv'/'bin'/'dbt'),'build','--target','native','--project-dir',str(PROJECT)],env=env,capture_output=True,text=True)
 (BASE/'sqlmesh-jaffle-native-dbt.log').write_text(dbt.stdout+dbt.stderr)
 res['dbt_exit_code']=dbt.returncode
 if dbt.returncode:raise RuntimeError('Native dbt baseline failed; see log')
 run_results=json.loads((PROJECT/'target'/'run_results.json').read_text())
 res['dbt_result_counts']={s:sum(r['status']==s for r in run_results['results']) for s in sorted(set(r['status'] for r in run_results['results']))}
 ctx=Context(paths=PROJECT)
 res['models']=list(ctx.models)
 res['model_audits']={m.name:[str(a) for a in m.audits] for m in ctx.models.values()}
 res['model_attached_audit_count']=sum(len(x) for x in res['model_audits'].values())
 prod=ctx.plan('prod',no_prompts=True,auto_apply=True,execution_time='2026-10-09')
 res['prod_physical_tables']={s.name:s.table_name() for s in prod.snapshots.values() if s.name in ctx.models}
 native=duckdb.connect(str(PROJECT/'native.duckdb'),read_only=True)
 for model in ['stg_customers','stg_orders','stg_payments','orders','customers']:
  n=normalize(native.execute(f'select * from analytics.{model} order by 1').fetchall())
  m=normalize(ctx.engine_adapter.fetchall(f'select * from analytics.{model} order by 1'))
  res['checks'].append({'name':f'dbt_sqlmesh_row_equivalence_{model}','pass':n==m,'native_count':len(n),'sqlmesh_count':len(m),'first_difference':next(((i,a,b) for i,(a,b) in enumerate(zip(n,m)) if a!=b),None)})
 native.close()
 prod_customers=normalize(ctx.engine_adapter.fetchall('select * from analytics.customers order by 1'))
 ctx.close()
 customer_path=PROJECT/'models'/'customers.sql'
 original=customer_path.read_text()
 # Add a calculated model column and verify only its snapshot changes.
 edited=original.replace('customer_payments.total_amount as customer_lifetime_value','customer_payments.total_amount * 2 as customer_lifetime_value')
 if edited==original:
  raise RuntimeError('Expected classic customer_lifetime_value expression was not found')
 customer_path.write_text(edited)
 ctx=Context(paths=PROJECT)
 dev=ctx.plan('dev',no_prompts=True,auto_apply=True,start='2026-10-08',end='2026-10-09',execution_time='2026-10-09',include_unmodified=True)
 res['dev_physical_tables']={s.name:s.table_name() for s in dev.snapshots.values() if s.name in ctx.models}
 reused=[n for n in res['prod_physical_tables'] if res['prod_physical_tables'][n]==res['dev_physical_tables'][n]]
 changed=[n for n in res['prod_physical_tables'] if res['prod_physical_tables'][n]!=res['dev_physical_tables'][n]]
 res['reused_models']=reused
 res['changed_models']=changed
 res['checks'].append({'name':'only_customers_snapshot_changes','pass':len(reused)==7 and len(changed)==1 and changed[0].endswith('"customers"')})
 dev_rows=normalize(ctx.engine_adapter.fetchall('select * from analytics__dev.customers order by 1'))
 prod_after=normalize(ctx.engine_adapter.fetchall('select * from analytics.customers order by 1'))
 expected=[r[:-1]+[r[-1]*2 if r[-1] is not None else None] for r in prod_customers]
 res['checks'].append({'name':'jaffle_development_change_isolated_and_correct','pass':dev_rows==expected and prod_after==prod_customers,'row_count':len(dev_rows)})
 ctx.close()
except Exception as exc:
 res['error']={'type':type(exc).__name__,'message':str(exc),'traceback':traceback.format_exc()}
(BASE/'sqlmesh-jaffle-results.json').write_text(json.dumps(res,indent=2,default=str))
print(json.dumps({'checks':res['checks'],'error':res.get('error')},indent=2))
