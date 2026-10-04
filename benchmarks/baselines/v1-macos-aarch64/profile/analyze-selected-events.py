from pathlib import Path
from collections import Counter
from decimal import Decimal
import json,re,hashlib,codecs,time
source=Path('build/v1-profile/selected-events.json')
identity=json.loads(Path('build/v1-profile/identity-privacy-verification.json').read_text())
started=time.monotonic()
count=Counter();cpuLeaf=Counter();cpuInclusive=Counter();cpuCategory=Counter();cpuStacks=Counter();cpuNamedPhase=Counter()
allocationLeaf=Counter();allocationLeafCount=Counter();allocationCategory=Counter();allocationCategoryCount=Counter();allocationClasses=Counter()
allocationWeight=0;allocationExamples={};gc=[];pauses=[];truncated=Counter();peakBuffer=0;rawHash=hashlib.sha256();bytesRead=0
utf=codecs.getincrementaldecoder('utf-8')();decoder=json.JSONDecoder();chunkBytes=1048576

def methods(value):
    trace=value.get('stackTrace') or {}
    frames=trace.get('frames') or []
    truncated[bool(trace.get('truncated'))]+=1
    return [((f.get('method') or {}).get('type') or {}).get('name','?').replace('/','.')+'.'+(f.get('method') or {}).get('name','?') for f in frames]

def category(ms):
    if any(m.startswith('jdk.jfr.internal.') or m.startswith('jdk.jfr.Recording.start') for m in ms):return 'recording-control-or-instrumentation'
    if any(m.startswith('org.apache.poi.xssf.usermodel.BaseXSSFEvaluationWorkbook.getName') for m in ms):return 'poi-named-range-resolution'
    if any(m.startswith('org.apache.xmlbeans.impl.store.Saver') for m in ms):return 'xmlbeans-serialization'
    if any(m.startswith('org.apache.poi.ss.formula.WorkbookEvaluator.') for m in ms):return 'poi-formula-evaluation-other'
    if any(m.startswith('com.xqiou.mantra.excel.') for m in ms):return 'mantra-export-other'
    if any(m.startswith('com.xqiou.mantra.core.') for m in ms):return 'mantra-core-other'
    return 'other-or-no-stack'

def consume(event):
    global allocationWeight
    kind=event['type'];value=event['values'];count[kind]+=1
    if kind in ['jdk.ExecutionSample','jdk.ObjectAllocationSample']:
        ms=methods(value);leaf=ms[0] if ms else '<no stack>';group=category(ms)
        if kind=='jdk.ExecutionSample':
            cpuLeaf[leaf]+=1;cpuInclusive.update(set(ms));cpuCategory[group]+=1;cpuStacks[tuple(ms)]+=1
            if group=='poi-named-range-resolution':
                if any(m.startswith('com.xqiou.mantra.excel.ExcelWorkbookBuilder.evaluate') for m in ms):phase='poi-recalculation-token-parsing'
                elif any(m.startswith('org.apache.poi.xssf.usermodel.XSSFCell.setFormula') for m in ms):phase='formula-installation-parsing'
                else:phase='other-or-truncated-caller'
                cpuNamedPhase[phase]+=1
        else:
            weight=int(value['weight']);allocationWeight+=weight
            allocationLeaf[leaf]+=weight;allocationLeafCount[leaf]+=1
            allocationCategory[group]+=weight;allocationCategoryCount[group]+=1
            clazz=(value.get('objectClass') or {}).get('name','?').replace('/','.')
            allocationClasses[clazz]+=weight
            if leaf not in allocationExamples:allocationExamples[leaf]={'weight':weight,'startTime':value.get('startTime'),'frames':ms}
    elif kind=='jdk.GarbageCollection':gc.append({key:value.get(key) for key in ['startTime','duration','gcId','name','cause','sumOfPauses','longestPause']})
    elif kind=='jdk.GCPhasePause':pauses.append({key:value.get(key) for key in ['startTime','duration','gcId','name']})

with source.open('rb') as stream:
    def read():
        global bytesRead
        data=stream.read(chunkBytes);bytesRead+=len(data);rawHash.update(data)
        return utf.decode(data,final=not data)
    buffer=read();match=re.search(r'"events"\s*:\s*\[',buffer);assert match
    pos=match.end()
    while True:
        while pos<len(buffer) and (buffer[pos].isspace() or buffer[pos]==','):pos+=1
        if pos==len(buffer):buffer=read();pos=0;assert buffer;continue
        if buffer[pos]==']':
            tail=buffer[pos+1:]
            while True:
                extra=read()
                if not extra:break
                tail+=extra
            assert re.fullmatch(r'\s*}\s*}\s*',tail),tail[:100]
            break
        try:event,end=decoder.raw_decode(buffer,pos)
        except json.JSONDecodeError:
            extra=read();assert extra,'Incomplete or malformed event at EOF'
            buffer=buffer[pos:]+extra;pos=0;peakBuffer=max(peakBuffer,len(buffer))
            assert len(buffer)<32*1048576,'Unexpected single event larger than memory bound'
            continue
        consume(event);pos=end
        if pos>chunkBytes:buffer=buffer[pos:];pos=0
        peakBuffer=max(peakBuffer,len(buffer))
assert bytesRead==source.stat().st_size
assert set(count)<=set(identity['selectedCounts'])
assert all(count[kind]==expected for kind,expected in identity['selectedCounts'].items()),(count,identity['selectedCounts'])

def pct(n,d):return str((Decimal(n)*100/Decimal(d)).quantize(Decimal('.000001'))) if d else '0'
def cpuRows(counter,n=20):return [{'method':key,'samples':value,'percentOfCpuSamples':pct(value,count['jdk.ExecutionSample'])} for key,value in counter.most_common(n)]
def allocationRows(counter,n=20):return [{'site':key,'sampledWeightBytes':value,'percentOfAllSampledWeight':pct(value,allocationWeight),'sampleEvents':allocationLeafCount.get(key)} for key,value in counter.most_common(n)]
def durationMilliseconds(text):
    match=re.fullmatch(r'PT([0-9]+(?:\.[0-9]+)?)S',text)
    assert match,text
    return Decimal(match.group(1))*1000
pauseMs=sorted(durationMilliseconds(p['duration']) for p in pauses)
pauseSum=sum(pauseMs,Decimal(0));exportNs=41029463375
analysis={
    'status':'PASS',
    'method':'Python stdlib streaming JSONDecoder; one event at a time; no JVM or re-recording; allocation weight is sampled estimated pressure, not exact allocated bytes.',
    'input':{'path':str(source),'bytes':bytesRead,'sha256':rawHash.hexdigest(),'maximumBufferedCharacters':peakBuffer,'eventCounts':dict(count),'truncatedStacks':dict(truncated)},
    'identity':identity,
    'cpu':{'sampleCount':count['jdk.ExecutionSample'],'exclusiveLeafMethods':cpuRows(cpuLeaf),'inclusiveMethods':cpuRows(cpuInclusive),'exclusiveStackCategories':[{'category':k,'samples':v,'percentOfCpuSamples':pct(v,count['jdk.ExecutionSample'])} for k,v in cpuCategory.most_common()],'namedRangeCallerPhases':[{'phase':k,'samples':v,'percentOfCpuSamples':pct(v,count['jdk.ExecutionSample']),'percentOfNamedRangeSamples':pct(v,cpuCategory['poi-named-range-resolution'])} for k,v in cpuNamedPhase.most_common()],'dominantStacks':[{'samples':v,'percentOfCpuSamples':pct(v,count['jdk.ExecutionSample']),'frames':list(k)} for k,v in cpuStacks.most_common(8)]},
    'allocation':{'sampleEventCount':count['jdk.ObjectAllocationSample'],'totalSampledWeightBytes':allocationWeight,'exclusiveLeafSites':allocationRows(allocationLeaf),'exclusiveStackCategories':[{'category':k,'sampleEvents':allocationCategoryCount[k],'sampledWeightBytes':v,'percentOfAllSampledWeight':pct(v,allocationWeight)} for k,v in allocationCategory.most_common()],'nonRecorderSampledWeightBytes':allocationWeight-allocationCategory['recording-control-or-instrumentation'],'nonRecorderExclusiveCategories':[{'category':k,'sampleEvents':allocationCategoryCount[k],'sampledWeightBytes':v,'percentOfNonRecorderSampledWeight':pct(v,allocationWeight-allocationCategory['recording-control-or-instrumentation'])} for k,v in allocationCategory.most_common() if k!='recording-control-or-instrumentation'],'allocatedClassWeights':[{'class':k,'sampledWeightBytes':v,'percentOfAllSampledWeight':pct(v,allocationWeight)} for k,v in allocationClasses.most_common(12)],'dominantSiteStackEvidence':[{'site':site,**allocationExamples[site]} for site,_ in allocationLeaf.most_common(6)]},
    'gc':{'collections':gc,'phasePauses':pauses,'pauseMilliseconds':list(map(str,pauseMs)),'totalPauseMilliseconds':str(pauseSum),'medianPauseMilliseconds':str(pauseMs[len(pauseMs)//2]),'maximumPauseMilliseconds':str(max(pauseMs)),'percentOfTimedExport':pct(pauseSum,Decimal(exportNs)/1000000),'timedExportNanoseconds':exportNs,'overlapPolicy':'Only GCPhasePause duration is summed. GarbageCollection entries describe the same collections and are not added again.'},
    'limitations':['One diagnostic export is not a timing distribution and its overhead is not included in 390 unprofiled samples.','Inclusive stack percentages overlap and must not be added.','Sampling frequency and stack truncation do not provide exact wall-time causal attribution.','Large recording-start instrumentation sample weight is not actual engine allocated bytes or a JVM heap peak.','A current single profile does not explain or invalidate historic M2 tails.','No FileWrite event was observed: workbook exported to bytes and recording dumped after stop.']
}
Path('build/v1-profile/analysis.json').write_text(json.dumps(analysis,indent=2)+'\n')
print('STREAM_ANALYSIS',round(time.monotonic()-started,3),'seconds','peakChars',peakBuffer,'bytes',bytesRead,'events',dict(count),'weight',allocationWeight)
print('CPU_LEAF',cpuRows(cpuLeaf,8))
print('CPU_CATEGORY',analysis['cpu']['exclusiveStackCategories'])
print('ALLOC_CATEGORY',analysis['allocation']['exclusiveStackCategories'])
print('ALLOC_LEAF',allocationRows(allocationLeaf,10))
print('DOMINANT_CPU_STACK',analysis['cpu']['dominantStacks'][0])
print('GC',analysis['gc'])
print('ANALYSIS_SHA256',hashlib.sha256(Path('build/v1-profile/analysis.json').read_bytes()).hexdigest())
