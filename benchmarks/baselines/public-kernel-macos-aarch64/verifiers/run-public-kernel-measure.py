from pathlib import Path
import subprocess, json, hashlib, datetime, os
root=Path('/Users/qiouyang/Documents/Claude/Codes/mantra')
evidence=root/'build/release-checks/public-normein';evidence.mkdir(parents=True,exist_ok=True)
lib=root/'benchmarks/build/install/mantra-benchmark/lib'
expected='83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8'
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
assert not subprocess.check_output(['git','status','--porcelain','--untracked-files=all'],cwd=root,text=True).strip()
assert sha(lib/'normein-dsl-0.3.0.jar')==expected
assert (lib/'mantra-core-1.0.0-rc.1.jar').is_file()
def capture(label):
 source=subprocess.check_output(['python3','scripts/performance-source-fingerprint.py','--root',str(root)],cwd=root,text=True)
 (evidence/f'public-source-{label}.json').write_text(source)
 jars=[{'path':str(path.relative_to(root)),'bytes':path.stat().st_size,'sha256':sha(path)} for path in sorted(lib.glob('*.jar'))]
 assert len(jars)==25
 record={'sourceCommit':commit,'engineVersion':'1.0.0-rc.1','normeinVersion':'0.3.0','normeinJarSha256':expected,'runtimeJars':jars}
 (evidence/f'public-runtime-{label}.json').write_text(json.dumps(record,indent=2)+'\n')
def run(label,arguments):
 print(label+' started '+datetime.datetime.now(datetime.timezone.utc).isoformat(),flush=True)
 with (evidence/f'{label}.log').open('w') as log:
  subprocess.run(arguments,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=1600)
 print(label+' passed '+datetime.datetime.now(datetime.timezone.utc).isoformat(),flush=True)
capture('before')
for folder in ['benchmarks/build/public-kernel-performance','benchmarks/build/public-kernel-period-performance','build/out/lease-batch-public-kernel-10000']:
 assert not (root/folder).exists(),folder+' already exists'
run('public-original',[str(root/'benchmarks/build/install/mantra-benchmark/bin/mantra-benchmark'),'--output','benchmarks/build/public-kernel-performance','--warmup','5','--repetitions','10'])
run('public-periods',['java','-Xms512m','-Xmx2g','-XX:+UseG1GC','-Dfile.encoding=UTF-8','-cp',str(lib/'*'),'com.xqiou.mantra.benchmarks.PeriodPerformanceBaseline','--out','benchmarks/build/public-kernel-period-performance','--warmup','5','--repetitions','10'])
run('public-batch',['java','-Xms128m','-Xmx512m','-XX:+UseG1GC','-Dfile.encoding=UTF-8','-cp',str(root/'mantra-cli/build/install/mantra/lib/*')+os.pathsep+str(root/'apps/ifrs-leases/build/libs/ifrs-leases-1.0.0-rc.1.jar'),'com.xqiou.mantra.apps.leases.LeaseBatchDemo','--package-root','apps/ifrs-leases','--reference','build/out/lease-batch-reference.jsonl','--reference-manifest','build/out/lease-batch-reference.manifest.json','--out','build/out/lease-batch-public-kernel-10000','--engine-version','1.0.0-rc.1','--directory-policy','trusted-local','--max-seconds','300'])
capture('after')
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()==commit
assert not subprocess.check_output(['git','status','--porcelain','--untracked-files=all'],cwd=root,text=True).strip()
print('ALL_PUBLIC_KERNEL_MEASUREMENTS_COMPLETED',flush=True)
