"""Observe whether terminating a client cancels an owned-engine query."""
import json, pathlib, signal, socket, subprocess, sys, time
import duckdb

base=pathlib.Path(__file__).parent
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
uri=f'quack:localhost:{port}'
c=duckdb.connect(config={'threads':2})
token=c.execute('CALL quack_serve(?)',[uri]).fetchone()[2]
marker='delivery_cancellation_probe'
query='/* '+marker+' */ SELECT SUM(sin(i)) FROM range(200000000) t(i)'
worker="import duckdb,json,sys; a=json.loads(sys.stdin.read()); c=duckdb.connect(); c.execute('FROM quack_query(?, ?, token=?)',[a['uri'],a['query'],a['token']]).fetchone(); print('completed')"
p=subprocess.Popen([sys.executable,'-c',worker],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
p.stdin.write(json.dumps({'uri':uri,'query':query,'token':token}));p.stdin.close();p.stdin=None
def active():
 return c.execute('SELECT state FROM quack_active_connections() WHERE query LIKE ?',['%'+marker+'%']).fetchall()
try:
 deadline=time.monotonic()+5
 while time.monotonic()<deadline and not active():time.sleep(.02)
 observed_before=bool(active())
 t=time.monotonic()
 p.terminate();code=p.wait(timeout=3)
 after=[]
 for delay in [0,.05,.15,.3,.5]:
  time.sleep(delay)
  after.append({'elapsed_ms':round((time.monotonic()-t)*1000),'active_states':[r[0] for r in active()]})
 result={'query_observed_before_cancel':observed_before,'client_exit_code':code,'server_after_client_termination':after,'query_may_continue_after_client_kill':any(r['active_states'] for r in after),'duckdb':duckdb.__version__,'scope':'localhostQuack client termination, does not establish finalcancel API behavior'}
finally:
 c.execute('CALL quack_stop(?)',[uri]);c.close()
 if p.poll() is None:p.kill();p.wait()
(base/'quack_cancel_results.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
