package com.xqiou.mantra.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** IDE overlay identity and UTF-16 edit guard. No symbols, parsing, or evaluation live here. */
public final class VersionedDocuments {
    public record Snapshot(String uri, int version, String text) { }
    public record Replacement(int start, int end, String text) { }
    public record Prepared(Snapshot snapshot, List<Replacement> replacements) {
        public String updatedText() {
            StringBuilder result = new StringBuilder(snapshot.text());
            for (int i = replacements.size()-1; i >= 0; i--) {
                Replacement edit = replacements.get(i); result.replace(edit.start(), edit.end(), edit.text());
            }
            return result.toString();
        }
    }
    private final Path root;
    private final Map<Path, Snapshot> open = new HashMap<>();
    public VersionedDocuments(Path root) throws IOException { this.root = root.toRealPath(); }
    public Path confined(String uri) throws IOException {
        URI value = URI.create(uri);
        if (!"file".equals(value.getScheme()) || (value.getAuthority() != null && !value.getAuthority().isEmpty())
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IOException("Only local file URIs are supported");
        }
        Path path = Path.of(value).toRealPath();
        if (!path.startsWith(root)) throw new IOException("Document is outside the workspace");
        return path;
    }
    /** Missing leaves are allowed only for watched-file invalidation, never edits or reads. */
    public Path changedPath(String uri) throws IOException {
        URI value = URI.create(uri);
        if (!"file".equals(value.getScheme()) || value.getQuery() != null || value.getFragment() != null
                || (value.getAuthority() != null && !value.getAuthority().isEmpty())) throw new IOException("Invalid file URI");
        Path candidate = Path.of(value);
        if (java.nio.file.Files.exists(candidate)) return confined(uri);
        Path parent = candidate.getParent().toRealPath();
        if (!parent.startsWith(root)) throw new IOException("Change is outside workspace");
        return parent.resolve(candidate.getFileName());
    }
    public synchronized Snapshot open(String uri, String text) throws IOException {
        Path path = confined(uri);
        Snapshot previous = open.get(path);
        if (previous != null) return previous;
        checkSize(path, text);
        if (open.size() >= 256) throw new IOException("Open document limit exceeded");
        Snapshot snapshot = new Snapshot(uri, 1, text); open.put(path, snapshot); return snapshot;
    }
    public synchronized Snapshot change(String uri, String text) throws IOException {
        Path path = confined(uri); Snapshot old = open.get(path);
        if (old == null) return null;
        checkSize(path, text);
        Snapshot next = new Snapshot(old.uri(), Math.addExact(old.version(), 1), text); open.put(path, next); return next;
    }
    private void checkSize(Path path, String text) throws IOException {
        if (text.length() > 65_536) throw new IOException("Document size limit exceeded");
        long total = text.length();
        for (var entry : open.entrySet()) if (!entry.getKey().equals(path)) total += entry.getValue().text().length();
        if (total > 4 * 1024 * 1024) throw new IOException("Workspace source limit exceeded");
    }
    public synchronized void close(String uri) throws IOException { open.remove(confined(uri)); }
    public synchronized Snapshot get(String uri) throws IOException { return open.get(confined(uri)); }
    public synchronized List<Snapshot> snapshots() { return List.copyOf(open.values()); }
    public synchronized boolean current(Snapshot snapshot) {
        try { return snapshot.equals(open.get(confined(snapshot.uri()))); } catch (IOException problem) { return false; }
    }
    public synchronized List<Prepared> prepare(JsonNode workspaceEdit) throws IOException {
        JsonNode changes = workspaceEdit.get("documentChanges");
        if (workspaceEdit.has("changes") || changes == null || !changes.isArray() || changes.size() > 64) {
            throw new IOException("Only bounded versioned text edits are accepted");
        }
        List<Prepared> result = new ArrayList<>(); Set<Path> seen = new HashSet<>();
        for (JsonNode change : changes) {
            JsonNode document = change.get("textDocument");
            if (change.has("kind") || document == null || !document.path("uri").isTextual()
                    || !document.path("version").isIntegralNumber() || !document.path("version").canConvertToInt()) {
                throw new IOException("Resource operations and unversioned edits are refused");
            }
            Path path = confined(document.path("uri").asText());
            Snapshot snapshot = open.get(path);
            if (snapshot == null || document.path("version").asInt() != snapshot.version()) throw new IOException("Document is closed or changed");
            if (!seen.add(path)) throw new IOException("Repeated document edit");
            JsonNode edits = change.path("edits");
            if (!edits.isArray() || edits.size() > 4096) throw new IOException("Invalid edit list");
            List<Replacement> replacements = new ArrayList<>();
            for (JsonNode edit : edits) {
                if (!edit.path("newText").isTextual() || edit.path("newText").asText().length() > Frames.MAX_BODY) {
                    throw new IOException("Invalid replacement text");
                }
                int start = offset(snapshot.text(), edit.path("range").path("start"));
                int end = offset(snapshot.text(), edit.path("range").path("end"));
                if (start > end) throw new IOException("Reversed edit range");
                replacements.add(new Replacement(start, end, edit.path("newText").asText()));
            }
            replacements.sort(Comparator.comparingInt(Replacement::start));
            for (int i = 1; i < replacements.size(); i++) {
                Replacement previous = replacements.get(i-1), current = replacements.get(i);
                if (current.start() < previous.end() || current.start() == previous.start()) throw new IOException("Overlapping edits");
            }
            Prepared prepared = new Prepared(snapshot, List.copyOf(replacements));
            if (prepared.updatedText().length() > 65_536) throw new IOException("Renamed document exceeds source limit");
            result.add(prepared);
        }
        long total = open.values().stream().mapToLong(snapshot -> snapshot.text().length()).sum();
        for (Prepared prepared : result) total += prepared.updatedText().length() - prepared.snapshot().text().length();
        if (total > 4 * 1024 * 1024) throw new IOException("Renamed workspace exceeds source limit");
        return List.copyOf(result);
    }
    public synchronized void validate(List<Prepared> prepared) throws IOException {
        for (Prepared edit : prepared) if (!current(edit.snapshot())) throw new IOException("Document changed before application");
    }
    public static int offset(String text, JsonNode position) throws IOException {
        JsonNode lineNode = position.path("line"), characterNode = position.path("character");
        if (!lineNode.isIntegralNumber() || !lineNode.canConvertToInt() || !characterNode.isIntegralNumber()
                || !characterNode.canConvertToInt()) throw new IOException("Invalid position");
        int line = lineNode.asInt(), character = characterNode.asInt();
        if (line < 0 || character < 0) throw new IOException("Negative position");
        int start = 0;
        for (int i = 0; i < line; i++) { int newline = text.indexOf('\n', start);
            if (newline < 0) throw new IOException("Line is outside document"); start = newline + 1; }
        int end = text.indexOf('\n', start); if (end < 0) end = text.length();
        if (end > start && text.charAt(end-1) == '\r') end--;
        if (character > end - start) throw new IOException("Character is outside line");
        int offset = start + character;
        if (offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset-1))
                && Character.isLowSurrogate(text.charAt(offset))) throw new IOException("Position splits a UTF-16 pair");
        return offset;
    }
    public static Map<String, Integer> position(String text, int offset) {
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("Offset is outside document");
        int line = 0, start = 0;
        for (int i = 0; i < offset; i++) if (text.charAt(i) == '\n') { line++; start = i+1; }
        return Map.of("line", line, "character", offset-start);
    }
}
