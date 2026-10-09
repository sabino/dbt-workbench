from pathlib import Path
import os,json,traceback
from sqlmesh import Context
from sqlmesh.core.console import CaptureTerminalConsole,set_console
console=CaptureTerminalConsole()
set_console(console)
BASE=Path(__file__).resolve().parent
PROJECT=BASE/'sqlmesh-jaffle-project'
os.environ['DBT_PROFILES_DIR']=str(PROJECT)
os.environ['DBT_SEND_ANONYMOUS_USAGE_STATS']='false'
seed=PROJECT/'seeds'/'raw_customers.csv'
original=seed.read_text()
lines=original.splitlines()
seed.write_text(original+'\n'+lines[1]+'\n')
res={'perturbation':'Duplicate first raw_customer seed row, invalidating imported unique(customer_id) audits','result':None}
ctx=None
try:
 ctx=Context(paths=PROJECT)
 res['standalone_audit_count']=len([s for s in ctx.snapshots.values() if not s.is_model])
 ctx.plan('audit_probe',no_prompts=True,auto_apply=True,start='2026-10-08',end='2026-10-09',execution_time='2026-10-09',include_unmodified=True)
 res['result']='unexpected_success'
except Exception as e:
 res['result']='rejected'
 res['error']={'type':type(e).__name__,'message':str(e),'traceback':traceback.format_exc()}
finally:
 seed.write_text(original)
 if ctx:ctx.close()
res['captured_errors']=console.captured_errors
res['captured_output']=console.captured_output
res['verified_unique_audit_rejection']='unique_stg_customers_customer_id' in res['captured_output'] and 'audit error: 1 row failed' in res['captured_output']
(BASE/'sqlmesh-audit-results.json').write_text(json.dumps(res,indent=2,default=str))
print(json.dumps(res,indent=2,default=str))
