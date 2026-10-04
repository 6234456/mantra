package com.xqiou.mantra.editor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** LSP framing, not a language parser. All limits match the installed Mantra server. */
public final class Frames implements AutoCloseable {
    public static final int MAX_BODY = 1_048_576;
    public final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_BODY).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final InputStream input;
    private final OutputStream output;
    public Frames(InputStream input, OutputStream output) { this.input = input; this.output = output; }
    public JsonNode read() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) >= 0) {
            header.write(next);
            if (header.size() > 8192) throw new IOException("LSP header limit exceeded");
            byte[] bytes = header.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n-4] == '\r' && bytes[n-3] == '\n' && bytes[n-2] == '\r' && bytes[n-1] == '\n') break;
        }
        if (next < 0) {
            if (header.size() == 0) return null;
            throw new EOFException("Incomplete LSP header");
        }
        int length = -1;
        for (String line : header.toString(StandardCharsets.US_ASCII).split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 1) throw new IOException("Invalid LSP header");
            if (line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                if (length >= 0) throw new IOException("Duplicate Content-Length");
                try { length = Integer.parseInt(line.substring(colon + 1).trim()); }
                catch (NumberFormatException invalid) { throw new IOException("Invalid Content-Length", invalid); }
            }
        }
        if (length < 0 || length > MAX_BODY) throw new IOException("LSP body limit exceeded");
        byte[] body = input.readNBytes(length);
        if (body.length != length) throw new EOFException("Incomplete LSP body");
        try (var parser = mapper.createParser(body)) {
            JsonNode result = mapper.readTree(parser);
            if (result == null || parser.nextToken() != null) throw new IOException("Invalid JSON-RPC body");
            return result;
        }
    }
    @Override public void close() throws IOException { try { input.close(); } finally { output.close(); } }
    public synchronized void write(JsonNode message) throws IOException {
        byte[] body = mapper.writeValueAsBytes(message);
        if (body.length > MAX_BODY) throw new IOException("LSP body limit exceeded");
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }
}
