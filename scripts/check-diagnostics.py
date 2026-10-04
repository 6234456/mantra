#!/usr/bin/env python3
"""Keep the diagnostic reference exhaustive for static codes in Mantra library/tool source."""
from collections import Counter
from pathlib import Path
import re
import sys


CODE_LITERAL = re.compile(r'"((?:MANTRA|DSL)(?:-[A-Z0-9]+)+)"')
DOCUMENTED_ROW = re.compile(r'^\| `((?:MANTRA|DSL)(?:-[A-Z0-9]+)+)` \|', re.MULTILINE)


def source_codes(root: Path) -> set[str]:
    result = set()
    for path in root.glob('mantra-*/src/main/**/*.kt'):
        result.update(CODE_LITERAL.findall(path.read_text(encoding='utf-8')))
    return result


def check(root: Path) -> list[str]:
    actual = source_codes(root)
    if not actual:
        return ['No static diagnostic codes found in Mantra library/tool main sources']
    document = root / 'docs/diagnostics.md'
    if not document.is_file():
        return ['Diagnostic reference is missing: docs/diagnostics.md']
    rows = Counter(DOCUMENTED_ROW.findall(document.read_text(encoding='utf-8')))
    documented = set(rows)
    problems = []
    missing = sorted(actual - documented)
    stale = sorted(documented - actual)
    duplicate = sorted(code for code, count in rows.items() if count > 1)
    if missing:
        problems.append('Undocumented static diagnostic codes: ' + ', '.join(missing))
    if stale:
        problems.append('Diagnostic reference lists removed static codes: ' + ', '.join(stale))
    if duplicate:
        problems.append('Duplicate diagnostic reference rows: ' + ', '.join(duplicate))
    return problems


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    problems = check(root)
    if problems:
        print('\n'.join(problems), file=sys.stderr)
        raise SystemExit(1)
    print(f'Diagnostic reference passed: {len(source_codes(root))} static codes documented exactly once')
