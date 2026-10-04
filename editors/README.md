# Mantra editor clients

Status: both clients compile against their public platform APIs. VS Code's five tests and IntelliJ's thirteen protocol tests, including a real installed LSP child process, pass. A full IntelliJ plugin build also passes against the existing local IDE. Interactive IDE installation and extension-host flows have not been verified. Both clients launch an existing server; neither embeds the engine or downloads an IDE/browser.

## Protocol and capability boundary

The installed `mantra-lsp` module in the repository is the authority. Both clients use stdio JSON-RPC, UTF-16 positions, `initialize` / `initialized`, versioned open/change/close buffers, cancellation, and `shutdown` / `exit`. The server owns static diagnostics, completion, hover, definitions, references, lexical binding identity, and safe rename. Clients apply only exact server-provided ranges. Neither searches for identifiers or performs textual rename substitution.

Rename requires all affected documents to be open at their exact server-provided versions. A closed, edited, overlapping, unversioned, resource-operation, or outside-root target refuses the whole result. Opening referenced files and requesting rename again is the recovery path. Canonical filesystem confinement rejects symlink escapes. A client restart invalidates old IntelliJ responses, even when a new buffer has the same text/version. The IDE owns undo; IntelliJ applies a fully preflighted batch in one `WriteCommandAction`.

## VSCode

`vscode-languageclient` is exact-pinned to **10.1.2**. Its primary package metadata declares `^1.91.0`, but the current public Node entry point checks **`^1.106.0`** at runtime; this extension uses the stricter requirement. Node 22.13+ is required. The committed lockfile pins the reviewed dependencies. The actual extension compiled and all five Node tests passed.

```sh
cd vscode
npm ci --ignore-scripts
npm test
```

The test command compiles the actual extension and runs five pure Node tests; it does not download VSCode, start an extension host, or launch a browser. Compilation against the exact published public client API passed.

Configure `mantra.languageServer.command` to the installed launcher (for a checkout: `mantra-lsp/build/install/mantra-lsp/bin/mantra-lsp`) and `mantra.languageServer.args` to an argument array. The executable is passed to `spawn` through the official language client with `shell:false`, `detached:false`; no command string is concatenated. One server is owned per local trusted workspace folder. Folder removal, explicit restart, configuration changes, failed start, and extension deactivation serialize cleanup; the official client receives a five-second stop timeout and owns process termination. Unexpected exits do not auto-restart. Folder-local `.mantra` watchers invalidate closed-file analysis and are disposed with their client.

VSCode registers the standard language-server features through the official client, including normal editor completion/hover/navigation/rename. The raw rename response is preflighted before conversion, and `workspace/applyEdit` has the same strict guard. Markdown HTML/trusted command links are disabled. Workspace trust is required; remote virtual document URIs are excluded. The stdio parser belongs to the official dependency; this client does not claim an extra frame-size bound over that parser.

Pure tests: exact/stale/closed version rejection; canonical symlink escape and resource-operation rejection; UTF-16 and overlap checks; deactivation during pending start; failed-start cleanup and explicit restart.

## IntelliJ Community-compatible client

The plugin uses only classic `com.intellij.modules.platform` APIs. It has **no dependency on `com.intellij.modules.lsp`, Ultimate, native LSP services, or paid APIs**. JetBrains' July 2026 SDK documentation still excludes open-source IntelliJ builds from its native LSP integration, while its September 2025 announcement enables native LSP in the unified/Ultimate free tier. Classic APIs avoid relying on that distribution distinction.

Target baseline: platform build **252 / 2025.2**, Java 21. An installed IDE was found at:

```
/Users/qiouyang/Applications/IntelliJ IDEA 2025.2.6.3.app/Contents
```

The observed installed product is IU, build 252.28539.97. Compiling against it verifies signatures only; actual Community compatibility is not claimed until a Community distribution or plugin verifier check runs. The plugin descriptor declares only the shared platform module. No SDK is downloaded by this build, and it never launches or registers an IDE.

Using the repository's existing Gradle executable (the preparation project deliberately has no copied Gradle wrapper):

```sh
/absolute/path/to/mantra/gradlew -p /absolute/path/to/intellij \
  -PideaHome='/absolute/path/to/installed/IDE/Contents' test pluginZip
```

`pluginZip` contains the plugin jar and exact Jackson 2.18.3 dependencies. Installation from disk is a later explicit user action; no installation was performed here.

The first adapter is **action based**: `Tools → Mantra` offers Start/Stop, completion at caret, hover at caret, definitions, references, and rename. It provides live diagnostic underlines/tooltips on opened editors. Completion and reference results use chooser popups; hover is plaintext. It does not yet replace the IDE's built-in automatic hover/completion/Find Usages/refactoring providers. This boundary is explicit, so compilation of the thin adapter cannot be mistaken for native IDE feature parity.

Start is an explicit editor action with an executable and JSON argument-vector prompt; no executable from an untrusted project file is automatically run. A local project root is required. One project owns its stdio process and background worker. No RPC wait blocks the EDT. Responses and diagnostics check the active connection/buffer version before UI publication. Requests time out after ten seconds and send cancellation. Close attempts shutdown for two seconds, exit, waits two seconds, then terminates only the owned child with a one-second grace period. Stderr is drained and discarded without mixing it into protocol stdout. Close disposes owned streams/workers and highlighting. The bridge bounds frames to 1 MiB, headers to 8 KiB, nesting to 64, pending requests to 128, open buffers to 256, each buffer to 65,536 UTF-16 characters, and the total overlay to 4 MiB.

Twelve pure JVM tests cover Unicode byte framing and multiple messages; malformed/truncated/oversize/duplicate framing; exact/stale/closed buffer edits; invalidated prepared batches; UTF-16 and reversed ranges; resource operations; canonical symlink escape; CRLF and source limits; in-memory stdio initialize/request/shutdown/exit; real cancellation frames; pending-request limits and close. They use no running IDE or real child process. Temporary files, pipes, and test-owned peer threads are closed in test cleanup.

## Primary references checked

- [Microsoft language client package metadata](https://github.com/microsoft/vscode-languageserver-node/blob/main/client/package.json)
- [Microsoft Node client runtime, executable vector, and process lifecycle](https://github.com/microsoft/vscode-languageserver-node/blob/main/client/src/node/main.ts)
- [Microsoft common middleware / workspace edit API](https://github.com/microsoft/vscode-languageserver-node/blob/main/client/src/common/client.ts)
- [VSCode language-server extension guide](https://code.visualstudio.com/api/language-extensions/language-server-extension-guide)
- [VSCode language feature API](https://code.visualstudio.com/api/language-extensions/programmatic-language-features)
- [JetBrains native LSP SDK and availability](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html)
- [JetBrains unified/free LSP announcement](https://blog.jetbrains.com/platform/2025/09/the-lsp-api-is-now-available-to-all-intellij-idea-users-and-plugin-developers/)
- [JetBrains plugin descriptor](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html)

Actual validation: VS Code compilation and five tests; full IntelliJ platform compilation, twelve isolated tests and plugin ZIP; thirteen protocol tests with an installed LSP command, including one real child-process integration. That integration checks UTF-16, two-document versioned rename, stale edit rejection, cancellation bytes and shutdown/exit 0; temporary workspace and process cleanup are verified. Native interactive IDE flows and a Community distribution/plugin verifier have not been run.

The VS Code client is compiled against its pinned public client API. The IntelliJ build uses an existing platform via `-PideaHome=/path/to/Contents`; CI executes the protocol/document tests and actual installed-server integration with `-PprotocolOnly=true -PlspCommand=/absolute/path/to/mantra-lsp`, which excludes platform classes and cannot produce an installable plugin ZIP. A full platform build is required for `pluginZip`. No task downloads or launches an IDE.
