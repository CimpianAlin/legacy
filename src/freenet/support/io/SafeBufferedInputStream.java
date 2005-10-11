/*
 * Java core library component.
 *
 * Copyright (c) 1997, 1998
 *      Transvirtual Technologies, Inc.  All rights reserved.
 *
 * See the file "COPYING" for information on usage and redistribution
 * of this file.
 */

/**
 * A buffering inputstream derived from Kaffe's implementation, added to 
 * Freenet because a lot of JVMs (Sun's and MS 1.1) have buggy implementation 
 * (specifically they loose the pointer on exceptions).
 *
 * oskar
 */

package freenet.support.io;
import java.io.*;

public class SafeBufferedInputStream extends FilterInputStream {

    protected byte[] buf;
    protected int count;
    protected int pos;
    protected int markpos;
    protected int marklimit;
    private byte[] single = new byte[1];
    final private static int DEFAULTBUFFER = 2048;

    /*
     * Invariant:
     *
     *   markpos <= pos <= count <= buf.length >= marklimit
     */

    public SafeBufferedInputStream(InputStream in) {
	this(in, DEFAULTBUFFER);
    }

    public SafeBufferedInputStream(InputStream in, int size) {
	super(in);
	buf = new byte[size];
	pos = count = 0;
	marklimit = size;
	markpos = -1;
    }

    public synchronized int available() throws IOException {
	return (count - pos) + in.available();
    }

    public synchronized void mark(int marklimit) {
	if (marklimit > buf.length - pos) {		// not enough room
            byte[] newbuf;

            if (marklimit <= buf.length) {
                newbuf = buf;			// just shift buffer
            } else {
                newbuf = new byte[marklimit];	// need a new buffer
            }
            System.arraycopy(buf, pos, newbuf, 0, count - pos);
            buf = newbuf;
            count -= pos;
            pos = markpos = 0;
	} else {
            markpos = pos;
	}
	this.marklimit = marklimit;
    }

    public boolean markSupported() {
	return true;
    }

    public synchronized int read() throws IOException {
	if (read(single, 0, 1) == -1) {
            return (-1);
        } else {
            return (single[0] & 0xFF);
	}
    }

    public synchronized int read(byte b[], int off, int len) throws IOException {
	/* Common case short-cut */
	if (len == 1 && pos < count) {
            b[off] = buf[pos++];
            return (1);
	}

	int total = 0;
	while (len > 0) {

            // If buffer fully consumed, invalidate mark & reset buffer
            if (pos == buf.length) {
                pos = count = 0;
                markpos = -1;
            }

            // Buffer empty?
            int nread;
            if (pos == count) {

                // If the amount requested is more than the size
                // of the buffer, we might as well optimize with
                // a direct read to avoid needless copying of data.
                if (len >= buf.length) {
                    if ((nread = super.read(b, off, len)) == -1) {
                        return (total > 0) ? total : -1;
                    }
                    return total + nread;
                }

                // Read another buffer's worth of data
                if (!fillBuffer()) {
                    return (total > 0) ? total : -1;
                }
            }

            // Copy the next chunk of bytes from our buffer
            nread = count - pos;
            if (nread > len) {
                nread = len;
            }
            System.arraycopy(buf, pos, b, off, nread);
            total += nread;
            pos += nread;
            off += nread;
            len -= nread;
	}
	return total;
    }

    public synchronized void reset() throws IOException {
	if (markpos == -1) {
            throw new IOException(
                                  "Attempt to reset when no mark is valid"
                                  + " (marklimit=" + marklimit + ")");
	}
	pos = markpos;
    }

    /*
     * This version of skip() does not invalidate a mark if less
     * than marklimit total bytes are read and/or skipped.
     * Not sure if this is actually a requirement or not.
     */
    public synchronized long skip(long n) throws IOException {

	// Sanity check
	if (n <= 0) {
            return 0;
	}

	// Skip buffered data if there is any
	if (pos < count) {
            if (count - pos > n) {
                pos += (int)n;		// narrowing cast OK
            } else {
                n = count - pos;
                pos = count;
            }
            return n;
	}

	// If buffer fully consumed, invalidate mark & reset buffer
	if (pos == buf.length) {
            pos = count = 0;
            markpos = -1;
            return super.skip(n);
	}

	// Read data into buffer and try again
	return fillBuffer() ? skip(n) : 0;
    }

    /*
     * Get more buffered data. This should only be called when:
     *
     *	1 pos == count
     *	2 count < buf.length
     *
     * Returns true if at least one byte was read.
     */
    private boolean fillBuffer() throws IOException {
	int nread;

	if ((nread = super.read(buf, pos, buf.length - pos)) <= 0) {
            return false;
	}
	count += nread;
	return true;
    }
}








