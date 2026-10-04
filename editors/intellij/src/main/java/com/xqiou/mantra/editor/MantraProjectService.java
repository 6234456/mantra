package com.xqiou.mantra.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.ui.JBColor;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Classic Community platform APIs. Protocol results remain authoritative; no PSI semantic model. */
public final class MantraProjectService implements Disposable {
    private final Project project;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mantra-editor-project"); thread.setDaemon(true); return thread;
    });
    private final Map<Document, List<RangeHighlighter>> marks = new HashMap<>(); // EDT owned
    private volatile StdioClient client;
    private volatile VersionedDocuments documents;
    private volatile boolean disposed;
    public MantraProjectService(Project project) {
        this.project = project;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) { changed(event.getDocument()); }
        }, this);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            new FileEditorManagerListener() {
                @Override public void fileOpened(FileEditorManager manager, VirtualFile file) { opened(file); }
                @Override public void fileClosed(FileEditorManager manager, VirtualFile file) { closed(file); }
            });
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(List<? extends VFileEvent> events) {
                StdioClient active = client; VersionedDocuments overlay = documents;
                if (active == null || overlay == null || !active.ready()) return;
                List<Map<String, Object>> changes = new ArrayList<>();
                for (VFileEvent event : events) {
                    VirtualFile file = event.getFile(); if (!accepts(file)) continue;
                    try { overlay.changedPath(uri(file)); changes.add(Map.of("uri", uri(file), "type",
                        event instanceof VFileDeleteEvent ? 3 : event instanceof VFileCreateEvent ? 1 : 2)); }
                    catch (IOException ignored) { }
                }
                if (!changes.isEmpty()) active.notify("workspace/didChangeWatchedFiles", active.json(Map.of("changes", changes)));
            }
        });
    }
    public boolean running() { StdioClient current = client; return current != null && current.ready(); }
    public void start(List<String> argv) {
        if (disposed) return;
        Path root = Path.of(Objects.requireNonNull(project.getBasePath(), "Open a local project first"));
        worker.execute(() -> {
            if (disposed) return;
            StdioClient connection = null;
            try {
                stop();
                VersionedDocuments overlay = new VersionedDocuments(root);
                connection = StdioClient.launch(argv, root, (method, params) -> notification(overlay, method, params));
                documents = overlay; client = connection;
                connection.initialize(root).get(10, TimeUnit.SECONDS);
                if (disposed) { stop(); return; }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed() && running()) for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) opened(file);
                });
            } catch (Exception error) { if (connection != null) connection.close(); client = null; showError(error); }
        });
    }
    public void requestStop() { if (!disposed) worker.execute(this::stop); }
    private void stop() { StdioClient old = client; client = null; documents = null; if (old != null) old.close(); clearAll(); }
    private static boolean accepts(VirtualFile file) { return file != null && !file.isDirectory() && "mantra".equals(file.getExtension()); }
    private void opened(VirtualFile file) {
        if (!accepts(file) || !running()) return;
        Document document = FileDocumentManager.getInstance().getDocument(file); if (document == null) return;
        try {
            var old = documents.get(uri(file)); if (old != null) return;
            var snapshot = documents.open(uri(file), document.getText());
            client.notify("textDocument/didOpen", client.json(Map.of("textDocument", Map.of("uri", snapshot.uri(),
                "languageId", "mantra", "version", snapshot.version(), "text", snapshot.text()))));
        } catch (IOException error) { /* Documents outside the local project are not sent. */ }
    }
    private void changed(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (!accepts(file) || !running()) return;
        try {
            var snapshot = documents.change(uri(file), document.getText()); if (snapshot == null) return;
            clear(document);
            client.notify("textDocument/didChange", client.json(Map.of("textDocument", Map.of("uri", snapshot.uri(),
                "version", snapshot.version()), "contentChanges", List.of(Map.of("text", snapshot.text())))));
        } catch (IOException error) { showError(error); }
    }
    private void closed(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getCachedDocument(file); if (document != null) clear(document);
        if (!accepts(file) || !running()) return;
        try { documents.close(uri(file)); client.notify("textDocument/didClose", client.json(Map.of("textDocument", Map.of("uri", uri(file))))); }
        catch (IOException ignored) { }
    }
    public record Query(VersionedDocuments.Snapshot snapshot, CompletableFuture<JsonNode> response, VersionedDocuments owner) { }
    public Query query(Editor editor, String method, Map<String, Object> additional) throws IOException {
        StdioClient active = client; VersionedDocuments overlay = documents;
        if (active == null || overlay == null || !active.ready()) throw new IOException("Start the Mantra language server first");
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (!accepts(file)) throw new IOException("Open a .mantra document");
        opened(file);
        var snapshot = overlay.get(uri(file)); if (snapshot == null) throw new IOException("Document is outside project");
        Map<String, Object> params = new HashMap<>(additional);
        params.put("textDocument", Map.of("uri", snapshot.uri()));
        params.put("position", VersionedDocuments.position(snapshot.text(), editor.getCaretModel().getOffset()));
        CompletableFuture<JsonNode> request = active.request(method, active.json(params));
        CompletableFuture<JsonNode> timed = new CompletableFuture<>();
        request.whenComplete((result, failure) -> {
            if (failure != null) timed.completeExceptionally(failure); else timed.complete(result);
        });
        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
            if (timed.completeExceptionally(new TimeoutException("Language request timed out"))) request.cancel(true);
        });
        return new Query(snapshot, timed, overlay);
    }
    public void onCurrent(Query query, java.util.function.Consumer<JsonNode> result) {
        query.response().whenComplete((value, failure) -> ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) return;
            if (failure != null) { showError(failure); return; }
            if (documents != query.owner() || !query.owner().current(query.snapshot())) { showError(new IOException("Document changed; request again")); return; }
            result.accept(value);
        }));
    }
    public void applyWorkspaceEdit(JsonNode edit) {
        try {
            var prepared = documents.prepare(edit);
            List<Document> targets = new ArrayList<>();
            for (var item : prepared) {
                VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(documents.confined(item.snapshot().uri()));
                Document document = file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
                if (document == null || !document.isWritable() || !document.getText().equals(item.snapshot().text())) throw new IOException("Edit target is closed or changed");
                targets.add(document);
            }
            WriteCommandAction.runWriteCommandAction(project, "Rename Mantra Symbol", null, () -> {
                try { documents.validate(prepared); }
                catch (IOException stale) { throw new IllegalStateException(stale); }
                // Complete preflight precedes every mutation. One IDE command supplies atomic undo.
                for (int index = 0; index < prepared.size(); index++) {
                    // Stage exact server edits first, then one event per document; intermediate text cannot exceed server limits.
                    targets.get(index).setText(prepared.get(index).updatedText());
                }
            });
        } catch (Exception error) { showError(error); }
    }
    public void applyCompletion(Query query, JsonNode item) {
        var snapshot = query.snapshot();
        JsonNode edit = item.get("textEdit"); if (edit == null) { showError(new IOException("Server did not provide a completion edit")); return; }
        applyWorkspaceEdit(client.json(Map.of("documentChanges", List.of(Map.of("textDocument", Map.of("uri", snapshot.uri(),
            "version", snapshot.version()), "edits", List.of(edit))))));
    }
    public void navigate(JsonNode location) {
        try {
            Path path = documents.confined(location.path("uri").asText());
            VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
            if (file == null) throw new IOException("Definition file is unavailable");
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) throw new IOException("Definition document is unavailable");
            int offset = VersionedDocuments.offset(document.getText(), location.path("range").path("start"));
            new OpenFileDescriptor(project, file, offset).navigate(true);
        } catch (Exception error) { showError(error); }
    }
    private void notification(VersionedDocuments owner, String method, JsonNode params) {
        if (!"textDocument/publishDiagnostics".equals(method)) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed() || documents != owner) return;
            try {
                var snapshot = documents.get(params.path("uri").asText());
                if (snapshot == null || !params.path("version").isIntegralNumber() || params.path("version").asInt() != snapshot.version()) return;
                VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(documents.confined(snapshot.uri()));
                Document document = file == null ? null : FileDocumentManager.getInstance().getCachedDocument(file);
                if (document == null || !snapshot.text().equals(document.getText())) return;
                clear(document); List<RangeHighlighter> highlights = new ArrayList<>();
                for (JsonNode diagnostic : params.path("diagnostics")) {
                    int start = VersionedDocuments.offset(snapshot.text(), diagnostic.path("range").path("start"));
                    int end = VersionedDocuments.offset(snapshot.text(), diagnostic.path("range").path("end"));
                    Color color = diagnostic.path("severity").asInt(1) == 1 ? JBColor.RED : JBColor.ORANGE;
                    TextAttributes attributes = new TextAttributes(null, null, color, EffectType.WAVE_UNDERSCORE, 0);
                    for (Editor editor : EditorFactory.getInstance().getEditors(document, project)) {
                        var highlight = editor.getMarkupModel().addRangeHighlighter(start, end,
                            HighlighterLayer.ERROR, attributes, HighlighterTargetArea.EXACT_RANGE);
                        highlight.setErrorStripeTooltip(diagnostic.path("message").asText()); highlights.add(highlight);
                    }
                }
                marks.put(document, highlights);
            } catch (IOException ignored) { }
        });
    }
    private void clear(Document document) { var highlights = marks.remove(document); if (highlights != null) highlights.forEach(RangeHighlighter::dispose); }
    private void clearAll() { ApplicationManager.getApplication().invokeLater(() -> { marks.values().forEach(list -> list.forEach(RangeHighlighter::dispose)); marks.clear(); }); }
    public void showError(Throwable error) { ApplicationManager.getApplication().invokeLater(() -> {
        if (!project.isDisposed()) Messages.showErrorDialog(project, Objects.toString(error.getMessage(), error.getClass().getSimpleName()), "Mantra Language Tools");
    }); }
    private static String uri(VirtualFile file) { return Path.of(file.getPath()).toUri().toString(); }
    @Override public void dispose() {
        disposed = true; worker.shutdownNow();
        StdioClient active = client; client = null; documents = null;
        if (active != null) {
            Thread closing = new Thread(active::close, "mantra-editor-project-close"); closing.setDaemon(true); closing.start();
        }
        clearAll();
    }
}
