#!/usr/bin/env python3
"""Controlled profiling on an owned emulator; no application classes or private URLs are logged."""
import argparse,json,pathlib,re,statistics,subprocess,time
parser=argparse.ArgumentParser()
parser.add_argument('--adb',default=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'))
parser.add_argument('--serial',required=True)
parser.add_argument('--mode',choices=['runtime','ui'],required=True)
parser.add_argument('--out',required=True)
a=parser.parse_args();out=pathlib.Path(a.out);out.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output([a.adb,'-s',a.serial,*args],text=True)
def pss(pid,label):
 raw=adb('shell','dumpsys','meminfo',str(pid));(out/(label+'.txt')).write_text(raw)
 match=re.search(r'TOTAL PSS:\s*(\d+)',raw) or re.search(r'TOTAL:\s*(\d+)',raw)
 return round(int(match.group(1))/1024,2) if match else None
report={'serial':a.serial,'sdk':adb('shell','getprop','ro.build.version.sdk').strip(),'abi':adb('shell','getprop','ro.product.cpu.abi').strip(),'workload':a.mode}
if a.mode=='runtime':
 with open(out/'runtime-test.log','w') as log:
  process=subprocess.Popen([a.adb,'-s',a.serial,'shell','am','instrument','-w','-e','holdMs','20000','-e','class','com.yuno.tv.RuntimeReleaseSmokeTest','com.yuno.tv.releaseqa/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT)
  found={};deadline=time.monotonic()+12
  while time.monotonic()<deadline:
   for row in adb('shell','ps','-A').splitlines():
    columns=row.split()
    if len(columns)<3:continue
    name=columns[-1]
    label='js' if name.startswith('com.yuno.tv:javascript') else 'spider' if name.startswith('com.yuno.tv:spider') else 'main' if name=='com.yuno.tv' else None
    if label:found[label]=(columns[0],columns[1])
   if len(found)==3:break
   time.sleep(.2)
  report['processes']={label:{'uid':uid,'pssMiB':pss(pid,label)} for label,(uid,pid) in found.items()}
  process.wait(timeout=30)
 report['testPass']='OK (1 test)' in (out/'runtime-test.log').read_text()
else:
 # Select Home using only D-pad before profiling cached process cold starts.
 adb('shell','am','start','-W','-n','com.yuno.tv/.MainActivity');time.sleep(1)
 for key in [19]*6+[21]*8+[23]:adb('shell','input','keyevent',str(key));time.sleep(.15)
 time.sleep(1);starts=[]
 for i in range(5):
  adb('shell','am','force-stop','com.yuno.tv')
  raw=adb('shell','am','start','-W','-n','com.yuno.tv/.MainActivity');(out/('cold-'+str(i)+'.txt')).write_text(raw)
  match=re.search(r'TotalTime:\s*(\d+)',raw)
  if match:starts.append(int(match.group(1)))
  time.sleep(1)
 time.sleep(2)
 report.update(coldStartMs=starts,coldStartMedianMs=statistics.median(starts),mainPssMiB=pss(adb('shell','pidof','com.yuno.tv').strip(),'home'))
 adb('shell','dumpsys','gfxinfo','com.yuno.tv','reset')
 for key in ([20,22,22,21,19,21]*10):adb('shell','input','keyevent',str(key));time.sleep(.15)
 raw=adb('shell','dumpsys','gfxinfo','com.yuno.tv');(out/'frames.txt').write_text(raw)
 total=re.search(r'Total frames rendered:\s*(\d+)',raw);jank=re.search(r'Janky frames:\s*(\d+)\s*\(([\d.]+)%\)',raw)
 report.update(frames=int(total.group(1)) if total else None,jankPercent=float(jank.group(2)) if jank else None)
 adb('shell','input','keyevent','3');time.sleep(2)
 adb('shell','am','start','-W','-n','com.yuno.tv/.MainActivity');time.sleep(2)
 report['backgroundReturnPssMiB']=pss(adb('shell','pidof','com.yuno.tv').strip(),'background-return')
(out/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False))
