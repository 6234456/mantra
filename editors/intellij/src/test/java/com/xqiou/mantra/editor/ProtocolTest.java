package com.xqiou.mantra.editor;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void utf8ByteLengthRoundTripsUnicodeAndMultipleFrames() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Frames writer = new Frames(InputStream.nullInputStream(), output);
        JsonNode message = writer.mapper.valueToTree(Map.of("jsonrpc", "2.0", "result", "税😀"));
        writer.write(message); writer.write(message);
        Frames reader = new Frames(new ByteArrayInputStream(output.toByteArray()), OutputStream.nullOutputStream());
        assertEquals(message, reader.read()); assertEquals(message, reader.read()); assertNull(reader.read());
    }
    @Test void headerBodyAndDuplicateJsonLimitsAreRejected() {
        for (String frame : new String[] {
            "Content-Length: 1\r\nContent-Length: 1\r\n\r\n0",
            "Content-Length: 1048577\r\n\r\n",
            "Content-Length: 4\r\n\r\n{}",
            "Content-Length: 13\r\n\r\n{\"x\":1,\"x\":2}",
            "Content-Length: 4\r\n\r\n{}{}"
        }) assertThrows(IOException.class, () -> new Frames(new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8)),
            OutputStream.nullOutputStream()).read(), frame);
    }
    @Test void incompleteHeaderIsNotTreatedAsCleanEof() {
        assertThrows(EOFException.class, () -> new Frames(new ByteArrayInputStream("Content-Length: 2\r\n".getBytes(StandardCharsets.US_ASCII)),
            OutputStream.nullOutputStream()).read());
    }
}
