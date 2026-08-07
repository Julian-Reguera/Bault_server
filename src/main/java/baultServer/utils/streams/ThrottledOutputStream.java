package baultServer.utils.streams;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Envuelve un OutputStream y limita la tasa media de escritura a
 * {@code maxBytesPerSecond}. Duerme el hilo actual cuando va por delante
 * del ritmo objetivo. Si el límite es <= 0, no aplica throttling.
 */
public class ThrottledOutputStream extends FilterOutputStream {

    private final long maxBytesPerSecond;
    private final long startNanos;
    private long bytesWritten;

    public ThrottledOutputStream(OutputStream delegate, long maxBytesPerSecond) {
        super(delegate);
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.startNanos = System.nanoTime();
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        bytesWritten++;
        throttle();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        bytesWritten += len;
        throttle();
    }

    private void throttle() {
        if (maxBytesPerSecond <= 0) return;
        long expectedNanos = bytesWritten * 1_000_000_000L / maxBytesPerSecond;
        long actualNanos = System.nanoTime() - startNanos;
        long sleepNanos = expectedNanos - actualNanos;
        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
