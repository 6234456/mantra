# Reproducible SQLite input snapshots

The Python 3.11+ standard-library adapter `scripts/snapshot-sqlite.py` extracts a parameterized,
read-only SQLite query into a JSON table input accepted by the existing `JsonSource`. The engine
does not connect to the database. Keep each snapshot alongside its case to replay the same facts
after the database changes; extract into a new directory when new facts are needed.

## Adapter contract: `mantra.data-snapshot/1`

`extract` publishes one directory containing exactly `inputs.json` and `manifest.json`. The directory
appears atomically after extraction, validation and hashing succeed. An existing byte-identical
bundle is accepted without rewriting it. Different existing contents are rejected; there is no
overwrite option. Query, conversion or budget failures leave no published half-bundle.

```sh
python3 scripts/snapshot-sqlite.py extract --root ./workspace \
  --database facts.sqlite --query-file select.sql --params parameters.json \
  --mapping columns.json --input records --out snapshots/revision-1
python3 scripts/snapshot-sqlite.py verify --root ./workspace --snapshot snapshots/revision-1
```

All supplied paths are relative to `--root` or absolute paths inside it. The root defaults to the
current directory. Inputs must be regular files, and symlinks below the root are rejected. Output
parents must already exist; the command creates only its temporary directory and final bundle.

`select.sql` contains one `SELECT` or `WITH … SELECT` statement with a top-level `ORDER BY`.
Include a stable tie-breaker so records have a deterministic order. Write values as named SQLite
parameters instead of interpolating them into SQL:

```sql
SELECT code AS record_id, amount_text, enabled, memo
FROM facts
WHERE period = :period
ORDER BY code
```

`parameters.json` is an object of named bindings. Values are strings, integers, booleans or null.
Use strings for decimal parameters; JSON floating-point numbers are rejected. Volatile random and
current date/time functions, extension loading, writes, pragmas and attached databases are rejected.

```json
{"period": "P1"}
```

`columns.json` maps target table-column ids to source aliases and explicit conversion policies:

```json
{
  "id": {"source": "record_id", "type": "text", "null": "error"},
  "amount": {"source": "amount_text", "type": "decimal"},
  "active": {"source": "enabled", "type": "boolean"},
  "memo": {"source": "memo", "type": "text", "null": "omit"}
}
```

- `decimal` accepts SQLite integers and finite decimal text. It emits an exact JSON numeric token
  with the decimal scale retained; it never converts through a Python `float`. Numbers are limited
  to 1,024 significant digits and an exponent/scale magnitude of 1,024.
- `integer` accepts SQLite integers or signed base-10 integer text, up to 1,024 digits.
- `boolean` accepts SQLite integer `0`/`1` or the exact text `false`/`true`.
- `text` accepts SQLite text only.
  For schema keyword or ISO date columns, transport their names/dates as text; the existing engine
  converts and validates them against the declared column type.
- SQLite `REAL` and `BLOB` results are rejected for every mapping type. Store exact decimals as
  integers or text. An explicit SQL cast of an existing `REAL` does not recover its lost precision.
- `null` is `include` by default, writing an explicit JSON null; `omit` leaves the target key absent,
  and `error` rejects a null source cell. Unmapped source columns are absent from each record.
  Zero and false remain zero and false. The engine performs schema type, required-column and other
  input validation; the adapter does not fill defaults or calculate business results.

The output is compact, UTF-8 JSON with sorted object keys and one final newline. Query row order is
retained. For example:

```json
{"inputs":{"records":[{"active":false,"amount":0.00,"id":"A"}]}}
```

Bind the snapshot using existing case syntax:

```clojure
(case example {:schema "example/schema"}
  (sources (json {:path "snapshots/revision-1/inputs.json"})))
```

No database adapter or new source type is added to the engine. The case still uses ordinary JSON
source precedence, type validation, revision capture and Explain provenance. Files can be archived
or copied with the case independently of the database.

## Manifest and consistency

The manifest has `format: "mantra.data-snapshot/1"`, adapter/tool versions, the target `input`,
SQLite version, the database path relative to the chosen root, captured database schema, exact SQL,
named parameters, column mapping, selected source aliases, row count and byte budgets. It includes
SHA-256 fingerprints of the captured database image, schema, query, parameters, mapping and complete
extraction identity, plus the exact `inputs.json` byte count and digest.

The source is opened with SQLite `mode=ro`. SQLite's online backup API captures a consistent private
database image; schema and query are then read from that image. Its SHA-256 identifies the complete
captured database, including committed WAL facts, rather than an unsafe checksum of only a live
database's main file. The private image is removed after extraction. No timestamps are included, so
repeating the same extraction has identical output. A changed database may change the extraction
fingerprint while leaving the input digest unchanged if the selected facts are unchanged.

`verify` reads only the archived files, checks their format, byte/hash integrity, row count and
manifest fingerprints, and does not open the current database. It optionally accepts
`--expected-manifest-sha256` to compare against an independently recorded manifest digest. Hash
consistency does not authenticate an untrusted manifest. The command prints both snapshot and
manifest digests; keep the latter with the review record.

Default limits are 10,000 result rows, 8 MiB for the complete output bundle, 128 MiB for the captured
database image and 15 seconds for extraction. `--max-rows`, `--max-bytes`, `--max-database-bytes` and
`--timeout` select explicit alternative budgets. Query/parameter/mapping files are limited to 64 KiB
each. SQLite value, SQL length and progress limits prevent a query from bypassing these budgets.
Budget failures reject the entire extraction; rows are never silently truncated.

## Verification

Run the standard-library regression tests:

```sh
python3 -m unittest discover -s scripts/tests -p test_snapshot_sqlite.py
```

Set `MANTRA_SNAPSHOT_TEST_CLI` to an installed `mantra` executable to include the real-engine
cross-source check. The test creates a fictional local database and equivalent CSV/JSON facts in a
temporary workspace, then checks that all three sources produce identical engine values and table
input diagnostics. Channel-specific source provenance remains distinct. It does not alter
demonstration applications.
