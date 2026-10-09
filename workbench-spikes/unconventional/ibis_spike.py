from pathlib import Path
import datetime,json,math,sqlite3,traceback,importlib.metadata as md
import duckdb,ibis
BASE=Path(__file__).resolve().parent
res={'versions':{p:md.version(p) for p in ['ibis-framework','sqlglot','duckdb']},'sqlite':sqlite3.sqlite_version,'tests':[],'compile_only':[]}
schema={'id':'int64','grp':'string','numerator':'int64','denominator':'int64','amount':'int64','status':'string','ts':'string','raw_number':'string'}
t=ibis.table(schema,name='input_data')
records=[(1,'a',3,2,20,'completed','2026-10-02 12:45:00','3'),(2,'a',-3,2,5,'completed','2026-10-20 08:00:00','invalid'),(3,'b',4,0,7,'completed','2026-11-01 00:00:00','4'),(4,'b',None,2,10,'cancelled',None,None)]
duck=duckdb.connect()
sq=sqlite3.connect(':memory:')
create='CREATE TABLE input_data(id BIGINT,grp VARCHAR,numerator BIGINT,denominator BIGINT,amount BIGINT,status VARCHAR,ts VARCHAR,raw_number VARCHAR)'
for conn in [duck,sq]:
 conn.execute(create)
 conn.executemany('INSERT INTO input_data VALUES(?,?,?,?,?,?,?,?)',records)
def norm(v):
 if isinstance(v,(datetime.date,datetime.datetime)): return v.isoformat(sep=' ') if isinstance(v,datetime.datetime) else v.isoformat()
 if isinstance(v,float) and math.isinf(v):return 'infinity' if v>0 else '-infinity'
 if isinstance(v,float) and math.isnan(v):return 'nan'
 return v
# Same typed expressions target both engines; no rewritten source SQL.
cases=[
 ('aggregate_filter', t.filter(t.status=='completed').group_by('grp').aggregate(total=t.amount.sum()).order_by('grp')),
 ('integer_true_division_nonzero',t.filter(t.denominator!=0).select('id',value=t.numerator/t.denominator).order_by('id')),
 ('integer_true_division_zero',t.select('id',value=t.numerator/t.denominator).order_by('id')),
 ('explicit_null_on_zero_division',t.select('id',value=t.numerator/t.denominator.nullif(0)).order_by('id')),
 ('month_truncation',t.select('id',month=t.ts.cast('timestamp').truncate('M')).order_by('id')),
 ('window_rank',t.select('id',position=ibis.row_number().over(ibis.window(group_by=t.grp,order_by=t.id))).order_by('id')),
 ('self_join',t.join(t.view(),['id']).select(t.id,t.amount).order_by(t.id)),
 ('literal_escaping',t.filter(t.status=="completed' OR 1=1 --").select('id').order_by('id')),
 ('try_cast',t.select('id',value=t.raw_number.try_cast('int64')).order_by('id')),
 ('array_length',ibis.array([1,2,3]).length()),
]
for name,expr in cases:
 row={'name':name,'engines':{}}
 for dialect,conn in [('duckdb',duck),('sqlite',sq)]:
  try:
   sql=ibis.to_sql(expr,dialect=dialect)
   row['engines'][dialect]={'sql':sql,'rows':[[norm(v) for v in r] for r in conn.execute(sql).fetchall()]}
  except Exception as e:row['engines'][dialect]={'error_type':type(e).__name__,'error':str(e)}
 outputs=list(row['engines'].values())
 row['both_executed']=all('rows' in x for x in outputs)
 row['equal_rows']=row['both_executed'] and outputs[0]['rows']==outputs[1]['rows']
 res['tests'].append(row)
for name,expr in [('aggregate_filter',cases[0][1]),('integer_division',cases[2][1]),('try_cast',cases[-2][1]),('array_length',cases[-1][1])]:
 row={'name':name,'engines':{}}
 for dialect in ['postgres','snowflake','bigquery']:
  try:row['engines'][dialect]={'sql':ibis.to_sql(expr,dialect=dialect),'status':'compiled_only_no_live_engine_validation'}
  except Exception as e:row['engines'][dialect]={'error_type':type(e).__name__,'error':str(e)}
 res['compile_only'].append(row)
(BASE/'ibis-results.json').write_text(json.dumps(res,indent=2))
print(json.dumps({'versions':res['versions'],'tests':[{k:v for k,v in t.items() if k!='engines'} for t in res['tests']]},indent=2))
