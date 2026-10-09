from pathlib import Path
import json, os, shutil, subprocess, time, importlib.metadata
ROOT=Path('/tmp/workbench-spikes/dbt')
CLI=ROOT/'v2-venv/bin/dbt'
PROJECT=ROOT/'v2-project'
if PROJECT.exists():shutil.rmtree(PROJECT)
(PROJECT/'models').mkdir(parents=True)
(PROJECT/'dbt_project.yml').write_text('name: v2_probe\nversion: "1.0"\nconfig-version: 2\nprofile: v2_probe\nmodels:\n  v2_probe:\n    +materialized: table\n')
(PROJECT/'profiles.yml').write_text(f'''v2_probe:
  target: dev
  outputs:
    dev:
      type: duckdb
      path: {PROJECT}/dev.duckdb
      schema: main
      threads: 1
''')
(PROJECT/'models/base.sql').write_text('select 1::integer as id\n')
(PROJECT/'models/child.sql').write_text("select id * 2 as doubled from {{ ref('base') }}\n")
schema='''version: 2
models:
  - name: child
    columns:
      - name: doubled
        data_tests:
          - not_null
          - unique
'''
(PROJECT/'models/schema.yml').write_text(schema)
RESULTS=[]
def run(label,command,target='dev',*extra,env_extra=None,project=PROJECT):
    cmd=[str(CLI),command,'--project-dir',str(project),'--profiles-dir',str(PROJECT),'--target',target,'--target-path',str(ROOT/'v2-outputs'/label),'--log-path',str(ROOT/'v2-logs'/label),*extra]
    env=os.environ.copy();env.update(env_extra or {})
    started=time.monotonic()
    p=subprocess.run(cmd,text=True,capture_output=True,env=env,timeout=60)
    (ROOT/f'v2-{label}.stdout.txt').write_text(p.stdout)
    (ROOT/f'v2-{label}.stderr.txt').write_text(p.stderr)
    rr=ROOT/'v2-outputs'/label/'run_results.json'
    compiled_dir=ROOT/'v2-outputs'/label/'compiled/v2_probe/models'
    result={'label':label,'command':cmd[1:],'env_overrides':env_extra or {},'exit_code':p.returncode,'elapsed_seconds':round(time.monotonic()-started,3),'stdout_excerpt':p.stdout[-5000:],'stderr_excerpt':p.stderr[-5000:]}
    if rr.exists():result['node_results']=[{k:r.get(k) for k in ['unique_id','status','message']} for r in json.loads(rr.read_text())['results']]
    if compiled_dir.exists():result['compiled']={p.stem:p.read_text() for p in compiled_dir.glob('*.sql')}
    RESULTS.append(result)
    print(json.dumps({'label':label,'exit_code':p.returncode,'stdout_excerpt':p.stdout[-600:],'stderr_excerpt':p.stderr[-1000:]}),flush=True)
    return result
version=subprocess.run([str(CLI),'--version'],text=True,capture_output=True,check=True).stdout.strip()
run('native_duckdb_build','build')
# Native unit-test fixtures in a DuckDB-targeted dbt OSS project.
(PROJECT/'models/schema.yml').write_text(schema+'''unit_tests:
  - name: child_doubles
    model: child
    given:
      - input: ref('base')
        rows:
          - {id: 3}
    expect:
      rows:
        - {doubled: 6}
''')
run('native_duckdb_unit_test','test','dev','--select','test_type:unit')
# Experimental compute-local requires both opt-in and eligible translation capabilities.
(PROJECT/'models/schema.yml').write_text(schema+'''unit_tests:
  - name: child_doubles
    model: child
    config:
      compute: local
    given:
      - input: ref('base')
        rows:
          - {id: 3}
    expect:
      rows:
        - {doubled: 6}
''')
run('local_unit_gate_no_optin','test','dev','--select','test_type:unit')
run('local_unit_optin_duckdb','test','dev','--select','test_type:unit',env_extra={'DBT_ENGINE_EXPERIMENTAL_LOCAL_UNIT_TESTS':'true'})
# Adapter config anywhere gates parsing, even if the node isn't selected.
(PROJECT/'models/schema.yml').write_text(schema)
(PROJECT/'models/alternate.sql').write_text("{{ config(adapter='duckdb') }}\nselect 5::integer as id\n")
run('multi_adapter_gate_no_optin','parse','dev','--select','child')
run('multi_adapter_optin_parse','parse','dev','--select','child',env_extra={'DBT_ENGINE_EXPERIMENTAL_MULTI_ADAPTER':'true'})
# Preserve the exact classic repository version gate in a detached copy.
classic=Path('/tmp/workbench-spikes/jaffle-shop-classic')
commit=None
if classic.exists():
    commit=subprocess.run(['git','-C',str(classic),'rev-parse','HEAD'],text=True,capture_output=True,check=True).stdout.strip()
    detached=ROOT/'classic-v2-check'
    if detached.exists():shutil.rmtree(detached)
    shutil.copytree(classic,detached,ignore=shutil.ignore_patterns('.git','target','logs','dbt_packages','.dbt'))
    run('classic_repository_version_gate','parse','dev','--profile','v2_probe',project=detached)
summary={'cli_version':version,'distribution':'dbt-oss','synthetic_inputs':True,'no_remote_credentials':True,'classic_commit':commit,'results':RESULTS,'limitations':'No remote warehouse or cross-engine adapter execution was attempted; local compute and adapter-config probes assess explicit capability gates only.'}
(ROOT/'results_v2.json').write_text(json.dumps(summary,indent=2))
print('RESULT_FILE '+str(ROOT/'results_v2.json'),flush=True)
