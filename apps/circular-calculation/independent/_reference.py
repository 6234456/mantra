#!/usr/bin/env python3
"""Independent M3 source computations; Python standard library only, no Mantra inputs.

Decimal tax ledgers are checked with rational arithmetic. Converge amounts come from the
closed-form equation and cent fixed-point candidates; a separate rational transition graph
certifies the frozen seed/stop behavior. That graph is source evidence, never runtime trace.
"""
import argparse, hashlib, json
from decimal import Decimal as D, getcontext, ROUND_FLOOR, ROUND_HALF_UP
from fractions import Fraction as F
from pathlib import Path
getcontext().prec=80
HERE=Path(__file__).resolve().parent
P=json.loads((HERE/'parameters.json').read_text())
Z=D(0)


def load(domain,name):
    return json.loads((HERE/'fixtures'/domain/(name+'.json')).read_text())


def text(value):
    if isinstance(value,F):
        value=D(value.numerator)/D(value.denominator)
    return format(value,'f') if isinstance(value,D) else str(value)


def floor(value,scale=0):
    return value.quantize(D(1).scaleb(-scale),rounding=ROUND_FLOOR)


def finding(node,row=None,column=None):
    result={'category':'BUSINESS','code':'MANTRA-CHECK-FAILED','node':node,'severity':'error'}
    if row is not None:result['row_index']=row
    if column is not None:result['column']=column
    return result


def tariff_decimal(income):
    p=P['income_2025'];x=floor(income)
    g=[D(p[key]) for key in ['basic_allowance','zone2_upper','zone3_upper','zone4_upper']]
    if x<=g[0]:raw=Z
    elif x<=g[1]:
        y=(x-g[0])/D(10000);raw=D(p['zone2_quadratic'])*y*y+D(p['zone2_linear'])*y
    elif x<=g[2]:
        y=(x-g[1])/D(10000);raw=D(p['zone3_quadratic'])*y*y+D(p['zone3_linear'])*y+D(p['zone3_constant'])
    elif x<=g[3]:raw=D(p['zone4_fraction'])*x-D(p['zone4_subtraction'])
    else:raw=D(p['zone5_fraction'])*x-D(p['zone5_subtraction'])
    return floor(raw)


def tariff_rational(income):
    """Integer quotient of an exact rational polynomial, separate from Decimal floor."""
    p=P['income_2025'];x=F(income).__floor__()
    if x<=12096:return F(0)
    if x<=17443:
        y=F(x-12096,10000);raw=F(p['zone2_quadratic'])*y*y+1400*y
    elif x<=68480:
        y=F(x-17443,10000);raw=F(p['zone3_quadratic'])*y*y+2397*y+F(p['zone3_constant'])
    elif x<=277825:raw=F(42,100)*x-F(p['zone4_subtraction'])
    else:raw=F(45,100)*x-F(p['zone5_subtraction'])
    return F(raw.numerator//raw.denominator)


def income_selected_chain(trade,other,used_loss=Z,mess=Z,payable=Z,final_credit=True):
    p=P['income_2025'];gde=trade+other;income=gde-used_loss-D(p['special_expense_flat'])
    tarif=tariff_decimal(income)
    assert F(tarif)==tariff_rational(income)
    positive_trade=max(Z,trade);positive_total=positive_trade+max(Z,other)
    ratio=F(positive_trade)/F(positive_total) if positive_total else F(0)
    cap=ratio*F(tarif)
    out={'gesamtbetrag-der-einkuenfte':gde,'verlustabzug':used_loss,'sonderausgaben-summe':D(p['special_expense_flat']),
         'einkommen':income,'zu-versteuerndes-einkommen':income,'tariff-income-floored':floor(income),'tarifliche-est':tarif,
         'positive-trade-income':positive_trade,'all-positive-income':positive_total,'section35-income-ratio':ratio,
         'section35-attribution-cap':cap,'fourfold-messbetrag':mess*D(p['credit_factor']),'gewst-payable@A':payable,'gewst-messbetrag@A':mess}
    if final_credit:
        credit=min(F(mess)*4,F(payable),cap)
        # Final credits are deliberately integral EUR in the complete frozen cases.
        assert credit.denominator==1,('unresolved final credit rounding',credit)
        est=tarif-D(credit.numerator)
        bmg=max(Z,est)
        raw_soli=Z if bmg<=D(p['soli_threshold']) else min(D(p['soli_fraction'])*bmg,D(p['soli_taper'])*(bmg-D(p['soli_threshold'])))
        soli=floor(raw_soli,2)
        rate=Z if not income else (est/income).quantize(D('.000001'),rounding=ROUND_HALF_UP)
        out.update({'ermaessigung-35':D(credit.numerator),'festzusetzende-est':est,'bmg-zuschlagsteuern':bmg,
                    'solidaritaetszuschlag':soli,'kirchensteuer':Z,'festgesetzte-steuern':est+soli,
                    'abrechnungsergebnis':est+soli,'durchschnittlicher-steuersatz':rate,'zahlungsabgleich':Z})
    return out


def loss_reference(fact):
    inherited=[];source=fact.get('source')
    if source:
        source_values,source_summary=loss_reference(load('loss',source))
        opening=source_values['closing-loss']
        inherited=source_summary['expected_business']
    else:opening=D(fact['opening_loss'])
    new=D(fact['eligible_new_loss']);positive=max(Z,D(fact['positive_income']))
    allowance=D(P['loss']['joint_allowance' if fact['joint_amount_check_only'] else 'single_allowance'])
    cap=min(positive,allowance)+D(P['loss']['excess_fraction'])*max(Z,positive-allowance)
    assert F(cap)==min(F(positive),F(allowance))+F(7,10)*max(F(0),F(positive)-F(allowance))
    used=min(opening,cap);carryback=D(fact['selected_carryback']);closing=opening+new-used-carryback
    out={'opening-loss':opening,'eligible-new-loss':new,'positive-income':positive,'loss-allowance':allowance,
         'loss-deduction-cap':cap,'loss-used':used,'selected-carryback':carryback,'closing-loss':closing,
         'post-loss-positive-income':positive-used,'loss-crossfoot':closing-(opening+new-used-carryback)}
    findings=[]
    if opening<0:findings.append(finding('opening-loss-nonnegative'))
    if new<0:findings.append(finding('new-loss-nonnegative'))
    if not fact['carryback_waived']:findings.append(finding('carryback-waiver-supported'))
    if source:
        out['verlustvortrag']=opening
        # The consumer has only self-employment income in the source-first fixture.
        out.update(income_selected_chain(Z,positive,used))
        out['verbleibender-verlustvortrag']=closing
    summary={'reference_scope':'loss-ledger-only' if fact['year']==2024 else 'selected-loss-chain',
             'expected_business':findings,'inherited_source_business':inherited,'validation_passed':not(findings or inherited),
             'source_case':source,'source_kind':'Link' if source else 'Provided','source_zero_is_provided':opening==0,
             'stock':'closing-loss is carried once to the next opening; never sum annual closing balances.',
             'numeric_invalid_facts':'Signed original facts remain visible; business findings do not turn a technical success into a tax approval.'}
    return out,summary


def trade_reference(fact):
    p=P['trade'];weights={key:D(value) for key,value in p['weighted_fractions'].items()}
    amounts={key:Z for key in weights};weighted=Z;out={};findings=[]
    for index,fee in enumerate(fact['fees']):
        amount=D(fee['amount']);category=fee['category'];out['fee-amount@'+str(index)]=amount
        if amount<0:findings.append(finding('financing-cost-nonnegative',index,'amount'))
        if category not in weights:
            findings.append({'category':'BUSINESS','code':'MANTRA-INPUT-REQUIRED','node':'financing-costs','severity':'error','row_index':index,'column':'category'})
            continue
        amounts[category]+=amount;share=amount*weights[category];out['weighted-fee@'+str(index)]=share;weighted+=share
        assert F(share)==F(amount)*F(p['weighted_fractions'][category])
    profit=D(fact['profit']);rate=D(fact['hebesatz_percent'])
    excess=max(Z,weighted-D(p['financing_allowance']));addition=excess*D(p['addition_fraction'])
    earnings=profit+addition
    rounded=floor(earnings,-2)
    # Within the selected natural-person scenario the applied allowance cannot exceed positive earnings.
    allowance=min(max(Z,rounded),D(p['natural_person_allowance']))
    base=max(Z,rounded-allowance);mess=base*D(p['assessment_fraction']);tax=mess*rate/D(100)
    assert F(mess)==F(base)*F(7,200)
    assert F(tax)==F(mess)*F(rate)/100
    assert tax==floor(tax,2),('Frozen tax needs unresolved legal rounding',tax)
    out.update({'profit':profit,'raw-financing-expenses':sum((D(f['amount']) for f in fact['fees']),Z),
                **{'fee-total@'+key:value for key,value in amounts.items()},'weighted-financing-total':weighted,
                'financing-excess':excess,'hinzurechnung':addition,'section9-deductions':Z,'gewerbeertrag':earnings,
                'gewerbeertrag-rounded-hundreds':rounded,'applied-freibetrag':allowance,'assessment-base':base,
                'messbetrag':mess,'hebesatz-percent':rate,'gewerbesteuer':tax,'trade-income-match':Z})
    if fact['entity']!='natural-person-sole-proprietor':findings.append(finding('entity-supported'))
    if rate<D(p['minimum_hebesatz_percent']):findings.append(finding('hebesatz-supported'))
    complete=fact['reference_scope']=='complete-selected-chain'
    est=income_selected_chain(profit,D(fact['other_self_employment_income']),mess=mess,payable=tax,final_credit=complete)
    out.update({'est/'+key:value for key,value in est.items()})
    summary={'expected_business':findings,'validation_passed':not findings,'reference_scope':fact['reference_scope'],
             'consumer_version':'de.est/2025@2025.3','source_version':'de.gewst/2025@2025.1',
             'final-credit-rounding':'No inferred rounding rule: complete fixture credits are integral. Fractional-cap fixture has no final tax outputs.',
             'missing-category':'Unknown classification is reported; only recognized categories enter the selected weighted subtotal. No guessed category.',
             'unsupported-entity':'Computed selected sole-proprietor scenario remains visible alongside explicit unsupported-entity error, not a corporation calculation.'}
    return out,summary


def cent_round(value):
    """Exact half-away-from-zero integer-cent rounding, no Decimal approximation."""
    scaled=value*100;sign=-1 if scaled<0 else 1;scaled=abs(scaled)
    quotient,remainder=divmod(scaled.numerator,scaled.denominator)
    return F(sign*(quotient+(2*remainder>=scaled.denominator)),100)


def affine(fact):
    base=F(fact['base']);rate=F(fact['rate'])
    return (rate*base,-rate) if fact['domain']=='bonus' else (base,rate)


def fixed_candidates(a,b):
    if b==1:return [],None
    exact=a/(1-b)
    # Every cent fixed point must be within this rounding-error envelope of the exact root.
    radius=F(1,200)/abs(1-b)
    low=((exact-radius)*100).__floor__()-1;high=((exact+radius)*100).__ceil__()+1
    assert high-low<2000,'Source candidate range intentionally bounded'
    candidates=[F(i,100) for i in range(low,high+1) if F(i,100)==cent_round(a+b*F(i,100))]
    return candidates,exact


def rational_graph_certificate(fact,a,b):
    """Finite orbit graph certifies chosen seed/stop semantics, independent of kernel trace.

    Amount expectations are frozen facts/closed-form candidates. We don't generate an engine
    expected by reading an execution or replay an execution as alleged runtime evidence.
    """
    seed=F(fact['seed']);tol=F(fact['tolerance']);maximum=fact['maximum_calls']
    states={seed:0};edges=[];current=seed
    if fact['termination_certificate']=='unbounded-drift':
        assert a>0 and b>=1 and seed>=0
        return {'outcome':'not-converged','expected_callback_calls':maximum,'proof':'positive unbounded affine drift dominates cent rounding','orbit_edges':[]},None
    for depth in range(1,maximum+1):
        nxt=cent_round(a+b*current);edges.append([text(current),text(nxt)])
        if abs(nxt-current)<=tol:
            return {'outcome':'converged','expected_callback_calls':depth,'proof':'rational cent transition reaches the declared stopping relation','orbit_edges':edges},nxt
        if nxt in states:
            length=depth-states[nxt]
            assert length>1
            return {'outcome':'not-converged','expected_callback_calls':maximum,'proof':'reachable cycle of length '+str(length),'cycle_length':length,'orbit_edges':edges},None
        states[nxt]=depth;current=nxt
    return {'outcome':'not-converged','expected_callback_calls':maximum,'proof':'maximum callbacks reached before stopping relation','orbit_edges':edges},None


def converge_reference(fact):
    a,b=affine(fact);candidates,exact=fixed_candidates(a,b)
    certificate,observed=rational_graph_certificate(fact,a,b)
    chosen=F(fact['expected_money']) if fact['expected_money'] is not None else None
    assert observed==chosen,(fact['case'],observed,chosen)
    summary={'reference_scope':'closed-form-and-cent-orbit-certificate','outcome':certificate['outcome'],
             'exact_closed_form':None if exact is None else {'numerator':exact.numerator,'denominator':exact.denominator},
             'cent_fixed_points':[text(value) for value in candidates],
             'certificate':certificate,'not_runtime_trace':True,'expected_business':[],
             'expected_evaluation_code':None if chosen is not None else 'MANTRA-CALC-NOT-CONVERGED',
             'declared_domain_supported':fact['supported_domain']}
    out={'base':F(fact['base']),'rate':F(fact['rate']),'seed':F(fact['seed']),'tolerance':F(fact['tolerance'])}
    if chosen is not None:
        if fact['termination_certificate']!='stopped-with-business-residual':assert chosen in candidates
        raw_residual=chosen-(a+b*chosen);rounded_residual=chosen-cent_round(a+b*chosen)
        out.update({'converged-amount':chosen,'exact-equation-residual':raw_residual,'money-equation-residual':rounded_residual})
        if fact['domain']=='bonus':out['retained-profit']=F(fact['base'])-chosen
        else:
            withheld=cent_round(F(fact['rate'])*chosen);net=chosen-withheld
            out.update({'deduction':withheld,'actual-net':net,'net-crossfoot':net-F(fact['base'])})
        if abs(rounded_residual)>F(1,100):summary['expected_business']=[finding('equation-residual')]
    summary['validation_passed']=not summary['expected_business'] and chosen is not None
    return out,summary


def reference(domain,fact):
    return {'loss':loss_reference,'trade':trade_reference,'converge':converge_reference}[domain](fact)


def manifest():
    paths=[p for p in HERE.rglob('*') if p.is_file() and p.name!='source-manifest.json' and '__pycache__' not in p.parts]
    data={'checked_on':'2026-10-04','status':'SOURCE PREPARATION ONLY; no schema, application or engine acceptance.',
          'notice':'Original fictional facts; independent Decimal/Fraction values. No engine output used to derive expected.',
          'files_sha256':{str(path.relative_to(HERE)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}}
    (HERE/'source-manifest.json').write_text(json.dumps(data,indent=2)+'\n')


def regenerate():
    count=0
    for domain in ['loss','trade','converge']:
        for path in sorted((HERE/'fixtures'/domain).glob('*.json')):
            values,summary=reference(domain,json.loads(path.read_text()))
            for suffix,data in [('values',{k:text(v) for k,v in values.items()}),('summary',summary)]:
                (HERE/'references'/(domain+'-'+path.stem+'-'+suffix+'.json')).write_text(json.dumps(data,indent=2)+'\n')
            count+=1
    manifest();print('Regenerated',count,'independent values/summary pairs')


def verify_tree():
    counts={}
    for domain in ['loss','trade','converge']:
        cases=keys=0
        for path in sorted((HERE/'fixtures'/domain).glob('*.json')):
            values,summary=reference(domain,json.loads(path.read_text()))
            numeric=json.loads((HERE/'references'/(domain+'-'+path.stem+'-values.json')).read_text())
            assert numeric=={k:text(v) for k,v in values.items()},(domain,path.stem,'values')
            assert json.loads((HERE/'references'/(domain+'-'+path.stem+'-summary.json')).read_text())==summary,(domain,path.stem,'summary')
            cases+=1;keys+=len(values)
        counts[domain]={'cases':cases,'numeric_assertions':keys}
    for path,digest in json.loads((HERE/'source-manifest.json').read_text())['files_sha256'].items():
        assert hashlib.sha256((HERE/path).read_bytes()).hexdigest()==digest,path
    print(json.dumps(counts,sort_keys=True))


def self_test():
    expected_loss={'consumer-2025':(45000,32000,13000),'consumer-revised-2025':(55000,32000,23000),
                   'consumer-exhausted-2025':(32000,32000,0),'consumer-zero-2025':(0,0,0),
                   'no-positive-income':(45000,0,45000),'minimum-tax-base':(3000000,1700000,1300000),
                   'joint-amount-only':(5000000,2700000,2300000)}
    for case,expected in expected_loss.items():
        values,_=loss_reference(load('loss',case));assert tuple(values[k] for k in ['opening-loss','loss-used','closing-loss'])==expected
    for amount,used in [('999999',999999),('1000000',1000000),('1000010',1000007)]:
        values,_=loss_reference(load('loss','threshold-'+amount));assert values['loss-used']==used
    values,summary=loss_reference(load('loss','consumer-business-source-2025'))
    assert values['verlustvortrag']==4900 and not summary['validation_passed'] and summary['inherited_source_business']
    for rate,tax,credit,est,soli in [('300','10815','10815','28657','1036.13'),('400','14420','14420','25052','607.13'),('450','16222.50','14420','25052','607.13')]:
        values,_=trade_reference(load('trade','rate-'+rate))
        assert values['weighted-financing-total']==230000 and values['hinzurechnung']==7500 and values['messbetrag']==3605
        for key,expected in [('gewerbesteuer',tax),('est/ermaessigung-35',credit),('est/festzusetzende-est',est),('est/solidaritaetszuschlag',soli)]:assert F(values[key])==F(expected),(rate,key)
    for amount,addition in [('199999','0'),('200000','0'),('200001','.25')]:
        values,_=trade_reference(load('trade','financing-threshold-'+amount));assert values['hinzurechnung']==D(addition)
    for case,mess in [('allowance-24499_99','0'),('allowance-24500','0'),('allowance-24599_99','0'),('allowance-24600','3.50')]:
        values,_=trade_reference(load('trade',case));assert values['messbetrag']==D(mess)
    values,_=trade_reference(load('trade','mixed-positive-income'))
    assert values['est/section35-attribution-cap']==9868 and values['est/ermaessigung-35']==9868
    assert values['est/festzusetzende-est']==29604 and values['est/solidaritaetszuschlag']==D('1148.82')
    values,_=trade_reference(load('trade','mixed-fractional-cap-intermediate-only'))
    assert values['est/section35-attribution-cap']==F('10960.80') and 'est/festzusetzende-est' not in values
    values,_=trade_reference(load('trade','negative-other-income'))
    assert values['est/all-positive-income']==120000 and values['est/section35-income-ratio']==1
    for domain in ['loss','trade','converge']:
        for path in (HERE/'fixtures'/domain).glob('*.json'):reference(domain,json.loads(path.read_text()))
    main,summary=converge_reference(load('converge','bonus-main'))
    assert main['converged-amount']==F('9090.91') and summary['exact_closed_form']=={'numerator':100000,'denominator':11}
    low,_=converge_reference(load('converge','gross-up-main'));high,_=converge_reference(load('converge','gross-up-seed-high'))
    assert low['converged-amount']==F('1333.33') and high['converged-amount']==F('1333.34')
    _,cycle=converge_reference(load('converge','bonus-penny-cycle'));assert cycle['certificate']['cycle_length']==2
    coarse,summary=converge_reference(load('converge','gross-up-coarse-tolerance'))
    assert coarse['converged-amount']==F('1333.01') and coarse['net-crossfoot']==F('-.24') and not summary['validation_passed']
    print('Independent self-test passed: annual loss stock/flow, statutory boundaries, linked tax caps, exact tariff/Soli, cent fixed points, cycles and residual failure')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--regenerate',action='store_true');parser.add_argument('--self-test',action='store_true');parser.add_argument('--verify-tree',action='store_true')
    args=parser.parse_args()
    if args.self_test:self_test()
    if args.regenerate:regenerate()
    if args.verify_tree:verify_tree()
