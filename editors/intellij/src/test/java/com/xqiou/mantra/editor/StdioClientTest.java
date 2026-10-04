package com.xqiou.mantra.editor;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StdioClientTest {
    @TempDir Path root;
    static final class Peer implements AutoCloseable {
        final StdioClient client;
        final Frames server;
        final Thread worker;
        final BlockingQueue<String> methods = new LinkedBlockingQueue<>();
        Peer() throws IOException {
            PipedInputStream incoming = new PipedInputStream(65536), outgoing = new PipedInputStream(65536);
            server = new Frames(outgoing, new PipedOutputStream(incoming));
            client = new StdioClient(incoming, new PipedOutputStream(outgoing), null, (method, params) -> {});
            worker = new Thread(() -> { try {
                JsonNode message;
                while ((message = server.read()) != null) {
                    String method = message.path("method").asText(); methods.add(method);
                    if ("exit".equals(method)) break;
                    if (message.has("id") && !"slow".equals(method)) {
                        var reply = server.mapper.createObjectNode().put("jsonrpc", "2.0"); reply.set("id", message.get("id"));
                        reply.set("result", "initialize".equals(method) ? server.mapper.valueToTree(Map.of("capabilities", Map.of("positionEncoding", "utf-16")))
                            : "shutdown".equals(method) ? server.mapper.getNodeFactory().nullNode() : message.path("params")); server.write(reply);
                    }
                }
            } catch (IOException ignored) { } }, "mantra-test-peer"); worker.setDaemon(true); worker.start();
        }
        @Override public void close() throws Exception { client.close(); server.close(); worker.join(2000); assertFalse(worker.isAlive()); }
    }
    @Test void realFramingHandshakeRequestShutdownAndExitAreOrdered() throws Exception {
        try (Peer peer = new Peer()) {
            peer.client.initialize(root).get(2, TimeUnit.SECONDS); assertTrue(peer.client.ready());
            assertEquals("税😀", peer.client.request("echo", peer.client.json(Map.of("value", "税😀"))).get(2, TimeUnit.SECONDS).path("value").asText());
            peer.client.close();
            assertEquals("initialize", peer.methods.poll(2, TimeUnit.SECONDS));
            assertEquals("initialized", peer.methods.poll(2, TimeUnit.SECONDS));
            assertEquals("echo", peer.methods.poll(2, TimeUnit.SECONDS));
            assertEquals("shutdown", peer.methods.poll(2, TimeUnit.SECONDS));
            assertEquals("exit", peer.methods.poll(2, TimeUnit.SECONDS));
            assertTrue(peer.client.request("after-close", peer.client.json(Map.of())).isCompletedExceptionally());
        }
    }
    @Test void cancellationIsSentForTheActualPendingRequest() throws Exception {
        try (Peer peer = new Peer()) {
            peer.client.initialize(root).get(2, TimeUnit.SECONDS);
            var future = peer.client.request("slow", peer.client.json(Map.of())); assertTrue(future.cancel(true));
            String method; do { method = peer.methods.poll(2, TimeUnit.SECONDS); assertNotNull(method); } while (!"$/cancelRequest".equals(method));
            assertTrue(future.isCancelled());
        }
    }
    @Test void requestBoundIsExactAndCloseCompletesUnansweredRequests() throws Exception {
        try (Peer peer = new Peer()) {
            var requests = new java.util.ArrayList<CompletableFuture<JsonNode>>();
            for (int i = 0; i < 128; i++) requests.add(peer.client.request("slow", peer.client.json(Map.of())));
            assertTrue(peer.client.request("slow", peer.client.json(Map.of())).isCompletedExceptionally());
            peer.client.close(); assertTrue(requests.stream().allMatch(CompletableFuture::isCompletedExceptionally));
        }
    }
}
