package baultServer.utils.streams;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CountingOutputStreamTest {

    @Test
    void initialCountIsZero() {
        CountingOutputStream out = new CountingOutputStream(new ByteArrayOutputStream());
        assertEquals(0L, out.getCount());
    }

    @Test
    void countsSingleByteWrites() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountingOutputStream out = new CountingOutputStream(sink);

        for (int i = 0; i < 7; i++) {
            out.write(i);
        }

        assertEquals(7L, out.getCount());
        assertEquals(7, sink.size());
    }

    @Test
    void countsBulkWrites() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountingOutputStream out = new CountingOutputStream(sink);
        byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);

        out.write(payload);

        assertEquals(payload.length, out.getCount());
        assertArrayEquals(payload, sink.toByteArray());
    }

    @Test
    void countsBulkWritesWithOffsetAndLength() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountingOutputStream out = new CountingOutputStream(sink);
        byte[] payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        out.write(payload, 2, 5);

        assertEquals(5L, out.getCount());
        assertArrayEquals(new byte[] {3, 4, 5, 6, 7}, sink.toByteArray());
    }

    @Test
    void countAccumulatesAcrossMultipleWrites() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountingOutputStream out = new CountingOutputStream(sink);

        out.write(new byte[] {1, 2, 3});
        out.write(9);
        out.write(new byte[] {4, 5, 6, 7, 8}, 1, 3);

        assertEquals(3L + 1L + 3L, out.getCount());
        assertArrayEquals(new byte[] {1, 2, 3, 9, 5, 6, 7}, sink.toByteArray());
    }

    @Test
    void countsLargePayload() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountingOutputStream out = new CountingOutputStream(sink);
        byte[] chunk = new byte[4096];

        for (int i = 0; i < 256; i++) {
            out.write(chunk);
        }

        long expected = 256L * 4096L;
        assertEquals(expected, out.getCount());
        assertEquals(expected, sink.size());
    }
}
