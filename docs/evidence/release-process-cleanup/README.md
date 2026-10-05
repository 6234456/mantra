# Local release-process verification — 2026-10-05

The implementation tested is `75d0258a377d27bff828a3e7217bb75c61a391a5`.
These receipts verify local preparation with a disposable TEST signer. They do not establish
production signer authority, namespace/token access, a hosted workflow run or a Maven upload.
Mantra's default remains `1.0.0-rc.1`; formal publication and secret provisioning are deferred.

- [Script suite](script-suite.json): 114 passing Python regressions, including dummy child-process
  cancellation and mocked cleanup failures. These unit tests do not execute Gradle signing.
- [Actual offline Gradle timeout](gradle-timeout-test.json): a blocking task started a separately
  grouped daemon; timeout handling and owned-home daemon/PID shutdown passed. Its temporary
  project/cache were removed. No signing key was used and unrelated processes were preserved.
- [Actual canonical TEST preparation](canonical-test.json): six signed local `1.0.0` publications,
  144 verified bundle entries, five fresh POM-only and five fresh ordinary Gradle consumers.
  All owned launcher/daemon/GPG cleanup flags passed; private staging and the outer TEST key/bundle
  were removed. The source commit, public Normein JAR hash and signed-byte hashes are retained.
- [Final ordinary-consumer cleanup](ordinary-consumer-cleanup.json): the last consumer mode's
  recorded daemon exit and temporary-state removal. The coordinator verifies each mode before
  returning success; this file is the retained last-mode receipt, not two separate receipts.

The earlier `a1e6e65` canonical TEST receipt remains unchanged in the public-kernel performance
archive. No runtime JAR, signed TEST bundle, private key, credential or full signing log is stored
here. See [the cleanup controls](../../central-publication.md#task-owned-process-cleanup).

## Compiled version identity guard

Implementation `d052f735b9700f41c5379435bd8d5f6f10a555bc` fixes stale inline version constants
when switching release versions and Kotlin compiler modes. Generated fields use ordinary properties;
the unchanged public getters read the selected build's values. The independent Kotlin consumer now
asserts the selected Mantra artifact version and Normein `0.3.0` before calculation.

[Actual public-JAR probes](runtime-version-switch.json) passed RC → local `1.0.0` → RC using
daemon → in-process → daemon compilation. [A new canonical TEST run](canonical-version-guard-test.json)
passed all 144 bundle entries and both five-consumer modes with this guard. Its receipt retains
both consumer cleanup records, owned daemon/GPG shutdown and outer TEST key/bundle removal.
Earlier signing receipts did not independently assert the embedded Mantra version.
The [post-signing installed CLI probe](post-signing-rc-identity.json) also passed after restoring
default RC artifacts, covering the exact transition that exposed the regression.

The [static measured-core proof](measured-core-bytecode-proof.json) binds an independently retained
JAR to the exact SHA-256 in both unchanged `a1e6e65` performance runtime inventories. Its public
getters return RC and Normein `0.3.0`. This confirms the 390-sample measurement's binary metadata;
it does not infer a separately uninventoried batch CLI JAR's getter. Batch numeric/lifecycle
evidence and its explicit compatibility option retain their own scope. The binary is retained
locally, not committed. Recheck a matching binary with the [static verifier](verify-measured-runtime-version.py):

```sh
python3 docs/evidence/release-process-cleanup/verify-measured-runtime-version.py \
  --jar /physical/path/to/the/measured-core.jar
```
