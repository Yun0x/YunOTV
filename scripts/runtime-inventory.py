#!/usr/bin/env python3
# Remote inventory artifacts remain private; reports never include raw JS or URL credentials.
import argparse,json,urllib.request,pathlib,collections,concurrent.futures,hashlib,re,zipfile,io
from urllib.parse import urlsplit,urlunsplit
args=argparse.ArgumentParser()
args.add_argument('--sites',required=True)
args.add_argument('--work-dir',required=True)
args.add_argument('--report',required=True)
options=args.parse_args()
def safe_url(url):
 try:
  value=urlsplit(url);return urlunsplit((value.scheme,value.hostname or '',value.path,'',''))
 except ValueError:return ''

p=pathlib.Path(options.work_dir);p.mkdir(parents=True,exist_ok=True);sites=json.loads(pathlib.Path(options.sites).read_text());dr=[s for s in sites if s['capability']['kind']=='DRPY_JS'];sp=[s for s in sites if s['capability']['kind']=='SPIDER_JAR'];jarcounts=collections.Counter(s['metadata']['spider'].split(';')[0] for s in sp)
urls=set(jarcounts)
# Group by runtime family, then round-robin rules to sample more than one engine.
families=collections.defaultdict(list)
for s in dr:families[s['api']].append(s)
sample=[]
for i in range(8):
 for family,rows in sorted(families.items(),key=lambda kv:-len(kv[1])):
  if i<len(rows) and len(sample)<24:sample.append(rows[i])
for s in sample:
 urls.add(s['api']);
 if s['ext'].startswith(('http://','https://')):urls.add(s['ext'])
(p/'artifacts').mkdir(exist_ok=True)
def load(u):
 try:
  req=urllib.request.Request(u,headers={'User-Agent':'YunoTV-Inventory/0.3'})
  with urllib.request.urlopen(req,timeout=15) as r:
   b=r.read(24*1024*1024+1)
   if len(b)>24*1024*1024:raise ValueError('SIZE_LIMIT')
  h=hashlib.sha256(b).hexdigest();(p/'artifacts'/h).write_bytes(b)
  info={'sha256':h,'bytes':len(b),'status':'DOWNLOADED'}
  if u in jarcounts:
   z=zipfile.ZipFile(io.BytesIO(b));info['dexEntries']=[f for f in z.namelist() if re.match(r'classes\d*\.dex$',f)];info['jarSites']=jarcounts[u]
  else:
   t=b.decode('utf-8-sig');info['helpers']=dict(collections.Counter(re.findall(r'\b(request|req|fetch|post|pdfh|pdfa|pd|jsp|cheerio|CryptoJS|md5|base64Encode|base64Decode|getItem|setItem|local|jsoup|require)\s*[.(]',t)));info['async']=bool(re.search(r'\basync\s+function',t))
  return u,info
 except Exception as e:return u,{'status':'FAILED','error':('HTTP_'+str(e.code)) if hasattr(e,'code') else type(e).__name__}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:results=dict(pool.map(load,urls))
(p/'artifact-index.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
(p/'drpy-sample.json').write_text(json.dumps(sample,ensure_ascii=False))
report={'siteCount':len(sites),'drpySites':len(dr),'spiderSites':len(sp),'uniqueJarUrls':len(jarcounts),'uniqueClasses':len(set(s['api'] for s in sp)),'uniqueDownloadedJarHashes':len(set(results[u]['sha256'] for u in jarcounts if 'sha256' in results[u])),'jars':[{'url':safe_url(u),'sites':n,**results[u]} for u,n in jarcounts.most_common()],'sampleCount':len(sample),'jsHelpers':dict(sum((collections.Counter(r.get('helpers',{})) for r in results.values()),collections.Counter())),'sample':[{'name':s['name'],'runtime':results[s['api']],'script':results.get(s['ext'],{})} for s in sample]}
pathlib.Path(options.report).write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in report.items() if k not in ['sample']},ensure_ascii=False,indent=2),flush=True)
