package com.chatcrmlite.backend.utils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A bounded, counting InputStream that tracks total bytes read and enforces a strict maximum byte limit.
 * If reading exceeds maxAllowedBytes, an IOException is thrown to abort streaming and protect memory.
 */
public class BoundedCountingInputStream extends FilterInputStream {

    private final long maxAllowedBytes;
    private long bytesRead = 0;

    public BoundedCountingInputStream(InputStream in, long maxAllowedBytes) {
        super(in);
        if (maxAllowedBytes <= 0) {
            throw new IllegalArgumentException("maxAllowedBytes must be greater than zero");
        }
        this.maxAllowedBytes = maxAllowedBytes;
    }

    @Override
    public int read() throws IOException {
        int result = super.read();
        if (result != -1) {
            bytesRead++;
            checkLimit();
        }
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int result = super.read(b, off, len);
        if (result != -1) {
            bytesRead += result;
            checkLimit();
        }
        return result;
    }

    @Override
    public long skip(long n) throws IOException {
        long result = super.skip(n);
        if (result > 0) {
            bytesRead += result;
            checkLimit();
        }
        return result;
    }

    private void checkLimit() throws IOException {
        if (bytesRead > maxAllowedBytes) {
            throw new IOException(String.format(
                    "Media stream exceeded configured maximum size limit: read %d bytes, limit is %d bytes",
                    bytesRead, maxAllowedBytes
            ));
        }
    }

    public long getBytesRead() {
        return bytesRead;
    }

    public long getMaxAllowedBytes() {
        return maxAllowedBytes;
    }
}
