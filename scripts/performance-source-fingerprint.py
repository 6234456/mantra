#!/usr/bin/env python3
"""Independent mirror of the existing two harnesses' source fingerprint. No VCS or engine import."""
from pathlib import Path
import argparse
import hashlib
import json

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, required=True)
args = parser.parse_args()
root = args.root.resolve()
files = sorted([p for module in ['mantra-core', 'mantra-render', 'mantra-excel', 'benchmarks']
                for p in (root / module).rglob('*.kt') if 'build' not in p.relative_to(root).parts]
               + [root / 'normein-build.lock'], key=lambda p: str(p.relative_to(root)))
digest = hashlib.sha256()
records = []
for path in files:
    relative = str(path.relative_to(root))
    data = path.read_bytes()
    digest.update(relative.encode('utf-8')); digest.update(b'\0'); digest.update(data)
    records.append({'path': relative, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
print(json.dumps({'fileCount': len(files), 'libraryAndHarnessSourceSha256': digest.hexdigest(), 'files': records}, indent=2))
