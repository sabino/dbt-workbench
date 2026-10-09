"""Probe bounded typed results from engine through Arrow without Python rows."""
import json,pathlib,resource,time
import duckdb,pyarrow as pa,pyarrow.ipc as ipc

base=pathlib.Path(__file__).parent
c=duckdb.connect(config={'threads':2,'memory_limit':'256MB'})
c.execute("SELECT range AS id, repeat('x',256) AS payload, CAST(range/100.0 AS DECIMAL(18,2)) AS amount FROM range(1000000)")
reader=c.fetch_record_batch(rows_per_batch=2048)
before=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
started=time.monotonic();count=0;batches=0
path=base/'millionrows.arrow'
with ipc.new_file(path,reader.schema) as writer:
 for batch in reader:
  count+=batch.num_rows;batches+=1
  writer.write_batch(batch)
peak=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
c.close()
with pa.memory_map(str(path),'r') as mmap:
 read=ipc.open_file(mmap)
 last=read.get_batch(read.num_record_batches-1)
 assert last.column(0)[-1].as_py()==999999
out={'pass':count==1000000,'rows':count,'batches':batches,'record_batch_max_rows':2048,'ipc_bytes':path.stat().st_size,'incremental_maxrss_mb':round((peak-before)/1024,1),'maxrss_mb':round(peak/1024,1),'seconds':round(time.monotonic()-started,3),'duckdb':duckdb.__version__,'pyarrow':pa.__version__,'limitation':'synthetic scan; complex queries can require larger engine memory/spill budgets'}
(base/'stream_results.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out,indent=2));path.unlink()
