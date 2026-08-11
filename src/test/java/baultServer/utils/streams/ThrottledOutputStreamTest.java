package baultServer.utils.streams;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottledOutputStreamTest {

    @Test
    void noThrottlingWhenLimitIsZero() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink, 0);
        byte[] payload = new byte[1_000_000];

        long start = System.nanoTime();
        out.write(payload);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // Sin throttling escribir 1 MB en un ByteArrayOutputStream debe ser
        // casi instantáneo. Damos margen amplio para entornos lentos de CI.
        assertTrue(elapsedMs < 500, "Expected no throttling, elapsedMs=" + elapsedMs);
        assertArrayEquals(payload, sink.toByteArray());
    }

    @Test
    void noThrottlingWhenLimitIsNegative() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink, -1);
        byte[] payload = new byte[1_000_000];

        long start = System.nanoTime();
        out.write(payload);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < 500, "Expected no throttling, elapsedMs=" + elapsedMs);
    }

    @Test
    void averageRateStaysAtOrBelowLimitForBulkWrites() throws IOException {
        // 100 KB/s: escribir 50 KB debe llevar >= 500 ms.
        long limit = 100_000L;
        int total = 50_000;
        int chunkSize = 5_000;

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink, limit);
        byte[] chunk = new byte[chunkSize];

        long start = System.nanoTime();
        for (int written = 0; written < total; written += chunkSize) {
            out.write(chunk);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        long minExpectedMs = (total * 1000L) / limit; // 500 ms
        // Toleramos algo de sobrecarga del scheduler; el mínimo teórico no debe
        // ser superado por debajo (es un límite duro por la aritmética de throttle()).
        assertTrue(
                elapsedMs >= minExpectedMs - 20,
                "Expected elapsed >= " + minExpectedMs + " ms, was " + elapsedMs);

        // La tasa efectiva no debe exceder claramente el límite. Margen generoso
        // para evitar flakiness en máquinas cargadas.
        double bytesPerSec = total / (elapsedMs / 1000.0);
        assertTrue(
                bytesPerSec <= limit * 1.5,
                "Effective rate " + bytesPerSec + " B/s exceeds limit " + limit + " B/s by too much");
    }

    @Test
    void averageRateStaysAtOrBelowLimitForSingleByteWrites() throws IOException {
        // 10 000 B/s: escribir 2 000 bytes debe llevar >= 200 ms.
        long limit = 10_000L;
        int total = 2_000;

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink, limit);

        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            out.write(0);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        long minExpectedMs = (total * 1000L) / limit; // 200 ms
        assertTrue(
                elapsedMs >= minExpectedMs - 20,
                "Expected elapsed >= " + minExpectedMs + " ms, was " + elapsedMs);

        double bytesPerSec = total / (elapsedMs / 1000.0);
        assertTrue(
                bytesPerSec <= limit * 1.5,
                "Effective rate " + bytesPerSec + " B/s exceeds limit " + limit + " B/s by too much");
    }

    @Test
    void writesAreDeliveredToDelegate() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        // Límite muy alto para no ralentizar el test pero manteniendo throttling activo.
        ThrottledOutputStream out = new ThrottledOutputStream(sink, 100_000_000L);
        byte[] payload = new byte[] {10, 20, 30, 40, 50, 60};

        out.write(payload, 1, 4);
        out.write(99);

        assertArrayEquals(new byte[] {20, 30, 40, 50, 99}, sink.toByteArray());
    }

    @Test
    void doesNotDelayBelowLimit() throws IOException {
        // Escribimos muy pocos bytes bajo un límite alto: el throttle no debe dormir.
        long limit = 1_000_000L; // 1 MB/s
        int total = 100;

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ThrottledOutputStream out = new ThrottledOutputStream(sink, limit);
        byte[] payload = new byte[total];

        long start = System.nanoTime();
        out.write(payload);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // El mínimo teórico es 0.1 ms; debe completar en decenas de ms como mucho.
        assertTrue(elapsedMs < 100, "Expected near-instant write, elapsedMs=" + elapsedMs);
    }
}
