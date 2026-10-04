#!/usr/bin/env python3
"""Keep handwritten Kotlin files small enough to review; formatters enforce line width."""

from pathlib import Path
import sys


MAX_NONBLANK_LINES = 1200


def kotlin_sources(root: Path):
    """Only repository source roots, never the pinned kernel, build output or dependencies."""
    source_roots = [module / 'src' for module in sorted(root.glob('mantra-*')) if module.is_dir()]
    source_roots += [app / 'src' for app in sorted((root / 'apps').glob('*')) if app.is_dir()]
    source_roots += [root / 'build-support', root / 'benchmarks' / 'src', root / 'conformance-adapter' / 'src']
    for directory in source_roots:
        yield from sorted(directory.rglob('*.kt'))


def check(root: Path, maximum: int = MAX_NONBLANK_LINES) -> list[str]:
    problems = []
    files = list(kotlin_sources(root))
    if not files:
        problems.append('No handwritten Kotlin sources found; the size gate must not pass vacuously')
    for path in files:
        count = sum(bool(line.strip()) for line in path.read_text(encoding='utf-8').splitlines())
        if count > maximum:
            problems.append(
                f'{path.relative_to(root)}: {count} nonblank lines exceeds {maximum}; split by responsibility'
            )
    return problems


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    findings = check(root)
    if findings:
        print('\n'.join(findings), file=sys.stderr)
        sys.exit(1)
    print(f'Source size passed: handwritten Kotlin files have at most {MAX_NONBLANK_LINES} nonblank lines')
