package dev.jdesk.runtime.assets;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Caps an underlying stream at {@code limit} bytes, for Range slices. */
final class LimitedInputStream extends FilterInputStream {
    private long remaining;

    LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = in.read();
        if (b >= 0) {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int n = in.read(buffer, off, (int) Math.min(len, remaining));
        if (n > 0) {
            remaining -= n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = in.skip(Math.min(n, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), remaining);
    }
}
