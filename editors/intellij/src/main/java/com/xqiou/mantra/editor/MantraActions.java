package com.xqiou.mantra.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import java.io.IOException;
import java.util.*;
import javax.swing.*;

/** Thin editor actions for distributions without JetBrains' native LSP module. */
public final class MantraActions {
    private MantraActions() { }
    private abstract static class EditorAction extends AnAction {
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent event) {
            Editor editor = event.getData(CommonDataKeys.EDITOR);
            var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
            event.getPresentation().setEnabledAndVisible(event.getProject() != null && editor != null
                && file != null && "mantra".equals(file.getExtension()));
        }
        @Override public final void actionPerformed(AnActionEvent event) {
            Project project = event.getProject(); Editor editor = event.getData(CommonDataKeys.EDITOR);
            if (project == null || editor == null) return;
            MantraProjectService service = project.getService(MantraProjectService.class);
            try { run(service, editor, project); } catch (Exception error) { service.showError(error); }
        }
        protected abstract void run(MantraProjectService service, Editor editor, Project project) throws Exception;
    }
    public static final class Start extends AnAction {
        @Override public void actionPerformed(AnActionEvent event) {
            Project project = event.getProject(); if (project == null) return;
            String command = Messages.showInputDialog(project, "Executable to run for this local project (no shell).",
                "Start Mantra Language Server", null, "mantra-lsp", null);
            if (command == null || command.isBlank()) return;
            String raw = Messages.showInputDialog(project, "Additional arguments as a JSON array of strings.",
                "Mantra Process Arguments", null, "[]", null);
            if (raw == null) return;
            var service = project.getService(MantraProjectService.class);
            try {
                JsonNode args = new Frames(java.io.InputStream.nullInputStream(), java.io.OutputStream.nullOutputStream()).mapper.readTree(raw);
                if (!args.isArray() || args.size() > 63) throw new IOException("Arguments must be a bounded JSON array");
                List<String> argv = new ArrayList<>(); argv.add(command);
                for (JsonNode arg : args) { if (!arg.isTextual()) throw new IOException("Argument must be a string"); argv.add(arg.asText()); }
                service.start(argv);
            } catch (Exception error) { service.showError(error); }
        }
    }
    public static final class Stop extends AnAction {
        @Override public void actionPerformed(AnActionEvent event) { if (event.getProject() != null)
            event.getProject().getService(MantraProjectService.class).requestStop(); }
    }
    public static final class Completion extends EditorAction {
        @Override protected void run(MantraProjectService service, Editor editor, Project project) throws Exception {
            var query = service.query(editor, "textDocument/completion", Map.of());
            service.onCurrent(query, result -> {
                JsonNode items = result.isArray() ? result : result.path("items");
                choose(editor, items, item -> item.path("label").asText() + "  " + item.path("detail").asText(),
                    item -> service.applyCompletion(query, item));
            });
        }
    }
    public static final class Hover extends EditorAction {
        @Override protected void run(MantraProjectService service, Editor editor, Project project) throws Exception {
            var query = service.query(editor, "textDocument/hover", Map.of());
            service.onCurrent(query, result -> {
                String text = result.path("contents").path("value").asText();
                Messages.showInfoMessage(project, text.isEmpty() ? "No static hover information at this position." : text, "Mantra Hover");
            });
        }
    }
    public static final class Definition extends Locations {
        @Override protected String method() { return "textDocument/definition"; }
    }
    public static final class References extends Locations {
        @Override protected String method() { return "textDocument/references"; }
    }
    private abstract static class Locations extends EditorAction {
        protected abstract String method();
        @Override protected void run(MantraProjectService service, Editor editor, Project project) throws Exception {
            var query = service.query(editor, method(), Map.of("context", Map.of("includeDeclaration", true)));
            service.onCurrent(query, result -> {
                List<JsonNode> items = new ArrayList<>(); if (result.isArray()) result.forEach(items::add);
                else if (result.isObject()) items.add(result);
                if (items.size() == 1) service.navigate(items.getFirst());
                else choose(editor, items, item -> item.path("uri").asText() + ":"
                    + (item.path("range").path("start").path("line").asInt() + 1), service::navigate);
            });
        }
    }
    public static final class Rename extends EditorAction {
        @Override protected void run(MantraProjectService service, Editor editor, Project project) throws Exception {
            var prepare = service.query(editor, "textDocument/prepareRename", Map.of());
            service.onCurrent(prepare, range -> {
                try {
                    int start = VersionedDocuments.offset(prepare.snapshot().text(), range.path("start"));
                    int end = VersionedDocuments.offset(prepare.snapshot().text(), range.path("end"));
                    String old = prepare.snapshot().text().substring(start, end);
                    String name = Messages.showInputDialog(project, "New name for this statically resolved symbol.", "Rename Mantra Symbol", null, old, null);
                    if (name == null || name.isBlank()) return;
                    var query = service.query(editor, "textDocument/rename", Map.of("newName", name));
                    service.onCurrent(query, service::applyWorkspaceEdit);
                } catch (Exception error) { service.showError(error); }
            });
        }
    }
    private static void choose(Editor editor, JsonNode array, java.util.function.Function<JsonNode, String> label,
            java.util.function.Consumer<JsonNode> select) {
        List<JsonNode> items = new ArrayList<>(); if (array.isArray()) array.forEach(items::add); choose(editor, items, label, select);
    }
    private static void choose(Editor editor, List<JsonNode> items, java.util.function.Function<JsonNode, String> label,
            java.util.function.Consumer<JsonNode> select) {
        if (items.isEmpty()) return;
        JBPopupFactory.getInstance().createPopupChooserBuilder(items).setTitle("Mantra")
            .setRenderer(new DefaultListCellRenderer() {
                @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                    return super.getListCellRendererComponent(list, label.apply((JsonNode) value), index, selected, focused);
                }
            }).setItemChosenCallback(select::accept).createPopup().showInBestPositionFor(editor);
    }
}
