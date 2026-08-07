package baultServer.utils.streams;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Envuelve un OutputStream y lleva la cuenta de bytes escritos con éxito. */
public class CountingOutputStream extends FilterOutputStream {

    private long count;

    public CountingOutputStream(OutputStream delegate) {
        super(delegate);
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        count += len;
    }

    public long getCount() {
        return count;
    }
}
