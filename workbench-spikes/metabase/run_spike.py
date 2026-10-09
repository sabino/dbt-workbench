#!/usr/bin/env python3
"""Reproduce compiler extraction probe with pinned Metabase uberjar; Python stdlib only."""
import os,json,time,subprocess,resource,hashlib,urllib.request,pathlib,sqlite3
HERE=pathlib.Path(__file__).resolve().parent
os.chdir(HERE)
VERSION='v0.64.1'
COMMIT='7010f2797355cb04f2bc2e82e747eaa86357a315'
SHA256='ffe600f532875b4d3c738effbc0f42f8a1ad7f8255cb875569f73b36645ef11d'
jar=HERE/'metabase.jar'
if not jar.exists():
 urllib.request.urlretrieve(f'https://downloads.metabase.com/{VERSION}/metabase.jar',jar)
actual=hashlib.file_digest(jar.open('rb'),'sha256').hexdigest()
assert actual==SHA256,actual
env=os.environ.copy()
env.update(MB_DB_TYPE='postgres',MB_DB_HOST='127.0.0.1',MB_DB_PORT='1',MB_DB_DBNAME='compiler_must_not_connect',MB_DB_USER='unused',MB_DB_PASS='unused')
cmd=['java','-Xmx2g','-Djava.awt.headless=true','-cp','metabase.jar','clojure.main','bootstrap_no_appdb.clj']
start=time.perf_counter()
with open('compiler.log','w') as out,open('compiler.stderr.log','w') as err:
 proc=subprocess.run(cmd,env=env,stdout=out,stderr=err,timeout=120)
seconds=time.perf_counter()-start
assert proc.returncode==0,(proc.returncode,pathlib.Path('compiler.stderr.log').read_text()[-4000:])
compiled=json.loads(pathlib.Path('results.compiler.json').read_text())
bootstrap=json.loads(pathlib.Path('bootstrap.json').read_text())
assert bootstrap['app_db_connection_attempts']==0,bootstrap
conn=sqlite3.connect(':memory:')
conn.executescript('CREATE TABLE orders(id INTEGER, total DECIMAL, created_at TEXT, customer_id INTEGER, status TEXT); CREATE TABLE customers(id INTEGER, name TEXT);')
orders=[(1,5,'2026-01-01T00:00:00',1,'shipped'),(2,15,'2026-01-02T00:00:00',1,'pending'),(3,25,'2026-02-10T00:00:00',2,'shipped'),(4,-2,'2026-02-20T00:00:00',None,'shipped')]
conn.executemany('INSERT INTO orders VALUES(?,?,?,?,?)',orders)
conn.executemany('INSERT INTO customers VALUES(?,?)',[(1,'Ada'),(2,'Grace')])
expected={'select_filter_limit':[(2,15),(3,25)],'aggregate_month':[('2026-01-01',20),('2026-02-01',23)],'implicit_select':orders[:3],'parameter_string':[(1,),(3,),(4,)],'join_customer':[(1,'Ada'),(2,'Ada'),(3,'Grace'),(4,None)]}
execution=[]
for r in compiled['results']:
 if r['engine']!='sqlite':continue
 entry={k:r[k] for k in ['case','mode','status']}
 if r['status']=='failure':
  entry['validation']='expected_rejection' if r['case']=='unknown_field' else 'unexpected_failure'
 else:
  try:
   rows=conn.execute(r['output']['query'],r['output'].get('params') or []).fetchall()
   entry['rows']=rows
   if r['case']=='join_customer' and r['mode']=='driver_direct':
    assert sorted(rows,key=repr)!=sorted(expected[r['case']],key=repr),'The direct compiler unexpectedly preserved full join semantics'
    entry['validation']='confirmed_semantic_divergence';entry['expected_rows']=expected[r['case']]
   else:
    assert sorted(rows,key=repr)==sorted(expected[r['case']],key=repr),(rows,expected.get(r['case']))
    entry['validation']='matches_independent_expected_fixture'
  except Exception as exc:
   entry['execution_error']=f'{type(exc).__name__}: {exc}'
   entry['validation']='expected_execution_failure' if r['case'] in ['native_passthrough','unknown_field'] else 'unexpected_failure'
 execution.append(entry)
summary={'metabase_version':VERSION,'commit':COMMIT,'jar_sha256':actual,'jar_bytes':jar.stat().st_size,'java_version':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.strip(),'bundled_clojure_version':'1.12.3','cold_process_seconds':seconds,'peak_rss_kib':resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,'bootstrap':bootstrap,'compiler_results':compiled['results'],'sqlite_fixture':orders,'sqlite_execution':execution,'limitations':['Postgres SQL compiled but not executed on PostgreSQL server.','Full Metabase uberjar reused; this does not prove source-level dependency trimming.','Application/Quartz pool factories overridden during namespace load; connection attempts throw.','Metadata IDs are synthetic local compiler IDs, not persisted Metabase IDs.','Portable external name-reference API not exercised by this probe.']}
pathlib.Path('results.json').write_text(json.dumps(summary,indent=2))
print(json.dumps({k:summary[k] for k in ['metabase_version','commit','jar_bytes','cold_process_seconds','peak_rss_kib','bootstrap']},indent=2))
print('compile:',len(compiled['results']),'cases; successful SQL emission:',sum(r['status']=='success' for r in compiled['results']))
print('sqlite validation:',[(r['case'],r['mode'],r['validation']) for r in execution])
assert not any(r['validation']=='unexpected_failure' for r in execution)
