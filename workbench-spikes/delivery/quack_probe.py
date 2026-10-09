"""Probe shared local DuckDB ownership using documented Quack protocol."""
import json
import pathlib
import socket
import subprocess
import sys
import time

import duckdb

base=pathlib.Path(__file__).parent
with socket.socket() as s:
    s.bind(('127.0.0.1',0))
    port=s.getsockname()[1]
uri=f'quack:localhost:{port}'
path=base/'quack.duckdb'
conn=duckdb.connect(str(path))
conn.execute('CREATE OR REPLACE TABLE concurrent_facts(i BIGINT)')
server=conn.execute('CALL quack_serve(?)',[uri]).fetchone()
token=server[2] # process-generated local capability; never print

worker='''
import json,sys,duckdb
cfg=json.loads(sys.stdin.read())
c=duckdb.connect()
start=cfg['start']
sql=f"INSERT INTO concurrent_facts SELECT range FROM range({start},{start+1000})"
c.execute("FROM quack_query(?, ?, token=?)",[cfg['uri'],sql,cfg['token']]).fetchall()
result=c.execute("FROM quack_query(?, ?, token=?)",[cfg['uri'],'SELECT COUNT(*) AS n FROM concurrent_facts',cfg['token']]).fetchone()[0]
print(json.dumps({'worker':start,'observed_rows':result}))
'''
start=time.monotonic()
workers=[]
try:
    # Spawn independent processes with independent client DBs.
    for i in range(3):
        p=subprocess.Popen([sys.executable,'-c',worker],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        p.stdin.write(json.dumps({'uri':uri,'token':token,'start':i*1000}))
        p.stdin.close()
        p.stdin=None
        workers.append(p)
    outputs=[p.communicate(timeout=20) for p in workers]
    details=[]
    for p,(stdout,stderr) in zip(workers,outputs):
        # Error strings might reveal server token in SQL; redact defensively.
        details.append({'returncode':p.returncode,'stdout':stdout.strip(),'stderr':stderr.replace(token,'[REDACTED]')[-700:]})
    rows,distinct=conn.execute('SELECT COUNT(*),COUNT(DISTINCT i) FROM concurrent_facts').fetchone()
    result={'pass':all(p.returncode==0 for p in workers) and rows==distinct==3000,'duckdb':duckdb.__version__,'concurrent_processes':len(workers),'final_rows':rows,'distinct_rows':distinct,'seconds':round(time.monotonic()-start,3),'workers':details,'limitation':'beta protocol; no dbt integration, server recovery, remote cancellation, or OS portability validated'}
finally:
    conn.execute('CALL quack_stop(?)',[uri])
    conn.close()
    for p in workers:
        if p.poll() is None:p.kill();p.wait()
(base/'quack_results.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
sys.exit(0 if result['pass'] else 1)
