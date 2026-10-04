#!/usr/bin/env python3
"""Capture-only app package preparation. Reads source headers; never evaluates Mantra formulas."""
import argparse
import hashlib
import json
from pathlib import Path
import re


class Header:
    def __init__(self, text):
        self.text, self.offset = text, 0

    def skip(self):
        while self.offset < len(self.text):
            if self.text[self.offset].isspace():
                self.offset += 1
            elif self.text[self.offset] == ';':
                self.offset = self.text.find('\n', self.offset)
                if self.offset < 0:
                    self.offset = len(self.text)
            else:
                break

    def value(self):
        self.skip()
        first = self.text[self.offset]
        if first == '"':
            value, consumed = json.JSONDecoder().raw_decode(self.text[self.offset:])
            self.offset += consumed
            return value
        if first in '([{':
            end = {'(': ')', '[': ']', '{': '}'}[first]
            self.offset += 1
            values = []
            while True:
                self.skip()
                if self.text[self.offset] == end:
                    self.offset += 1
                    break
                values.append(self.value())
            if first != '{':
                return values
            if len(values) % 2:
                raise ValueError('Odd metadata map')
            entries = list(zip(values[::2], values[1::2]))
            if len({key for key, _ in entries}) != len(entries):
                raise ValueError('Duplicate metadata key')
            return {key.removeprefix(':'): value for key, value in entries}
        match = re.match(r'[^\s()\[\]{};]+', self.text[self.offset:])
        if not match:
            raise ValueError(f'Unexpected header character at {self.offset}')
        self.offset += len(match[0])
        return None if match[0] == 'nil' else match[0]

    def read(self):
        self.skip()
        if self.text[self.offset] != '(':
            raise ValueError('Expected document root')
        self.offset += 1
        kind = self.value()
        if kind == 'fragment':
            return kind, None, {}
        identity = self.value()
        self.skip()
        metadata = self.value() if self.text[self.offset] == '{' else {}
        return kind, identity, metadata


def retained(path):
    if path.parts[0] in {'build', 'src'} or path.name == 'manifest.json':
        return False
    return path.suffix in {'.mantra', '.csv', '.md', '.py'} or path.as_posix() == 'batch/reference-small.jsonl' or (
        path.suffix in {'.json', '.xlsx'} and path.parts[0] in {'data', 'fixtures', 'independent', 'import-templates', 'migration', 'batch'})


def binding(entry):
    version = entry['metadata'].get('version')
    semver = isinstance(version, str) and re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version)
    # Historical spellings stay exact; newly authored three-component identities use SemVer.
    return {'id': entry['id'], 'version': version, 'versionMode': 'semver' if semver else 'legacy-exact'}


def nearest(owner, candidates, hint=None, version=None, asserted=False):
    matching = [item for item in candidates if hint is None or item['id'] == hint]
    if asserted:
        matching = [item for item in matching if item['metadata'].get('version') == version]
    ancestors = [owner['path'].parent, *owner['path'].parent.parents]
    ranked = [(ancestors.index(item['path'].parent), item) for item in matching if item['path'].parent in ancestors]
    if not ranked:
        raise ValueError(f'No declared ancestor schema for {owner["path"]} ({hint}, {version})')
    best = min(rank for rank, _ in ranked)
    winners = [item for rank, item in ranked if rank == best]
    if len(winners) != 1:
        raise ValueError(f'Ambiguous exact binding for {owner["path"]}')
    return binding(winners[0])


def prepare(app_dir, output, copy_resources=False):
    paths = sorted(path for path in app_dir.rglob('*') if path.is_file() and retained(path.relative_to(app_dir)))
    headers = []
    for path in paths:
        if path.suffix == '.mantra':
            kind, identity, metadata = Header(path.read_text()).read()
            if kind not in {'schema', 'parameters', 'case', 'layout', 'fragment'}:
                raise ValueError(f'Unknown document root {kind}: {path}')
            headers.append({'path': path.relative_to(app_dir), 'kind': kind, 'id': identity, 'metadata': metadata})
    schemas = [entry for entry in headers if entry['kind'] == 'schema']
    by_path = {entry['path']: entry for entry in headers}
    parameters, layouts, cases = [], [], []
    for entry in headers:
        metadata = entry['metadata']
        if entry['kind'] in {'parameters', 'layout', 'case'}:
            schema = nearest(entry, schemas, metadata.get('for') if entry['kind'] == 'parameters' else metadata.get('schema'),
                             metadata.get('schema-version'), 'schema-version' in metadata)
            declared = {'id': entry['id'], 'schema': schema, 'path': entry['path'].as_posix()}
            if entry['kind'] == 'parameters':
                parameters.append(declared)
            elif entry['kind'] == 'layout':
                layouts.append(declared)
            else:
                declared['parameters'] = metadata.get('parameters', [])
                selected_schema = [item for item in schemas if binding(item) == schema]
                conventional = [item['id'] for item in headers if item['kind'] == 'layout'
                                and len(selected_schema) == 1
                                and item['path'] == selected_schema[0]['path'].parent / 'layout.mantra']
                declared['layout'] = metadata.get('layout') or (conventional[0] if len(conventional) == 1 else None)
                cases.append(declared)
    resources = []
    for path in paths:
        relative = path.relative_to(app_dir)
        kind = by_path[relative]['kind'] if relative in by_path else None
        role = kind if kind in {'schema', 'parameters', 'layout', 'case', 'fragment'} else (
            'import-template' if relative.parts[0] == 'import-templates' else
            'data' if relative.parts[0] == 'data' or path.suffix == '.csv' else 'documentation')
        data = path.read_bytes()  # Preserve original CRLF and all frozen bytes exactly.
        resources.append({'path': relative.as_posix(), 'role': role, 'byteLength': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        if copy_resources:
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    dependencies = [{'id': 'mantra.demo.de-gewst', 'version': '1.0.0'}] if app_dir.name == 'de-est' else []
    manifest = {'format': 'mantra.package/1', 'id': f'mantra.demo.{app_dir.name}', 'version': '1.0.0',
                'engine': '>=0.5.0-0 <2.0.0', 'resources': resources,
                'schemas': [{'schema': binding(entry), 'path': entry['path'].as_posix()} for entry in schemas],
                'parameters': parameters, 'layouts': layouts, 'cases': cases, 'dependencies': dependencies}
    for category in [parameters, layouts, cases]:
        ids = [entry['id'] for entry in category]
        if len(ids) != len(set(ids)):
            raise ValueError(f'Duplicate document identity in {app_dir.name}')
    known_parameters = {entry['id']: entry['schema'] for entry in parameters}
    known_layouts = {entry['id']: entry['schema'] for entry in layouts}
    for entry in cases:
        if any(known_parameters.get(identity) != entry['schema'] for identity in entry['parameters']):
            raise ValueError(f'Foreign or absent case parameter: {entry["path"]}')
        if entry['layout'] and known_layouts.get(entry['layout']) != entry['schema']:
            raise ValueError(f'Foreign or absent case layout: {entry["path"]}')
    output.mkdir(parents=True, exist_ok=True)
    target = output / 'manifest.json'
    target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return {'app': app_dir.name, 'resources': len(resources), 'schemas': len(schemas), 'cases': len(cases),
            'parameters': len(parameters), 'layouts': len(layouts), 'resourceBytes': sum(entry['byteLength'] for entry in resources),
            'manifestSha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'status': 'UNVERIFIED_BY_JVM'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent.parent / 'apps')
    parser.add_argument('--copy-resources', action='store_true')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    apps = sorted(path for path in (args.repository / 'apps').iterdir() if (path / 'build.gradle.kts').is_file())
    reports = [prepare(app, args.output / app.name, args.copy_resources) for app in apps]
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(reports, indent=2) + '\n')
    print(json.dumps(reports, indent=2))


if __name__ == '__main__':
    main()
