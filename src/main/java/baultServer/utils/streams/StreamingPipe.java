package baultServer.utils.streams;

import baultServer.exceptions.UploaderDenialException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Puente en memoria entre un uploader (escribe en {@link #sink()}) y un
 * downloader (lee de {@link #source()}). Los bytes no se persisten en el
 * servidor: viajan a través de un buffer de tamaño fijo con backpressure
 * (si el buffer se llena, el escritor bloquea; si está vacío, el lector bloquea).
 *
 * Cada plaza (uploader/downloader) solo puede reclamarse una vez. El primer
 * llegante detecta con {@link #isUploaderClaimed()}/{@link #isDownloaderClaimed()}
 * si el peer ya está esperando; si no, avisa por WS y bloquea en
 * {@link #awaitRendezvous(long)}. Tras el rendezvous, {@link #tryClaimStateTransition()}
 * elige a UN solo endpoint (uploader o downloader) para ejecutar el
 * {@code PENDING → IN_PROGRESS} en BD.
 */
public final class StreamingPipe {

    public record Metadata(String filename, String contentType, Long size) {}

    private final PipedInputStream in;
    private final PipedOutputStream out;
    private final CompletableFuture<Metadata> metadata = new CompletableFuture<>();
    private final AtomicBoolean uploaderClaimed = new AtomicBoolean(false);
    private final AtomicBoolean downloaderClaimed = new AtomicBoolean(false);
    private final AtomicBoolean stateTransitioned = new AtomicBoolean(false);
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicBoolean fullyReleased = new AtomicBoolean(false);
    private final CountDownLatch rendezvous = new CountDownLatch(2);
    private final AtomicInteger releaseCount = new AtomicInteger();
    private final Runnable onFullyReleased;

    public StreamingPipe(int bufferSize, Runnable onFullyReleased) {
        try {
            this.in = new PipedInputStream(bufferSize);
            this.out = new PipedOutputStream(this.in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.onFullyReleased = onFullyReleased;
    }

    /** @return true si esta llamada consigue la plaza; false si ya estaba ocupada. */
    public boolean claimUploader() {
        boolean ok = uploaderClaimed.compareAndSet(false, true);
        if (ok) rendezvous.countDown();
        return ok;
    }

    public boolean claimDownloader() {
        boolean ok = downloaderClaimed.compareAndSet(false, true);
        if (ok) rendezvous.countDown();
        return ok;
    }

    public boolean isUploaderClaimed() { return uploaderClaimed.get(); }
    public boolean isDownloaderClaimed() { return downloaderClaimed.get(); }

    /**
     * Elige atomicamente al ejecutor de la transición PENDING → IN_PROGRESS.
     * Tras el rendezvous ambos endpoints están vivos; solo uno debe hacer el
     * UPDATE en BD para no depender de @Version en este paso.
     *
     * @return true si el caller es el elegido; false si el otro ya se hizo cargo.
     */
    public boolean tryClaimStateTransition() {
        return stateTransitioned.compareAndSet(false, true);
    }

    /** Espera a que ambos extremos hayan reclamado su plaza. */
    public boolean awaitRendezvous(long timeoutMs) throws InterruptedException {
        return rendezvous.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isAborted() { return aborted.get(); }

    public OutputStream sink() { return out; }
    public InputStream source() { return in; }

    public void publishMetadata(String filename, String contentType, Long size) {
        metadata.complete(new Metadata(
                filename == null || filename.isBlank() ? "file" : filename,
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
                size));
    }

    public Metadata awaitMetadata(long timeoutMs) throws TimeoutException, UploaderDenialException {
        try {
            return metadata.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UploaderDenialException denied) throw denied;
            throw new RuntimeException(cause);
        }
    }

    /**
     * Aborta el pipe: marca el flag {@code aborted}, completa {@code metadata} con excepción,
     * cierra streams y despierta a quien esté esperando en {@link #awaitRendezvous(long)}
     * contando el latch a cero. Idempotente. Además fuerza la eliminación del pipe del
     * registro para que un peer que llegue tarde reciba un pipe nuevo.
     */
    public void abort(Throwable cause) {
        if (!aborted.compareAndSet(false, true)) return;
        metadata.completeExceptionally(cause);
        try { in.close(); } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
        while (rendezvous.getCount() > 0) rendezvous.countDown();
        forceRelease();
    }

    /** El sender rechaza la transferencia. Notifica al downloader (via metadata) y cierra streams. */
    public void deny(UploaderDenialException.Code code, String message) {
        abort(new UploaderDenialException(code, message));
    }

    /**
     * Cada extremo llama al terminar. Cuando ambos lo hacen, el pipe se elimina del registro.
     * Si el pipe fue abortado, la eliminación se fuerza al abortarse, así que este release()
     * queda como no-op respecto al registro.
     */
    public void release() {
        if (releaseCount.incrementAndGet() >= 2) {
            forceRelease();
        }
    }

    /**
     * Elimina el pipe del registro de forma idempotente. Se usa desde {@link #abort(Throwable)}
     * y desde el path de timeout donde solo un lado llegó y el otro nunca se conectará.
     */
    public void forceRelease() {
        if (fullyReleased.compareAndSet(false, true)) {
            onFullyReleased.run();
        }
    }
}
