package com.xqiou.mantra.editor;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses an explicitly configured installed server; no IDE SDK, installation or server mock. */
class InstalledLspTest {
    private static final String SCHEMA = """
        (schema test/external {:version "1"}
          (input source-value :decimal)
          (line result "😀 Result" (+ source-value 2)))
        """;
    private static final String CASE = """
        (case external {:schema "test/external" :schema-version "1"}
          (inputs {:source-value 10}))
        """;

    @Test void installedServerUsesUtf16OverlaysVersionedRenameCancellationAndCleanExit() throws Exception {
        String configured = System.getProperty("mantra.test.lspCommand");
        assumeTrue(configured != null && !configured.isBlank(), "Supply -PlspCommand=/absolute/installed/mantra-lsp");
        Path executable = Path.of(configured).toRealPath();
        assertTrue(Files.isRegularFile(executable) && Files.isExecutable(executable), "Invalid configured launcher");
        Path temporary = Files.createTempDirectory("mantra-editor-installed-lsp-").toRealPath();
        Process process = null;
        StdioClient client = null;
        DeliveryGate delivery = null;
        Throwable failure = null;
        try {
            Path workspace = Files.createDirectory(temporary.resolve("workspace"));
            Path schema = workspace.resolve("schema.mantra");
            Path sample = workspace.resolve("case.mantra");
            Files.writeString(schema, SCHEMA);
            Files.writeString(sample, CASE);
            Files.createDirectory(temporary.resolve("java-tmp"));
            Files.createDirectory(temporary.resolve("preferences"));
            Files.createDirectory(temporary.resolve("cache"));
            var builder = new ProcessBuilder(List.of(executable.toString())).directory(workspace.toFile());
            builder.redirectError(temporary.resolve("stderr.log").toFile());
            builder.environment().put("TMPDIR", temporary.resolve("java-tmp").toString());
            builder.environment().put("XDG_CACHE_HOME", temporary.resolve("cache").toString());
            String previous = builder.environment().getOrDefault("JAVA_TOOL_OPTIONS", "");
            builder.environment().put("JAVA_TOOL_OPTIONS", previous + " " + javaOption("java.io.tmpdir", temporary.resolve("java-tmp"))
                + " " + javaOption("java.util.prefs.userRoot", temporary.resolve("preferences")));
            process = builder.start();
            delivery = new DeliveryGate(process.getInputStream());
            var sent = new RecordedOutput(process.getOutputStream());
            client = new StdioClient(delivery, sent, process, (method, params) -> { });
            JsonNode initialized = client.initialize(workspace).get(30, TimeUnit.SECONDS);
            assertEquals("utf-16", initialized.path("capabilities").path("positionEncoding").asText());
            assertTrue(client.ready());
            String schemaUri = schema.toUri().toString();
            String caseUri = sample.toUri().toString();
            var documents = new VersionedDocuments(workspace);
            documents.open(schemaUri, SCHEMA);
            documents.open(caseUri, CASE);
            open(client, schemaUri, SCHEMA);
            open(client, caseUri, CASE);
            int reference = SCHEMA.lastIndexOf("source-value");
            Map<String, Integer> position = VersionedDocuments.position(SCHEMA, reference);
            String linePrefix = SCHEMA.substring(SCHEMA.lastIndexOf('\n', reference) + 1, reference);
            assertEquals(linePrefix.codePointCount(0, linePrefix.length()) + 1, position.get("character"));
            JsonNode navigation = client.json(Map.of("textDocument", Map.of("uri", schemaUri), "position", position));
            JsonNode definition = client.request("textDocument/definition", navigation).get(30, TimeUnit.SECONDS);
            assertTrue(definition.isArray() && !definition.isEmpty(), definition.toString());
            assertEquals(schemaUri, definition.get(0).path("uri").asText());
            assertEquals(1, definition.get(0).path("range").path("start").path("line").asInt());
            assertFalse(client.request("textDocument/hover", navigation).get(30, TimeUnit.SECONDS).isNull());
            JsonNode firstRename = rename(client, schemaUri, SCHEMA);
            assertRename(documents, firstRename, schemaUri, caseUri, 1);
            var prepared = documents.prepare(firstRename);
            assertEquals(2, prepared.size());
            assertTrue(prepared.stream().allMatch(change -> change.updatedText().contains("supplied-value")));

            String changed = "; 😀 unsaved overlay\n" + SCHEMA.replace("source-value 2", "source-value 3");
            documents.change(schemaUri, changed);
            assertThrows(IOException.class, () -> documents.validate(prepared));
            assertThrows(IOException.class, () -> documents.prepare(firstRename));
            assertEquals(SCHEMA, Files.readString(schema), "Open overlays must not write source files");
            client.notify("textDocument/didChange", client.json(Map.of(
                "textDocument", Map.of("uri", schemaUri, "version", 2),
                "contentChanges", List.of(Map.of("text", changed)))));
            JsonNode nextRename = rename(client, schemaUri, changed);
            assertRename(documents, nextRename, schemaUri, caseUri, 2);
            var nextPrepared = documents.prepare(nextRename);
            assertTrue(nextPrepared.stream().filter(change -> change.snapshot().uri().equals(schemaUri))
                .anyMatch(change -> change.updatedText().contains("supplied-value 3")));

            // Delay delivery of real server bytes, never substitute a response. This makes the
            // bridge's local cancellation deterministic even if the server finishes immediately.
            // LSP permits a server to finish before cancellation reaches it; this test does not
            // claim that asynchronous compiler work was necessarily interrupted.
            delivery.pause();
            JsonNode changedNavigation = navigation(client, schemaUri, changed);
            var cancelled = client.request("textDocument/hover", changedNavigation);
            assertTrue(cancelled.cancel(false));
            assertThrows(CancellationException.class, cancelled::join);
            JsonNode cancellation = sent.messages().stream()
                .filter(message -> message.path("method").asText().equals("$/cancelRequest"))
                .findFirst().orElseThrow();
            long cancelledId = cancellation.path("params").path("id").asLong();
            assertTrue(sent.messages().stream().anyMatch(message -> message.path("id").asLong(-1) == cancelledId
                && message.path("method").asText().equals("textDocument/hover")));
            delivery.resume();
            assertFalse(client.request("textDocument/hover", changedNavigation).get(30, TimeUnit.SECONDS).isNull());
            for (String uri : List.of(schemaUri, caseUri)) {
                client.notify("textDocument/didClose", client.json(Map.of("textDocument", Map.of("uri", uri))));
                documents.close(uri);
            }
            client.close();
            assertFalse(client.ready());
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned language server did not exit");
            assertEquals(0, process.exitValue(), Files.readString(temporary.resolve("stderr.log")));
            List<JsonNode> messages = sent.messages();
            assertTrue(messages.stream().anyMatch(message -> message.path("method").asText().equals("shutdown")));
            assertEquals("exit", messages.getLast().path("method").asText());
        } catch (Throwable problem) {
            failure = problem;
            throw problem;
        } finally {
            boolean interrupted = Thread.interrupted();
            if (delivery != null) delivery.resume();
            if (client != null) client.close();
            try {
                stop(process);
                try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
                assertFalse(Files.exists(temporary), "Owned temporary workspace/log/cache remains");
            } catch (Throwable cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void open(StdioClient client, String uri, String text) {
        client.notify("textDocument/didOpen", client.json(Map.of("textDocument", Map.of(
            "uri", uri, "languageId", "mantra", "version", 1, "text", text))));
    }

    private static JsonNode navigation(StdioClient client, String uri, String text) {
        return client.json(Map.of("textDocument", Map.of("uri", uri),
            "position", VersionedDocuments.position(text, text.lastIndexOf("source-value"))));
    }

    private static JsonNode rename(StdioClient client, String uri, String text) throws Exception {
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) navigation(client, uri, text);
        params.put("newName", "supplied-value");
        return client.request("textDocument/rename", params).get(30, TimeUnit.SECONDS);
    }

    private static void assertRename(VersionedDocuments documents, JsonNode edit,
                                     String schemaUri, String caseUri, int schemaVersion) throws Exception {
        JsonNode changes = edit.path("documentChanges");
        assertTrue(changes.isArray());
        assertEquals(2, changes.size(), edit.toString());
        for (JsonNode change : changes) {
            String uri = change.path("textDocument").path("uri").asText();
            assertTrue(uri.equals(schemaUri) || uri.equals(caseUri));
            assertEquals(uri.equals(schemaUri) ? schemaVersion : 1,
                change.path("textDocument").path("version").asInt(-1));
        }
        documents.validate(documents.prepare(edit));
    }

    private static String javaOption(String name, Path value) {
        // JAVA_TOOL_OPTIONS supports quoted complete options; no shell executes this value.
        return "\"-D" + name + "=" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void stop(Process process) throws Exception {
        if (process == null) return;
        List<ProcessHandle> children = process.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        if (process.isAlive()) process.destroy();
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned server process remains alive");
        for (ProcessHandle child : children) {
            if (child.isAlive()) child.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(child.isAlive(), "Owned language server child remains alive");
        }
        process.getInputStream().close();
        process.getOutputStream().close();
        process.getErrorStream().close();
    }

    private static final class DeliveryGate extends FilterInputStream {
        private boolean paused;
        DeliveryGate(InputStream input) { super(input); }
        synchronized void pause() { paused = true; }
        synchronized void resume() { paused = false; notifyAll(); }
        private synchronized void deliver() throws InterruptedIOException {
            while (paused) try { wait(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Delivery gate interrupted");
            }
        }
        @Override public int read() throws IOException { int value = in.read(); deliver(); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length); deliver(); return count;
        }
        @Override public void close() throws IOException { resume(); super.close(); }
    }

    private static final class RecordedOutput extends FilterOutputStream {
        private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        RecordedOutput(OutputStream output) { super(output); }
        @Override public synchronized void write(int value) throws IOException {
            if (transcript.size() >= Frames.MAX_BODY) throw new IOException("Test transcript limit");
            out.write(value); transcript.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int count) throws IOException {
            if (transcript.size() + count > Frames.MAX_BODY) throw new IOException("Test transcript limit");
            out.write(bytes, offset, count); transcript.write(bytes, offset, count);
        }
        synchronized List<JsonNode> messages() throws IOException {
            var values = new ArrayList<JsonNode>();
            try (var frames = new Frames(new ByteArrayInputStream(transcript.toByteArray()), OutputStream.nullOutputStream())) {
                JsonNode next;
                while ((next = frames.read()) != null) values.add(next);
            }
            return values;
        }
    }
}
