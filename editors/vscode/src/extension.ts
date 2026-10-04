import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  CloseAction,
  ErrorAction,
  CancellationToken,
} from "vscode-languageclient/node";
import { Client, ClientLifecycle } from "./lifecycle";
import { Snapshot, validateEdit } from "./edits";

const clients = new Map<string, ClientLifecycle>();
let output: vscode.LogOutputChannel;
function openSnapshots(folder: vscode.WorkspaceFolder): Snapshot[] {
  return vscode.workspace.textDocuments
    .filter(
      (document) =>
        document.uri.scheme === "file" &&
        vscode.workspace.getWorkspaceFolder(document.uri)?.uri.toString() ===
          folder.uri.toString(),
    )
    .map((document) => ({
      uri: document.uri.toString(),
      version: document.version,
      text: document.getText(),
    }));
}
function create(folder: vscode.WorkspaceFolder): Client {
  const config = vscode.workspace.getConfiguration(
    "mantra.languageServer",
    folder.uri,
  );
  const command = config.get<string>("command", "mantra-lsp");
  const args = config.get<string[]>("args", []);
  if (
    !command ||
    command.includes("\0") ||
    !Array.isArray(args) ||
    args.some((arg) => typeof arg !== "string" || arg.includes("\0"))
  ) {
    throw new Error("Language server command and argument vector are invalid");
  }
  // Undefined transport is stdio and does not append Node-specific command line flags.
  const server: ServerOptions = {
    command,
    args: [...args],
    options: { cwd: folder.uri.fsPath, shell: false, detached: false },
  };
  const watcher = vscode.workspace.createFileSystemWatcher(
    new vscode.RelativePattern(folder, "**/*.mantra"),
  );
  const options: LanguageClientOptions = {
    documentSelector: [
      {
        scheme: "file",
        language: "mantra",
        pattern: { baseUri: folder.uri.toString(), pattern: "**/*.mantra" },
      },
    ],
    workspaceFolder: folder,
    outputChannel: output,
    synchronize: { fileEvents: watcher },
    markdown: { isTrusted: false, supportHtml: false },
    errorHandler: {
      error: () => ({ action: ErrorAction.Shutdown }),
      closed: () => ({ action: CloseAction.DoNotRestart }),
    },
    middleware: {
      sendRequest: async (type, params, token, next) => {
        const result = await next(type, params, token);
        const method = typeof type === "string" ? type : type.method;
        if (method === "textDocument/rename" && result != null) {
          validateEdit(result, folder.uri.fsPath, openSnapshots(folder));
        }
        return result;
      },
      workspace: {
        handleApplyEdit: async (params, next) => {
          try {
            validateEdit(params.edit, folder.uri.fsPath, openSnapshots(folder));
          } catch (error) {
            return { applied: false, failureReason: String(error) };
          }
          return next(params, CancellationToken.None);
        },
      },
    },
  };
  const client = new LanguageClient(
    `mantra-${folder.index}`,
    "Mantra",
    server,
    options,
  );
  return {
    start: () => client.start(),
    stop: async (timeout) => {
      try {
        await client.stop(timeout);
      } finally {
        watcher.dispose();
      }
    },
  };
}
async function reconcile(): Promise<void> {
  if (!vscode.workspace.isTrusted) return;
  const folders =
    vscode.workspace.workspaceFolders?.filter(
      (folder) => folder.uri.scheme === "file",
    ) ?? [];
  const wanted = new Set(folders.map((folder) => folder.uri.toString()));
  for (const [key, client] of clients) {
    if (!wanted.has(key)) {
      clients.delete(key);
      await client.close();
    }
  }
  for (const folder of folders) {
    const key = folder.uri.toString();
    if (!clients.has(key)) {
      const client = new ClientLifecycle(
        () => create(folder),
        (error) => output.error(String(error)),
      );
      clients.set(key, client);
      await client.start();
    }
  }
}
export async function activate(
  context: vscode.ExtensionContext,
): Promise<void> {
  output = vscode.window.createOutputChannel("Mantra Language Server", {
    log: true,
  });
  context.subscriptions.push(
    output,
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      void reconcile().catch((error) => output.error(String(error)));
    }),
    vscode.workspace.onDidGrantWorkspaceTrust(() => {
      void reconcile().catch((error) => output.error(String(error)));
    }),
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration("mantra.languageServer")) {
        for (const client of clients.values())
          void client.restart().catch((error) => output.error(String(error)));
      }
    }),
    vscode.commands.registerCommand(
      "mantra.restartLanguageServer",
      async () => {
        for (const client of clients.values()) await client.restart();
        await reconcile();
      },
    ),
  );
  await reconcile();
}
export async function deactivate(): Promise<void> {
  const owned = [...clients.values()];
  clients.clear();
  await Promise.allSettled(owned.map((client) => client.close()));
}
