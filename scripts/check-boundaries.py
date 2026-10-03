#!/usr/bin/env python3
"""Enforce M0's one-way dependencies and domain-free library/frontend sources."""
from pathlib import Path
import re
import sys

LIBRARIES = {
    'mantra-core', 'mantra-render', 'mantra-excel', 'mantra-workbench',
    'mantra-server', 'mantra-cli',
}
PUBLIC_API_CONSUMERS = LIBRARIES | {'benchmarks'}
DECLARATIONS = {
    'schema', 'section', 'param', 'input', 'dimension', 'line', 'total',
    'choice', 'slot', 'formula-slot',
}
# These names also describe language/presentation concepts or ordinary programming operations.
# They are never exemptions for schema IDs; adding domain names here needs boundary review.
SHARED_VOCABULARY = {'order', 'orders', 'person', 'rounding', 'summary'}
TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|;[^\n]*|[(){}\[\]]|[^\s(){}\[\]";]+')
SOURCE_TOKEN = re.compile(r'(?<![\w./-])[\w][\w./-]*(?![\w./-])')
SUFFIXES = {'.kt', '.ts', '.tsx', '.js', '.jsx', '.mjs'}
PROJECT_DEPENDENCY = re.compile(r'project\s*\(\s*(?:path\s*=\s*)?["\']([^"\']+)["\']\s*\)')
COMMENTS_AND_STRINGS = re.compile(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', re.S)


def without_comments(text: str) -> str:
    return COMMENTS_AND_STRINGS.sub(
        lambda match: match.group() if match.group().startswith('"') else '\n' * match.group().count('\n'), text,
    )



def application_identifiers(root: Path) -> set[str]:
    identifiers = set()
    for app in sorted((root / 'apps').iterdir()):
        if not app.is_dir():
            continue
        for path in app.rglob('*.mantra'):
            if any(part in {'build', 'node_modules'} for part in path.relative_to(app).parts):
                continue
            tokens = [m.group() for m in TOKEN.finditer(path.read_text()) if not m.group().startswith(';')]
            for index in range(len(tokens) - 2):
                if tokens[index] == '(' and tokens[index + 1] in DECLARATIONS:
                    name = tokens[index + 2]
                    if name not in {'(', ')', '{', '}', '[', ']'} and not name.startswith('"'):
                        if tokens[index + 1] == 'schema' or name not in SHARED_VOCABULARY:
                            identifiers.add(name)
    return identifiers


def production_sources(directory: Path):
    for path in directory.rglob('*'):
        if path.is_file() and path.suffix in SUFFIXES and not any(
            part in {'__tests__', 'test', 'tests', 'node_modules', 'build'} for part in path.parts
        ) and not re.search(r'\.(?:test|spec)\.', path.name):
            yield path


def check(root: Path) -> list[str]:
    problems = []
    identifiers = application_identifiers(root)
    if not identifiers:
        problems.append('apps/: no application identifiers found; the boundary check must not pass vacuously')
    for module in sorted(PUBLIC_API_CONSUMERS):
        base = root / module
        for path in sorted(base.rglob('*.kt')):
            if module != 'mantra-core' and 'build' not in path.relative_to(base).parts:
                for number, line in enumerate(without_comments(path.read_text()).splitlines(), 1):
                    if 'com.xqiou.mantra.core.engine.' in line:
                        problems.append(f'{path.relative_to(root)}:{number}: imports or references internal core.engine API')
        build = base / 'build.gradle.kts'
        if build.exists():
            for dependency in PROJECT_DEPENDENCY.findall(without_comments(build.read_text())):
                if module == 'benchmarks' and dependency.removeprefix(':') not in LIBRARIES:
                    problems.append(f'{build.relative_to(root)}: benchmarks depend on non-library {dependency}')
                elif dependency.startswith(':apps'):
                    problems.append(f'{build.relative_to(root)}: library depends on application {dependency}')
    for app in sorted((root / 'apps').iterdir()):
        build = app / 'build.gradle.kts'
        if not app.is_dir():
            continue
        if not build.is_file():
            problems.append(f'{app.relative_to(root)}: application has no Gradle project')
            continue
        text = without_comments(build.read_text())
        for dependency in PROJECT_DEPENDENCY.findall(text):
            if dependency.removeprefix(':') not in LIBRARIES:
                problems.append(f'{build.relative_to(root)}: application depends on non-library {dependency}')
        if re.search(r'\bmaven-publish\b|\bpublishing\s*\{', text):
            problems.append(f'{build.relative_to(root)}: demonstration applications must not publish library artifacts')
        for path in sorted((app / 'src').rglob('*.kt')):
            for number, line in enumerate(without_comments(path.read_text()).splitlines(), 1):
                if 'com.xqiou.mantra.core.engine.' in line:
                    problems.append(f'{path.relative_to(root)}:{number}: application references internal core.engine API')
    for path in sorted((root / 'build-support/app-acceptance').rglob('*.kt')):
        for number, line in enumerate(without_comments(path.read_text()).splitlines(), 1):
            if 'com.xqiou.mantra.core.engine.' in line:
                problems.append(f'{path.relative_to(root)}:{number}: shared acceptance code references internal core.engine API')
    roots = [root / module / 'src/main' for module in sorted(PUBLIC_API_CONSUMERS)] + [root / 'workbench-ui/src']
    for directory in roots:
        for path in sorted(production_sources(directory)):
            for number, line in enumerate(without_comments(path.read_text()).splitlines(), 1):
                found = set(SOURCE_TOKEN.findall(line)) & identifiers
                if found:
                    problems.append(f'{path.relative_to(root)}:{number}: domain identifier(s): {", ".join(sorted(found))}')
    return problems


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    findings = check(root)
    if findings:
        print('\n'.join(findings), file=sys.stderr)
        sys.exit(1)
    print('Boundaries passed: public API, application dependencies and domain-free production sources')
