package com.xqiou.mantra.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;

/** One project-owned stdio connection. No automatic execution, install, or restart. */
public final class StdioClient implements AutoCloseable {
    private final Frames frames;
    private final Process process;
    private final BiConsumer<String, JsonNode> notifications;
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> daemon(r, "mantra-editor-stdio"));
    private volatile boolean ready;
    public StdioClient(InputStream input, OutputStream output, Process process, BiConsumer<String, JsonNode> notifications) {
        this.frames = new Frames(input, output); this.process = process; this.notifications = notifications;
        reader.execute(this::receive);
    }
    public static StdioClient launch(List<String> argv, Path root, BiConsumer<String, JsonNode> notifications) throws IOException {
        if (argv.isEmpty() || argv.size() > 64 || argv.stream().anyMatch(s -> s == null || s.contains("\0"))) {
            throw new IllegalArgumentException("Invalid executable argument vector");
        }
        Process process = new ProcessBuilder(List.copyOf(argv)).directory(root.toRealPath().toFile()).start();
        // Bounded memory: discard stderr bytes rather than mixing log output into protocol stdout.
        Thread stderr = daemon(() -> { try (InputStream stream = process.getErrorStream()) {
            byte[] buffer = new byte[4096]; while (stream.read(buffer) >= 0) { }
        } catch (IOException ignored) { } }, "mantra-editor-stderr");
        stderr.start();
        return new StdioClient(process.getInputStream(), process.getOutputStream(), process, notifications);
    }
    public CompletableFuture<JsonNode> initialize(Path root) {
        ObjectNode params = frames.mapper.createObjectNode();
        params.put("processId", ProcessHandle.current().pid()); params.put("rootUri", root.toUri().toString());
        params.putArray("workspaceFolders").addObject().put("uri", root.toUri().toString()).put("name", (root.getFileName() == null ? root.toString() : root.getFileName().toString()));
        params.putObject("capabilities").putObject("general").putArray("positionEncodings").add("utf-16");
        params.with("capabilities").putObject("workspace").put("applyEdit", false)
            .putObject("workspaceEdit").put("documentChanges", true);
        return request("initialize", params).thenApply(result -> {
            if (!"utf-16".equals(result.path("capabilities").path("positionEncoding").asText("utf-16"))) {
                throw new CompletionException(new IOException("Unsupported position encoding"));
            }
            notify("initialized", frames.mapper.createObjectNode()); ready = true; return result;
        });
    }
    public JsonNode json(Object value) { return frames.mapper.valueToTree(value); }
    public boolean ready() { return ready && !closing.get(); }
    public synchronized CompletableFuture<JsonNode> request(String method, JsonNode params) {
        if (closing.get() || pending.size() >= 128) return CompletableFuture.failedFuture(new IOException("Connection closed or request limit reached"));
        long id = sequence.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        future.whenComplete((result, failure) -> {
            if (future.isCancelled() && pending.remove(id, future)) notify("$/cancelRequest", json(Map.of("id", id)));
        });
        ObjectNode message = frames.mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        message.set("params", params);
        try { frames.write(message); }
        catch (IOException failure) { pending.remove(id); future.completeExceptionally(failure); }
        return future;
    }
    public void notify(String method, JsonNode params) {
        ObjectNode message = frames.mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        message.set("params", params);
        try { frames.write(message); }
        catch (IOException failure) { fail(failure); }
    }
    private void receive() {
        try {
            JsonNode message;
            while ((message = frames.read()) != null) {
                if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) throw new IOException("Invalid JSON-RPC response");
                if (message.has("method")) {
                    if (message.has("id")) {
                        ObjectNode refused = frames.mapper.createObjectNode().put("jsonrpc", "2.0");
                        refused.set("id", message.get("id"));
                        refused.putObject("error").put("code", -32601).put("message", "Unsupported client request");
                        frames.write(refused);
                    } else notifications.accept(message.path("method").asText(), message.path("params"));
                } else {
                    if (!message.path("id").isIntegralNumber() || !message.path("id").canConvertToLong()) throw new IOException("Invalid response ID");
                    long id = message.path("id").asLong();
                    CompletableFuture<JsonNode> future = pending.remove(id);
                    if (future != null) {
                        if (message.has("error")) future.completeExceptionally(new IOException(message.get("error").toString()));
                        else if (message.has("result")) future.complete(message.get("result"));
                        else future.completeExceptionally(new IOException("Response has no result or error"));
                    }
                }
            }
            fail(new EOFException("Language server exited"));
        } catch (Exception failure) { fail(failure); }
    }
    private void fail(Throwable failure) {
        ready = false;
        for (var entry : pending.entrySet()) if (pending.remove(entry.getKey(), entry.getValue())) entry.getValue().completeExceptionally(failure);
    }
    @Override public synchronized void close() {
        if (closing.get()) return;
        // Finish protocol before closing input or terminating this owned child. Never touch another process.
        if (ready) {
            try { request("shutdown", frames.mapper.createObjectNode()).get(2, TimeUnit.SECONDS); }
            catch (Exception ignored) { }
            notify("exit", frames.mapper.createObjectNode());
        }
        if (!closing.compareAndSet(false, true)) return;
        ready = false; fail(new IOException("Language client closed"));
        if (process != null) {
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy(); if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
            }} catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            try { process.getOutputStream().close(); process.getInputStream().close(); process.getErrorStream().close(); }
            catch (IOException ignored) { }
        }
        try { frames.close(); } catch (IOException ignored) { }
        reader.shutdownNow();
        try { reader.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static Thread daemon(Runnable work, String name) { Thread thread = new Thread(work, name); thread.setDaemon(true); return thread; }
}
