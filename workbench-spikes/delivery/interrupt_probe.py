"""Verify direct DuckDB execution has a confirmed cancellation primitive."""
import json,pathlib,threading,time
import duckdb

c=duckdb.connect(config={'threads':2})
started=threading.Event();out={}
def run():
 started.set()
 try:
  c.execute('SELECT SUM(sin(i)) FROM range(200000000) t(i)').fetchone()
  out['state']='completed'
 except Exception as e:
  out['state']='interrupted' if isinstance(e,duckdb.InterruptException) else 'failed'
  out['exception']=type(e).__name__
worker=threading.Thread(target=run)
worker.start();started.wait();time.sleep(.1)
t=time.monotonic();c.interrupt();worker.join(timeout=3)
out['thread_stopped']=not worker.is_alive();out['interrupt_ms']=round((time.monotonic()-t)*1000,1)
if worker.is_alive():raise AssertionError('Worker did not acknowledge interrupt')
out['connection_reusable']=c.execute('SELECT 42').fetchone()[0]==42
c.close();out['pass']=out['state']=='interrupted' and out['thread_stopped'] and out['connection_reusable']
out['duckdb']=duckdb.__version__
pathlib.Path(__file__).with_name('interrupt_results.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out,indent=2))
raise SystemExit(0 if out['pass'] else 1)
