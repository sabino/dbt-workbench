from pathlib import Path
import hashlib, importlib.metadata, json, os, queue, shutil, signal, subprocess, threading, time
import duckdb
ROOT=Path('/tmp/workbench-spikes/dbt')
CLI='/tmp/dbt-workbench-feasibility-venv/bin/dbt'
PROJECT=ROOT/'project'
if PROJECT.exists(): shutil.rmtree(PROJECT)
(PROJECT/'models').mkdir(parents=True)
(PROJECT/'dbt_project.yml').write_text('name: extended_probe\nversion: "1.0"\nconfig-version: 2\nprofile: extended_probe\nmodel-paths: [models]\nmodels:\n  extended_probe:\n    +materialized: table\n')
(PROJECT/'profiles.yml').write_text(f'''extended_probe:
  target: dev
  outputs:
    prod:
      type: duckdb
      path: {PROJECT}/prod.duckdb
      schema: main
      threads: 1
    dev:
      type: duckdb
      path: {PROJECT}/dev.duckdb
      schema: main
      threads: 1
      attach:
        - path: {PROJECT}/prod.duckdb
          alias: prod
          read_only: true
    offline:
      type: duckdb
      path: {PROJECT}/offline.duckdb
      schema: main
      threads: 1
''')
def model(name, code): (PROJECT/'models'/f'{name}.sql').write_text(code+'\n')
model('upstream',"select {{ var('upstream_value', 100) }}::integer as id")
model('child',"select id, id * 2 as doubled from {{ ref('upstream') }}")
model('ephemeral_parent',"{{ config(materialized='ephemeral') }}\nselect id + 1 as id from {{ ref('upstream') }}")
model('ephemeral_child',"select id * 2 as doubled from {{ ref('ephemeral_parent') }}")
model('source_child',"select id from {{ source('raw', 'events') }}")
model('incremental_model',"""{{ config(materialized='incremental', unique_key='id', incremental_strategy='delete+insert') }}
select id, value, updated_at from {{ source('raw', 'changes') }}
{% if is_incremental() %}
where updated_at > (select coalesce(max(updated_at), TIMESTAMP '1900-01-01') from {{ this }})
{% endif %}""")
model('introspective_model',"""{% if execute %}
{% set result = run_query('select max(id) as max_id from ' ~ source('raw', 'events')) %}
{% set max_id = result.columns[0].values()[0] %}
{% else %}
{% set max_id = 0 %}
{% endif %}
select {{ max_id }}::integer as seen_at_compile""")
model('long_running_model',"select sum(sqrt(i::double)) as value from range(10000000000) t(i)")
(PROJECT/'models/schema.yml').write_text('''version: 2
sources:
  - name: raw
    database: "{{ var('raw_catalog', target.database) }}"
    schema: raw
    tables:
      - name: events
      - name: changes
models:
  - name: incremental_model
    columns:
      - name: id
        data_tests:
          - unique
          - not_null
''')
for dbname, source_id in [('prod',10),('dev',1000)]:
    with duckdb.connect(str(PROJECT/f'{dbname}.duckdb')) as c:
        c.sql('create schema raw')
        c.sql(f'create table raw.events as select {source_id}::integer as id')
        c.sql("create table raw.changes as select * from (values (1,'a',TIMESTAMP '2026-01-01'),(2,'b',TIMESTAMP '2026-01-02')) t(id,value,updated_at)")
RESULTS=[]
def args(label, command, target, extra):
    outdir=PROJECT/'outputs'/label
    return [CLI,command,'--project-dir',str(PROJECT),'--profiles-dir',str(PROJECT),'--target',target,'--target-path',str(outdir),'--log-path',str(PROJECT/'logs'/label),*extra],outdir
def run(label,command,target,*extra,queries=None):
    cmd,outdir=args(label,command,target,extra)
    started=time.monotonic()
    p=subprocess.run(cmd,text=True,capture_output=True,timeout=60)
    (ROOT/f'{label}.stdout.txt').write_text(p.stdout)
    (ROOT/f'{label}.stderr.txt').write_text(p.stderr)
    result={'label':label,'command':cmd[1:],'exit_code':p.returncode,'elapsed_seconds':round(time.monotonic()-started,3),'compiled':{}}
    compiled_root=outdir/'compiled/extended_probe/models'
    if compiled_root.exists():
        result['compiled']={path.stem:path.read_text().strip() for path in compiled_root.glob('*.sql')}
    rr=outdir/'run_results.json'
    if rr.exists(): result['node_results']=[{k:n.get(k) for k in ('unique_id','status','message')} for n in json.loads(rr.read_text())['results']]
    if queries:
        with duckdb.connect(str(PROJECT/f'{target}.duckdb'),read_only=True) as c:
            result['query_results']={name:[list(row) for row in c.sql(sql).fetchall()] for name,sql in queries.items()}
        # stringify timestamps for reproducible JSON
        result['query_results']=json.loads(json.dumps(result['query_results'],default=str))
    if p.returncode:
        result['error_excerpt']=p.stdout[-2000:]
    RESULTS.append(result)
    print(json.dumps({'label':label,'exit_code':result['exit_code'],'node_statuses':[n['status'] for n in result.get('node_results',[])],'query_results':result.get('query_results')}),flush=True)
    return outdir
prod_out=run('prod_initial','build','prod','--select','upstream child source_child')
STATE=PROJECT/'state/prod'
STATE.mkdir(parents=True)
shutil.copy2(prod_out/'manifest.json',STATE/'manifest.json')
state_before=hashlib.sha256((STATE/'manifest.json').read_bytes()).hexdigest()
flags=['--defer','--state',str(STATE)]
run('source_current_definition','build','dev','--select','source_child',*flags,'--favor-state',queries={'source_child':'select * from main.source_child','local_source':'select * from raw.events'})
run('source_explicit_mapping','build','dev','--select','source_child',*flags,'--favor-state','--vars','{"raw_catalog":"prod"}',queries={'source_child':'select * from main.source_child'})
run('ephemeral_deferred_physical_parent','build','dev','--select','ephemeral_child',*flags,'--favor-state',queries={'ephemeral_child':'select * from main.ephemeral_child'})
with duckdb.connect(str(PROJECT/'dev.duckdb')) as c: c.sql('create table main.upstream as select 7::integer as id')
model('upstream',"select {{ var('upstream_value', 999) }}::integer as id")
run('revised_parent_stale_local_default','build','dev','--select','child',*flags,queries={'child':'select * from main.child'})
run('revised_parent_explicit_state_preference','build','dev','--select','child',*flags,'--favor-state',queries={'child':'select * from main.child'})
run('revised_parent_selected','build','dev','--select','upstream child',*flags,'--favor-state',queries={'child':'select * from main.child'})
run('incremental_first_build','build','dev','--select','incremental_model',queries={'incremental':'select * from main.incremental_model order by id'})
with duckdb.connect(str(PROJECT/'dev.duckdb')) as c:
    c.sql("update raw.changes set value='bb',updated_at=TIMESTAMP '2026-01-03' where id=2")
    c.sql("insert into raw.changes values (3,'c',TIMESTAMP '2026-01-03')")
run('incremental_second_build','build','dev','--select','incremental_model',queries={'incremental':'select * from main.incremental_model order by id'})
run('introspection_live_compile','compile','dev','--select','introspective_model')
run('introspection_no_data_compile','compile','offline','--select','introspective_model')
run('introspection_no_introspect_compile','compile','offline','--select','introspective_model','--no-introspect')
# Cancel a genuine dbt subprocess after its heavy DuckDB materialization starts.
label='cancel_heavy_worker'
cmd,outdir=args(label,'build','dev',['--select','long_running_model'])
p=subprocess.Popen(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True,bufsize=1)
lines=[]; q=queue.Queue()
def reader():
    for line in p.stdout:
        lines.append(line);q.put(line)
t=threading.Thread(target=reader,daemon=True);t.start()
started=time.monotonic();saw_start=False
while time.monotonic()-started < 30 and p.poll() is None:
    try: line=q.get(timeout=.2)
    except queue.Empty: continue
    if 'START sql table model' in line and 'long_running_model' in line:
        saw_start=True;break
if p.poll() is None:
    time.sleep(.4)
    os.killpg(p.pid,signal.SIGTERM)
    try:p.wait(timeout=5)
    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
t.join(timeout=2)
(ROOT/f'{label}.stdout.txt').write_text(''.join(lines))
with duckdb.connect(str(PROJECT/'dev.duckdb')) as c:
    recovery=c.sql('select 1').fetchall()
RESULTS.append({'label':label,'command':cmd[1:],'started_heavy_materialization':saw_start,'worker_exit_code':p.returncode,'database_reopened':recovery==[(1,)]})
run('after_cancel_build','build','dev','--select','child',*flags,'--favor-state',queries={'child':'select * from main.child'})
state_after=hashlib.sha256((STATE/'manifest.json').read_bytes()).hexdigest()
checks={
  'source_deferral_does_not_replace_current_definition':RESULTS[1]['query_results']['source_child']==[[1000]],
  'source_explicit_mapping_resolves_prod':RESULTS[2]['query_results']['source_child']==[[10]],
  'ephemeral_contains_cte_and_prod_ref':'__dbt__cte__ephemeral_parent' in RESULTS[3]['compiled']['ephemeral_child'] and '"prod"."main"."upstream"' in RESULTS[3]['compiled']['ephemeral_child'],
  'default_defer_uses_stale_local':RESULTS[4]['query_results']['child']==[[7,14]],
  'favor_state_uses_saved_prod':RESULTS[5]['query_results']['child']==[[100,200]],
  'selected_modified_parent_builds_local':RESULTS[6]['query_results']['child']==[[999,1998]],
  'incremental_first_build_full_sql':'where updated_at >' not in RESULTS[7]['compiled']['incremental_model'],
  'incremental_second_build_destination_filter':'"dev"."main"."incremental_model"' in RESULTS[8]['compiled']['incremental_model'] and 'where updated_at >' in RESULTS[8]['compiled']['incremental_model'],
  'incremental_second_build_updates_without_duplicates':[r[:2] for r in RESULTS[8]['query_results']['incremental']]==[[1,'a'],[2,'bb'],[3,'c']],
  'incremental_tests_pass_both_runs':all(n['status'] in ('success','pass') for idx in [7,8] for n in RESULTS[idx]['node_results']),
  'live_introspection_observes_data':'1000::integer' in RESULTS[9]['compiled']['introspective_model'],
  'offline_introspection_fails':RESULTS[10]['exit_code']!=0,
  'no_introspect_not_universal_offline_mode':RESULTS[11]['exit_code']!=0,
  'cancel_releases_database':RESULTS[12]['started_heavy_materialization'] and RESULTS[12]['worker_exit_code']<0 and RESULTS[12]['database_reopened'],
  'after_cancel_build_succeeds':RESULTS[13]['exit_code']==0,
  'immutable_state_unchanged':state_before==state_after,
}
summary={'versions':{k:importlib.metadata.version(k) for k in ['dbt-core','dbt-duckdb','duckdb']},'synthetic_inputs':True,'no_remote_credentials':True,'project':str(PROJECT),'state_hash_before':state_before,'state_hash_after':state_after,'checks':checks,'all_checks_passed':all(checks.values()),'results':RESULTS}
(ROOT/'results.json').write_text(json.dumps(summary,indent=2))
print(json.dumps({'checks':checks,'all_checks_passed':summary['all_checks_passed'],'result_file':str(ROOT/'results.json')}),flush=True)
if not all(checks.values()):raise SystemExit(1)
