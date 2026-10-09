from pathlib import Path
import os,json,shutil,subprocess,traceback,importlib.metadata as md
from sqlmesh import Context
import duckdb
BASE=Path(__file__).resolve().parent
PROJECT=BASE/'sqlmesh-incremental-project'
if PROJECT.exists():shutil.rmtree(PROJECT)
(PROJECT/'models').mkdir(parents=True)
(PROJECT/'seeds').mkdir()
profiles='workbench:\n  target: mesh\n  outputs:\n'
for target in ['native','mesh']:
 profiles+=f'    {target}:\n      type: duckdb\n      path: {PROJECT/f"{target}.duckdb"}\n      schema: analytics\n      threads: 1\n'
(PROJECT/'profiles.yml').write_text(profiles)
(PROJECT/'dbt_project.yml').write_text("name: workbench\nversion: '1.0'\nconfig-version: 2\nprofile: workbench\nmodel-paths: [models]\nseed-paths: [seeds]\n")
(PROJECT/'sqlmesh.yaml').write_text("model_defaults:\n  start: '2026-10-08'\n")
(PROJECT/'seeds'/'inputs.csv').write_text('id,amount\n1,5\n2,7\n')
(PROJECT/'models'/'inc.sql').write_text("{{ config(materialized='incremental', unique_key='id', incremental_strategy='append') }}\nselect id, amount from {{ ref('inputs') }}")
env=dict(os.environ,DBT_PROFILES_DIR=str(PROJECT),DBT_SEND_ANONYMOUS_USAGE_STATS='false')
os.environ['DBT_PROFILES_DIR']=str(PROJECT)
os.environ['DBT_SEND_ANONYMOUS_USAGE_STATS']='false'
res={'versions':{p:md.version(p) for p in ['sqlmesh','dbt-core','dbt-duckdb','duckdb']},'project':str(PROJECT)}
def dbt(command,name):
 process=subprocess.run([str(BASE/'venv'/'bin'/'dbt'),command,'--target','native','--project-dir',str(PROJECT)],env=env,capture_output=True,text=True)
 (BASE/f'sqlmesh-incremental-{name}.log').write_text(process.stdout+process.stderr)
 if process.returncode:raise RuntimeError(f'Native dbt {command} failed')
def native_rows():
 conn=duckdb.connect(str(PROJECT/'native.duckdb'),read_only=True)
 rows=conn.execute('select * from analytics.inc order by id,amount').fetchall()
 conn.close()
 return rows
try:
 dbt('build','first')
 res['native_first_rows']=native_rows()
 ctx=Context(paths=PROJECT)
 res['imported_model_kind']={m.name:str(m.kind) for m in ctx.models.values()}
 prod=ctx.plan('prod',no_prompts=True,auto_apply=True,execution_time='2026-10-09')
 res['sqlmesh_first_rows']=ctx.engine_adapter.fetchall('select * from analytics.inc order by id,amount')
 dbt('run','second')
 res['native_second_rows']=native_rows()
 status=ctx.run('prod',start='2026-10-09',end='2026-10-10',execution_time='2026-10-10',ignore_cron=True,skip_janitor=True)
 res['sqlmesh_second_run_status']=str(status)
 res['sqlmesh_second_rows']=ctx.engine_adapter.fetchall('select * from analytics.inc order by id,amount')
 res['initial_equivalent']=res['native_first_rows']==res['sqlmesh_first_rows']
 res['second_run_equivalent']=res['native_second_rows']==res['sqlmesh_second_rows']
 ctx.close()
except Exception as e:res['error']={'type':type(e).__name__,'message':str(e),'traceback':traceback.format_exc()}
(BASE/'sqlmesh-incremental-results.json').write_text(json.dumps(res,indent=2,default=str))
print(json.dumps(res,indent=2,default=str))
