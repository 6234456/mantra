#!/usr/bin/env python3
"""Independent fictional Mustermann migration reference; never reads engine or golden output."""
import argparse
from decimal import Decimal as D, localcontext, ROUND_HALF_UP
import hashlib
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parent
SPEC = importlib.util.spec_from_file_location('mustermann_independent', APP / 'verify_expected.py')
RULES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RULES)


def reference():
    with localcontext() as context:
        context.prec = 80
        rows = dict(RULES.compute(RULES.PARAMS[2025]))
        common = {key.replace(' ', '@'): str(value) for key, value in rows.items() if not isinstance(value, bool)}
        positive = D('66160') + D('29970') + D('12400') + D('3850')
        # The original facts have no opening loss, new loss, assessment amount or trade tax due.
        # Newly introduced default-zero ledgers therefore preserve the historic tariff computation.
        following = {key: '0' for key in [
            'opening-loss', 'loss-used', 'ledger-opening', 'ledger-new', 'ledger-used',
            'ledger-carryback', 'closing-loss', 'verbleibender-verlustvortrag', 'ermaessigung-35',
        ]}
        following.update({'positive-income': str(positive), 'loss-allowance': '2000000',
                          'loss-deduction-cap': str(min(positive, D('2000000')) + D('.7') * max(positive - D('2000000'), 0)),
                          'post-loss-positive-income': str(positive), 'positive-trade-total': '12400',
                          'all-positive-income': str(positive),
                          'section35-income-ratio': str((D('12400') / positive).quantize(D('.000001'), ROUND_HALF_UP)),
                          'section35-attribution-cap': str((rows['tarifliche-est'] * D('12400') / positive).quantize(D(1).scaleb(-20), ROUND_HALF_UP)),
                          'tariff-income-floored': str(RULES.floor(rows['zu-versteuerndes-einkommen']))})
        for person, income, trade in [('A', D('78560'), D('12400')), ('B', D('29970'), D('0'))]:
            following.update({f'positive-person-income@{person}': str(income), f'positive-trade-income@{person}': str(trade),
                              f'fourfold-messbetrag@{person}': '0', f'gewst-anrechnungsbetrag@{person}': '0'})
        following.update({'positive-person-income@*': '108530', 'positive-trade-income@*': '12400',
                          'fourfold-messbetrag@*': '0', 'gewst-anrechnungsbetrag@*': '0'})
        return {'status': 'INDEPENDENT_SOURCE_ENGINE_UNEXECUTED', 'source': {'id': 'de.est/2025', 'version': '2025.2', 'versionMode': 'legacy-exact'},
                'target': {'id': 'de.est/2025', 'version': '2025.3', 'versionMode': 'legacy-exact'},
                'effectiveDate': '2025-12-31', 'commonNumbers': common,
                'commonBooleans': {key: value for key, value in rows.items() if isinstance(value, bool)}, 'targetNumbers': following,
                'expectedBusiness': [], 'simplifications': ['Original fictional Mustermann facts are reused without any edit.',
                    'Opening/new loss and trade-tax facts are zero defaults; no carryback, source links or new financial facts are inferred.',
                    'The same historical 2025 tariff parameters are used in both versions.']}


def self_test():
    result = reference()
    assert D(result['commonNumbers']['abrechnungsergebnis']) == D('1462.24')
    assert D(result['commonNumbers']['festzusetzende-est']) == D('17996')
    assert result['commonBooleans']['kfb-guenstiger'] is True
    target = result['targetNumbers']
    assert D(target['closing-loss']) == D(target['ledger-opening']) + D(target['ledger-new']) - D(target['ledger-used']) - D(target['ledger-carryback'])
    assert D(target['positive-person-income@*']) == D(target['positive-person-income@A']) + D(target['positive-person-income@B'])
    assert D(target['all-positive-income']) == D(target['positive-person-income@*']) + D('3850')


def write():
    value = reference()
    output = HERE / 'reference.json'
    output.write_text(json.dumps(value, indent=2) + '\n')
    paths = [Path(__file__).resolve(), APP / 'verify_expected.py', APP / 'case-mustermann.mantra',
             APP / 'schema.mantra', APP / 'versions/2025.3/schema.mantra', APP / 'versions/2025.3/params.mantra']
    manifest = {'status': 'INDEPENDENT_SOURCE_ENGINE_UNEXECUTED', 'numericChecks': len(value['commonNumbers']) + len(value['targetNumbers']),
                'booleanChecks': len(value['commonBooleans']),
                'reference': {'path': 'reference.json', 'byteLength': output.stat().st_size, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest()},
                'sources': [{'path': path.relative_to(APP).as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()} for path in paths]}
    (HERE / 'reference.manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--self-test', action='store_true')
    arguments = parser.parse_args()
    self_test()
    if not arguments.self_test:
        write()
