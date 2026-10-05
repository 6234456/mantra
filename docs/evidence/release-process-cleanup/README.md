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
