#!/usr/bin/env python3
"""Independent supplied-selection portfolio: Decimal budgets, Fraction weighted ratios."""
import argparse
from copy import deepcopy
from decimal import Decimal as D, localcontext, ROUND_HALF_UP
from fractions import Fraction
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASE = {'id':'demo', 'budget':'120', 'hour_limit':'140', 'currency_scale':2, 'ratio_scale':6,
        'departments':[{'id':'DELIVERY','title':'Fictional delivery team'}, {'id':'RESEARCH','title':'Fictional research team'}],
        'projects':[{'id':'A','title':'Fictional tooling upgrade','department':'DELIVERY','cost':'80','benefit':'140','hours':'70','selected':True,'approval_note':'Fictional selection A'},
                    {'id':'B','title':'Fictional service refresh','department':'DELIVERY','cost':'50','benefit':'75','hours':'50','selected':False,'approval_note':None},
                    {'id':'C','title':'Fictional prototype','department':'RESEARCH','cost':'40','benefit':'68','hours':'60','selected':True,'approval_note':'Fictional selection C'}]}


def rounded(value, scale):
    return value.quantize(D(1).scaleb(-scale), ROUND_HALF_UP)


def ratio(numerator, denominator, scale):
    if denominator == 0:
        return None
    exact = Fraction(numerator) / Fraction(denominator)
    return rounded(D(exact.numerator) / D(exact.denominator), scale)


def compute(fact):
    with localcontext() as context:
        context.prec=80
        rows, values, undefined, diagnostics = {}, {}, [], []
        for index, project in enumerate(fact['projects']):
            cost = rounded(D(project['cost']), fact['currency_scale'])
            benefit = rounded(D(project['benefit']), fact['currency_scale'])
            hours = D(project['hours'])
            assert cost >= 0 and benefit >= 0 and hours >= 0
            selected_cost = cost if project['selected'] else D(0)
            selected_benefit = benefit if project['selected'] else D(0)
            selected_hours = hours if project['selected'] else D(0)
            row = {'proposed-cost':cost,'proposed-benefit':benefit,'proposed-hours':hours,'selected-cost':selected_cost,
                   'selected-benefit':selected_benefit,'selected-hours':selected_hours,'selected-net-benefit':selected_benefit-selected_cost}
            rows[project['id']]=row
            for name,value in row.items():
                values[f'{name}@{project["id"]}']=value
            share=ratio(selected_benefit,selected_cost,fact['ratio_scale'])
            if share is None:
                undefined.append(f'project-benefit-cost@{project["id"]}')
            else:
                values[f'project-benefit-cost@{project["id"]}']=share
            if project['selected'] and not (project['approval_note'] or '').strip():
                diagnostics.append({'code':'MANTRA-INPUT-REQUIRED','node':'projects','severity':'error','row-index':index,'column':'approval-note'})
        for name in next(iter(rows.values())).keys():
            values[f'{name}@*']=sum((row[name] for row in rows.values()),D(0))
        for department in fact['departments']:
            children=[rows[project['id']] for project in fact['projects'] if project['department']==department['id']]
            for target,source in [('department-cost','selected-cost'),('department-benefit','selected-benefit'),('department-hours','selected-hours'),('department-net-benefit','selected-net-benefit')]:
                values[f'{target}@{department["id"]}']=sum((row[source] for row in children),D(0))
            share=ratio(values[f'department-benefit@{department["id"]}'],values[f'department-cost@{department["id"]}'],fact['ratio_scale'])
            if share is None:
                undefined.append(f'department-benefit-cost@{department["id"]}')
            else:
                values[f'department-benefit-cost@{department["id"]}']=share
        for name in ['department-cost','department-benefit','department-hours','department-net-benefit']:
            values[f'{name}@*']=sum((values[f'{name}@{department["id"]}'] for department in fact['departments']),D(0))
        cost,benefit,hours = (values[name+'@*'] for name in ['selected-cost','selected-benefit','selected-hours'])
        values.update({'portfolio-cost':cost,'portfolio-benefit':benefit,'portfolio-hours':hours,'portfolio-net-benefit':benefit-cost,
                       'budget-remaining':D(fact['budget'])-cost,'capacity-remaining':D(fact['hour_limit'])-hours,
                       'cost-reconciliation':cost-D(fact.get('reported_cost',str(cost))),
                       'currency-scale':D(fact['currency_scale']),'budget-limit':D(fact['budget']),
                       'hour-limit':D(fact['hour_limit']),'reported-cost':D(fact.get('reported_cost',str(cost)))})
        share=ratio(benefit,cost,fact['ratio_scale'])
        for name in ['project-benefit-cost','department-benefit-cost']:
            if share is None:
                undefined.append(name+'@*')
                diagnostics.append({'code':'MANTRA-AGGREGATE-ZERO-DENOMINATOR','node':name,'severity':'warning'})
            else:
                values[name+'@*']=share
        for name,actual,limit in [('portfolio-within-budget',cost,D(fact['budget'])),('portfolio-within-capacity',hours,D(fact['hour_limit']))]:
            if actual>limit:
                diagnostics.append({'code':'MANTRA-CHECK-FAILED','node':name,'severity':'error'})
        if values['cost-reconciliation']!=0:
            diagnostics.append({'code':'MANTRA-RECONCILE-FAILED','node':'cost-reconciliation','severity':'error'})
        return values,{'id':fact['id'],'succeeded':True,'validationPassed':not any(row['severity']=='error' for row in diagnostics),
                      'expectedBusiness':diagnostics,'undefined':sorted(undefined),'cost':str(cost),'benefit':str(benefit),'hours':str(hours),
                      'selectedProjects':[project['id'] for project in fact['projects'] if project['selected']]}


def fixtures():
    result={}
    for name in ['demo','none-selected','over-budget','fractional-currency','missing-approval','zero-cost']:
        fact=deepcopy(BASE)
        fact['id']=name
        if name=='none-selected':
            for project in fact['projects']:
                project['selected']=False
                project['approval_note']=None
        elif name=='over-budget':
            fact['projects'][1].update(selected=True,approval_note='Fictional selection B')
        elif name=='fractional-currency':
            fact['projects'][0].update(cost='80.005',benefit='140.005')
            fact['projects'][2].update(cost='40.004',benefit='68.004')
            fact['budget']='120.01'
        elif name=='missing-approval':
            fact['projects'][0]['approval_note']=''
        elif name=='zero-cost':
            for project in fact['projects']:
                project['cost']='0'
        values,_=compute(fact)
        fact['reported_cost']=str(values['portfolio-cost'])
        result[name]=fact
    return result


def self_test():
    facts=fixtures()
    values,summary=compute(facts['demo'])
    assert values['portfolio-cost']==120 and values['portfolio-benefit']==208 and values['portfolio-hours']==130
    assert values['project-benefit-cost@*']==D('1.733333')
    assert values['project-benefit-cost@*']!=(values['project-benefit-cost@A']+values['project-benefit-cost@C'])/2
    for fact in facts.values():
        values,_=compute(fact)
        assert values['portfolio-cost']==values['department-cost@*']==sum(values[f'selected-cost@{p["id"]}'] for p in fact['projects'])
        assert values['portfolio-net-benefit']==values['portfolio-benefit']-values['portfolio-cost']
        assert values['cost-reconciliation']==0
    assert compute(facts['fractional-currency'])[0]['portfolio-cost']==D('120.01')
    assert len(compute(facts['over-budget'])[1]['expectedBusiness'])==2
    assert not compute(facts['missing-approval'])[1]['validationPassed']
    assert len(compute(facts['none-selected'])[1]['expectedBusiness'])==2
    assert compute(facts['none-selected'])[1]['validationPassed']
    assert 'project-benefit-cost@*' in compute(facts['zero-cost'])[1]['undefined']


def write():
    entries=[]
    for name,fact in fixtures().items():
        values,summary=compute(fact)
        for path,data in [(HERE/'fixtures'/f'{name}.json',fact),(HERE/'references'/f'{name}-values.json',{key:str(value) for key,value in sorted(values.items())}),
                          (HERE/'references'/f'{name}-summary.json',summary)]:
            path.write_text(json.dumps(data,indent=2)+'\n')
            entries.append({'path':path.relative_to(HERE).as_posix(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    entries.append({'path':'reference.py','sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()})
    (HERE/'manifest.json').write_text(json.dumps({'status':'INDEPENDENT_REFERENCE_ENGINE_UNEXECUTED','fixtureCount':6,'files':entries},indent=2)+'\n')
    print(json.dumps([compute(fact)[1] for fact in fixtures().values()],indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--self-test',action='store_true')
    parser.add_argument('--write-references',action='store_true')
    arguments=parser.parse_args()
    self_test()
    if arguments.write_references:
        write()
