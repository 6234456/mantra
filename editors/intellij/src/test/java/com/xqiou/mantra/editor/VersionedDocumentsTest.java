package com.xqiou.mantra.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

class VersionedDocumentsTest {
    @TempDir Path root;
    private final Frames json = new Frames(java.io.InputStream.nullInputStream(), java.io.OutputStream.nullOutputStream());
    String file(String name, String text) throws IOException { Path path = root.resolve(name); Files.writeString(path, text); return path.toUri().toString(); }
    JsonNode edit(String uri, int version, int start, int end) {
        return json.mapper.valueToTree(Map.of("documentChanges", List.of(Map.of("textDocument", Map.of("uri", uri, "version", version),
            "edits", List.of(Map.of("range", Map.of("start", Map.of("line", 0, "character", start),
                "end", Map.of("line", 0, "character", end)), "newText", "replaced"))))));
    }
    @Test void renameUsesExactOpenVersionAndPreservesZeroWidthEdits() throws Exception {
        String uri = file("schema.mantra", "abc"); var docs = new VersionedDocuments(root); docs.open(uri, "abc");
        assertEquals(1, docs.prepare(edit(uri, 1, 0, 3)).size());
        assertEquals(0, docs.prepare(edit(uri, 1, 0, 0)).getFirst().replacements().getFirst().start());
        docs.change(uri, "abcd"); assertThrows(IOException.class, () -> docs.prepare(edit(uri, 1, 0, 3)));
        docs.close(uri); assertThrows(IOException.class, () -> docs.prepare(edit(uri, 2, 0, 3)));
    }
    @Test void changedTargetInvalidatesWholePreparedBatchBeforeApplication() throws Exception {
        String uri = file("case.mantra", "old"); var docs = new VersionedDocuments(root); docs.open(uri, "old");
        var prepared = docs.prepare(edit(uri, 1, 0, 3)); docs.change(uri, "new");
        assertThrows(IOException.class, () -> docs.validate(prepared));
        assertEquals("new", docs.get(uri).text());
    }
    @Test void surrogateAndReversedRangesAreRejected() throws Exception {
        String uri = file("case.mantra", "a😀b"); var docs = new VersionedDocuments(root); docs.open(uri, "a😀b");
        assertThrows(IOException.class, () -> docs.prepare(edit(uri, 1, 1, 2)));
        assertThrows(IOException.class, () -> docs.prepare(edit(uri, 1, 3, 1)));
        assertEquals(3, docs.prepare(edit(uri, 1, 1, 3)).getFirst().replacements().getFirst().end());
    }
    @Test void resourceOperationsAndMissingVersionsAreRefused() throws Exception {
        String uri = file("schema.mantra", "abc"); var docs = new VersionedDocuments(root); docs.open(uri, "abc");
        assertThrows(IOException.class, () -> docs.prepare(json.mapper.valueToTree(Map.of("documentChanges", List.of(Map.of("kind", "delete", "uri", uri))))));
        assertThrows(IOException.class, () -> docs.prepare(json.mapper.valueToTree(Map.of("changes", Map.of(uri, List.of())))));
    }
    @Test void symlinksCannotEscapeCanonicalWorkspace() throws Exception {
        Path outside = Files.createTempDirectory("mantra-ide-outside-");
        try {
            Path file = outside.resolve("private.mantra"); Files.writeString(file, "secret");
            Path alias = root.resolve("alias.mantra"); Files.createSymbolicLink(alias, file);
            var docs = new VersionedDocuments(root);
            assertThrows(IOException.class, () -> docs.open(alias.toUri().toString(), "secret"));
        } finally { Files.deleteIfExists(outside.resolve("private.mantra")); Files.deleteIfExists(outside); }
    }
    @Test void linePositionsRespectCrlfAndDocumentLimit() throws Exception {
        String uri = file("schema.mantra", "ab\r\nc"); var docs = new VersionedDocuments(root); docs.open(uri, "ab\r\nc");
        assertEquals(4, VersionedDocuments.offset("ab\r\nc", json.mapper.valueToTree(Map.of("line", 1, "character", 0))));
        assertThrows(IOException.class, () -> VersionedDocuments.offset("ab\r\nc", json.mapper.valueToTree(Map.of("line", 0, "character", 3))));
        assertThrows(IOException.class, () -> docs.change(uri, "x".repeat(65_537)));
        assertEquals(1, docs.get(uri).version());
    }
}
