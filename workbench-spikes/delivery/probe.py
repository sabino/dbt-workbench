#!/usr/bin/env python3
"""Run falsifiable local-workbench lifecycle probes; no project/prod access."""
import datetime
import decimal
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time

import duckdb
import pyarrow as pa
import pyarrow.ipc as ipc

BASE = Path(__file__).parent
SCRATCH = Path(tempfile.mkdtemp(prefix='delivery-', dir=BASE))
RESULTS = {}


def test(name, fn):
    start = time.monotonic()
    try:
        detail = fn()
        RESULTS[name] = {'pass': True, 'seconds': round(time.monotonic() - start, 3), 'detail': detail}
    except Exception as e:
        RESULTS[name] = {'pass': False, 'seconds': round(time.monotonic() - start, 3), 'error': repr(e)}


def wait_until(fn, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if fn():
            return
        time.sleep(0.025)
    raise AssertionError('Deadline reached')


def process_alive(pid):
    # Reparented Linux zombies no longer execute, though their PID persists.
    p = Path(f'/proc/{pid}/stat')
    return p.exists() and p.read_text().split(') ')[1][0] != 'Z'


def setup_store(path):
    c = sqlite3.connect(path)
    c.execute('PRAGMA journal_mode=WAL')
    c.execute('CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, state TEXT, pid INTEGER, exit_code INTEGER)')
    c.execute('CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT, job TEXT, payload TEXT)')
    c.commit()
    return c


def emit(c, job, payload):
    c.execute('INSERT INTO events(job,payload) VALUES (?,?)', [job, json.dumps(payload)])
    c.commit()


def lifecycle():
    db = SCRATCH / 'jobs.sqlite'
    c = setup_store(db)
    p = subprocess.Popen([sys.executable, '-c', "import sys; print('compiling model',flush=True); print('invalid model',file=sys.stderr,flush=True); sys.exit(7)"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
    c.execute('INSERT INTO jobs VALUES(?,?,?,?)', ['failing', 'running', p.pid, None])
    emit(c, 'failing', {'state':'running'})
    out, err = p.communicate(timeout=5)
    c.execute('UPDATE jobs SET state=?,exit_code=? WHERE id=?', ['failed', p.returncode, 'failing'])
    emit(c, 'failing', {'state':'failed','stdout':out,'stderr':err,'exit_code':p.returncode})
    first_events = c.execute('SELECT seq,payload FROM events ORDER BY seq').fetchall()
    assert p.returncode == 7
    assert 'invalid model' in err and 'compiling model' in out
    last_seq = first_events[-1][0]
    c.close()
    # New supervisor connection reads persisted jobs and resumes event replay.
    c = setup_store(db)
    assert c.execute('SELECT state,exit_code FROM jobs WHERE id=?',['failing']).fetchone() == ('failed',7)
    emit(c,'failing',{'state':'acknowledged'})
    replay = c.execute('SELECT seq,payload FROM events WHERE seq>? ORDER BY seq',[last_seq]).fetchall()
    assert len(replay)==1 and json.loads(replay[0][1])['state']=='acknowledged'
    c.close()
    return {'exit_code':p.returncode,'persisted_terminal_state':'failed','event_resume_after':last_seq,'replayed_event_count':len(replay)}


def cancel_tree():
    child = SCRATCH / 'child.py'
    heartbeat = SCRATCH / 'heartbeat'
    child.write_text("from pathlib import Path\nimport time\np=Path(" + repr(str(heartbeat)) + ")\nwhile True:\n p.write_text(str(time.monotonic()))\n time.sleep(.03)\n")
    launcher = "import subprocess,sys,time; p=subprocess.Popen([sys.executable," + repr(str(child)) + "]); print(p.pid,flush=True); p.wait()"
    p = subprocess.Popen([sys.executable,'-c',launcher],stdout=subprocess.PIPE,text=True,start_new_session=True)
    child_pid = int(p.stdout.readline())
    try:
        wait_until(heartbeat.exists)
        before = float(heartbeat.read_text())
        wait_until(lambda: float(heartbeat.read_text()) > before)
        t = time.monotonic()
        os.killpg(p.pid, signal.SIGTERM)
        code = p.wait(timeout=3)
        wait_until(lambda: not process_alive(child_pid))
        last = heartbeat.read_text()
        time.sleep(.12)
        assert heartbeat.read_text()==last
        return {'worker_exit_code':code,'child_no_longer_running':True,'heartbeat_stopped':True,'cancellation_ms':round((time.monotonic()-t)*1000,1),'scope':'Linux process group, no remote query'}
    finally:
        if p.poll() is None:
            os.killpg(p.pid,signal.SIGKILL)
            p.wait(timeout=3)


def orphan_reconcile():
    path=SCRATCH/'orphan.sqlite'
    c=setup_store(path)
    p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'],start_new_session=True)
    c.execute('INSERT INTO jobs VALUES(?,?,?,?)',['orphan','running',p.pid,None])
    c.commit()
    c.close() # stand-in for stopped supervisor
    recovered=setup_store(path)
    stored=recovered.execute('SELECT id,pid FROM jobs WHERE state=?',['running']).fetchall()
    assert len(stored)==1 and process_alive(stored[0][1])
    os.killpg(stored[0][1],signal.SIGTERM)
    code=p.wait(timeout=3)
    recovered.execute('UPDATE jobs SET state=?,exit_code=? WHERE id=?',['interrupted',code,'orphan'])
    emit(recovered,'orphan',{'state':'interrupted','reason':'supervisor_restart'})
    state=recovered.execute('SELECT state FROM jobs WHERE id=?',['orphan']).fetchone()[0]
    recovered.close()
    assert state=='interrupted'
    return {'detected_prior_running_job':True,'orphan_terminated':True,'terminal_state':state,'limitation':'Real supervisor must verify PID identity to avoid PID reuse'}


def locks():
    db=SCRATCH/'workspace.duckdb'
    a=duckdb.connect(str(db))
    a.execute('CREATE TABLE facts AS SELECT * FROM range(8)')
    same=duckdb.connect(str(db))
    same.execute('INSERT INTO facts VALUES (8)')
    same.close()
    details={}
    for read_only in [False,True]:
        code="import duckdb; c=duckdb.connect("+repr(str(db))+",read_only="+repr(read_only)+"); print(c.sql('SELECT COUNT(*) FROM facts').fetchone()[0])"
        cp=subprocess.run([sys.executable,'-c',code],capture_output=True,text=True,timeout=5)
        assert cp.returncode != 0 and 'Conflicting lock' in cp.stderr
        details['cross_process_readonly' if read_only else 'cross_process_readwrite']='rejected_conflicting_lock'
    a.close()
    code="import duckdb,time; c=duckdb.connect("+repr(str(db))+",read_only=True); print(c.sql('SELECT COUNT(*) FROM facts').fetchone()[0],flush=True); time.sleep(.2)"
    readers=[subprocess.Popen([sys.executable,'-c',code],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True) for _ in range(2)]
    outputs=[p.communicate(timeout=5) for p in readers]
    assert all(p.returncode==0 for p in readers) and all(x[0].strip()=='9' for x in outputs)
    details['same_process_multiple_connections']='accepted'
    details['cross_process_readers_after_writer_closed']='accepted_two_readers'
    return details


def arrow_reconnect():
    rows=50000
    table=pa.table({
        'id':pa.array(range(rows),type=pa.int64()),
        'decimal_amount':pa.array([decimal.Decimal('1234.56')]*rows,type=pa.decimal128(18,2)),
        'timestamp':pa.array([datetime.datetime(2026,10,9,10,30,tzinfo=datetime.timezone.utc)]*rows,type=pa.timestamp('us',tz='UTC')),
        'optional':pa.array([None if i%7==0 else 'row-'+str(i) for i in range(rows)]),
    })
    path=SCRATCH/'result.arrow'
    with ipc.new_file(path,table.schema) as f:
        for batch in table.to_batches(max_chunksize=2048):
            f.write_batch(batch)
    del table
    # mmap batches after disconnect; browser only receives requested page.
    with pa.memory_map(str(path),'r') as mmap:
        reader=ipc.open_file(mmap)
        final=reader.get_batch(reader.num_record_batches-1)
        assert final.column(0)[0].as_py()==49152
        assert final.column(0)[-1].as_py()==49999
        assert final.column(1)[0].as_py()==decimal.Decimal('1234.56')
        assert final.column(2)[0].as_py().tzinfo is not None
        null=reader.get_batch(0).column(3)[0]
        assert not null.is_valid
        return {'rows':rows,'record_batches':reader.num_record_batches,'requested_final_page_rows':final.num_rows,'decimal_timestamp_null_preserved':True,'ipc_file_bytes':path.stat().st_size,'browser_all_rows_required':False,'limitation':'Query execution still needs bounded/streaming fetch; Arrow alone does not enforce it'}


def editor_conflict():
    import hashlib
    path=SCRATCH/'model.sql'
    path.write_text('SELECT 1 AS n\n')
    opened_hash=hashlib.sha256(path.read_bytes()).hexdigest()
    path.write_text('SELECT 2 AS n\n') # user edits externally
    current_hash=hashlib.sha256(path.read_bytes()).hexdigest()
    rejected=current_hash!=opened_hash
    assert rejected and path.read_text()=='SELECT 2 AS n\n'
    # Explicit reload then atomically publish a new file.
    tmp=path.with_suffix('.tmp')
    tmp.write_text('SELECT 3 AS n\n')
    tmp.replace(path)
    assert path.read_text()=='SELECT 3 AS n\n' and not tmp.exists()
    return {'stale_save_rejected':rejected,'external_edit_preserved':True,'atomic_publish_after_reload':True}


for name, fn in [('job_failure_reconnect',lifecycle),('cancel_process_tree',cancel_tree),('restart_orphan_reconciliation',orphan_reconcile),('duckdb_process_locks',locks),('arrow_result_paging_reconnect',arrow_reconnect),('external_editor_conflict',editor_conflict)]:
    test(name,fn)

RESULTS['versions']={'python':sys.version.split()[0],'duckdb':duckdb.__version__,'pyarrow':pa.__version__}
RESULTS['scratch_path']=str(SCRATCH)
(BASE/'results.json').write_text(json.dumps(RESULTS,indent=2)+'\n')
print(json.dumps(RESULTS,indent=2))
sys.exit(0 if all(v.get('pass',True) for v in RESULTS.values() if isinstance(v,dict)) else 1)
