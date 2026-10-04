#!/usr/bin/env bash
# Exercise every CLI command against demonstration documents, cleaning the task-owned server.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
cli="$root/mantra-cli/build/install/mantra/bin/mantra"
[ -x "$cli" ] || { echo 'Run ./gradlew :mantra-cli:installDist first' >&2; exit 1; }
task_dir="$(mktemp -d "${TMPDIR:-/tmp}/mantra-cli-smoke.XXXXXX")"
server_pid=''
cleanup() {
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    if kill -0 "$server_pid" 2>/dev/null; then
      echo "Task-owned server $server_pid did not stop" >&2
      return 1
    fi
  fi
  rm -rf "$task_dir"
  [ ! -e "$task_dir" ]
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
schema='apps/de-est/schema.mantra'
case_file='apps/de-est/case-mustermann.mantra'
layout='apps/de-est/layout.mantra'
"$cli" run "$schema" --case "$case_file" --layout "$layout" --out "$task_dir/paper.txt"
"$cli" check "$schema" --case "$case_file" > "$task_dir/check.txt"
"$cli" catalog > "$task_dir/catalog.txt"
"$cli" fixtures "$case_file" --workspace apps --out "$task_dir/fixtures" > "$task_dir/fixtures.txt"
"$cli" diff "$schema" --case "$case_file" --variant-parameters apps/de-est/params-2026.mantra --out "$task_dir/compare.json"
"$cli" explain "$schema" --case "$case_file" --address tarifliche-est --layout "$layout" --out "$task_dir/explain.json"
"$cli" explain apps/ifrs-income-taxes/schema.mantra --case apps/ifrs-income-taxes/case-demo.mantra \
  --address aggregate.effective-tax-rate --out "$task_dir/aggregate.json"
"$cli" run apps/fixed-assets/schema.mantra --case apps/fixed-assets/case-demo.mantra \
  --layout apps/fixed-assets/layout-transpose.mantra --out "$task_dir/assets.txt"
"$cli" explain apps/fixed-assets/schema.mantra --case apps/fixed-assets/case-demo.mantra \
  --address carrying-opening --coord Machine,P2 --out "$task_dir/previous.json"
"$cli" explain apps/fixed-assets/schema.mantra --case apps/fixed-assets/case-demo.mantra \
  --address aggregate.carrying-closing --coord asset=Machine --out "$task_dir/stock.json"
"$cli" run apps/ifrs-income-taxes/schema.mantra --case apps/ifrs-income-taxes/case-unreconciled.mantra \
  --out "$task_dir/business-failure.txt"
"$cli" serve apps --port 0 > "$task_dir/server.log" 2>&1 &
server_pid=$!
for _ in {1..100}; do
  if [[ "$(cat "$task_dir/server.log")" == *'at http://127.0.0.1:'* ]]; then break; fi
  kill -0 "$server_pid" 2>/dev/null || { cat "$task_dir/server.log" >&2; exit 1; }
  sleep 0.1
done
server_url="$(sed -n 's/.* at \(http:\/\/127.0.0.1:[0-9]*\)\/.*/\1/p' "$task_dir/server.log")"
[ -n "$server_url" ]
curl --fail --silent "$server_url/api/v1/workspace" > "$task_dir/workspace.json"
curl --fail --silent "$server_url/api/v1/cases/ifrs-income-taxes%2Fcase-unreconciled%2Emantra/run" \
  > "$task_dir/business-run.json"
python3 - "$task_dir" <<'PY'
import json
from decimal import Decimal
from pathlib import Path
import sys
base = Path(sys.argv[1])
assert 'fin/pmt' in (base / 'catalog.txt').read_text()
assert (base / 'paper.txt').stat().st_size > 0
assert json.loads((base / 'fixtures/index.json').read_text())['cases']
assert json.loads((base / 'compare.json').read_text())['data']['mainline']
assert json.loads((base / 'explain.json').read_text())['data']['steps']
assert json.loads((base / 'workspace.json').read_text())['data']['cases']
aggregate = json.loads((base / 'aggregate.json').read_text())
assert aggregate['contract'] == 'mantra.workbench/3'
assert aggregate['data']['aggregate']['result']['n'] == '0.249375'
assert aggregate['data']['aggregate']['activeMemberCount'] == 2
assert aggregate['data']['steps'] == []
previous = json.loads((base / 'previous.json').read_text())['data']
assert Decimal(previous['result']['value']['n']) == Decimal('76000')
prior = next(item for item in previous['references'] if item['kind'] == 'previous')
assert prior['address']['node'] == 'carrying-closing'
assert prior['address']['coord'] == ['Machine', 'P1']
stock = json.loads((base / 'stock.json').read_text())['data']['aggregate']
assert stock['kind'] == 'boundary'
assert stock['boundary'] == 'last'
assert Decimal(stock['result']['n']) == Decimal('10000')
assert (base / 'assets.txt').stat().st_size > 0
business = json.loads((base / 'business-run.json').read_text())['data']
assert business['succeeded'] and not business['validationPassed']
assert len([item for item in business['diagnostics'] if item['category'] == 'business']) == 3
assert 'MANTRA-RECONCILE-FAILED' in (base / 'business-failure.txt').read_text()
PY
echo 'PASS CLI: run, check, catalog, fixtures, diff, explain, serve'
