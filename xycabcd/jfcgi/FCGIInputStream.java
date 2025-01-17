package xycabcd.jfcgi;

import java.io.IOException;
import java.io.InputStream;

/**
 * This stream manages buffered reads of FCGI messages.
 */
public class FCGIInputStream extends InputStream {

    /* Stream vars */
    private int rdNext;
    private int stop;
    private boolean isClosed;

    /* data vars */

    private byte[] buff;
    private int buffLen;
    private int buffStop;
    private int type;
    private int contentLen;
    private int paddingLen;
    private boolean skip;
    private boolean eorStop;
    private FCGIRequest request;

    private InputStream in;

    /**
     * Creates a new input stream to manage fcgi prototcol stuff
     *
     * @param inStream   the input stream
     * @param bufLen     length of buffer
     * @param streamType
     */
    FCGIInputStream(InputStream inStream, int bufLen, int streamType, FCGIRequest inReq) {

        in = inStream;
        buffLen = Math.min(bufLen, FCGIConstants.MAX_BUFFER_LENGTH);
        buff = new byte[buffLen];
        type = streamType;
        stop = 0;
        rdNext = 0;
        buffStop = 0;
        isClosed = false;
        contentLen = 0;
        paddingLen = 0;
        skip = false;
        eorStop = false;
        request = inReq;

    }

    /**
     * Reads a byte of data. This method will block if no input is available.
     *
     * @return the byte read, or -1 if the end of the stream is reached.
     * @throws IOException If an I/O error has occurred.
     */
    @Override
    public int read() throws IOException {
        if (rdNext != stop) {
            return buff[rdNext++] & 0xff;
        }
        if (isClosed) {
            return -1;
        }
        fill();
        if (rdNext != stop) {
            return buff[rdNext++] & 0xff;
        }
        return -1;
    }

    /**
     * Reads into an array of bytes. This method will block until some input is
     * available.
     *
     * @param b the buffer into which the data is read
     * @return the actual number of bytes read, -1 is returned when the end of
     *         the stream is reached.
     * @throws IOException If an I/O error has occurred.
     */
    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    /**
     * Reads into an array of bytes. Blocks until some input is available.
     *
     * @param b   the buffer into which the data is read
     * @param off the start offset of the data
     * @param len the maximum number of bytes read
     * @return the actual number of bytes read, -1 is returned when the end of
     *         the stream is reached.
     * @throws IOException If an I/O error has occurred.
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int m = 0;
        int bytesMoved = 0;

        if (len <= 0) {
            return 0;
        }
        /*
         * Fast path: len bytes already available.
         */

        if (len <= stop - rdNext) {
            System.arraycopy(buff, rdNext, b, off, len);
            rdNext += len;
            return len;
        }
        /*
         * General case: stream is closed or fill needs to be called
         */
        bytesMoved = 0;
        while (true) {
            if (rdNext != stop) {
                m = Math.min(len - bytesMoved, stop - rdNext);
                System.arraycopy(buff, rdNext, b, off, m);
                bytesMoved += m;
                rdNext += m;
                if (bytesMoved == len) {
                    return bytesMoved;
                }
                off += m;
            }
            if (isClosed) {
                return bytesMoved;
            }
            fill();

        }
    }

    void fill() throws IOException {
        byte[] headerBuf = new byte[FCGIConstants.BUFFER_HEADER_LENGTH];
        int headerLen = 0;
        int status = 0;
        int count = 0;
        while (true) {
            /*
             * If buffer is empty, do a read
             */
            if (rdNext == buffStop) {
                count = in.read(buff, 0, buffLen);
                if (count <= 0) {
                    setFCGIError(FCGIConstants.ERROR_PROTOCOL_ERROR);
                    return;
                }
                rdNext = 0;
                buffStop = count; // 1 more than we read
            }
            /*
             * Now buf is not empty: If the current record contains more content
             * bytes, deliver all that are present in buff to callers buffer
             * unless he asked for less than we have, in which case give him
             * less
             */
            if (contentLen > 0) {
                count = Math.min(contentLen, buffStop - rdNext);
                contentLen -= count;
                if (!skip) {
                    stop = rdNext + count;
                    return;
                }
                else {
                    rdNext += count;
                    if (contentLen > 0) {
                        continue;
                    }
                    else {
                        skip = false;
                    }
                }
            }
            /*
             * Content has been consumed by client. If record was padded, skip
             * over padding
             */
            if (paddingLen > 0) {
                count = Math.min(paddingLen, buffStop - rdNext);
                paddingLen -= count;
                rdNext += count;
                if (paddingLen > 0) {
                    continue; // more padding to read
                }
            }
            /*
             * All done with current record, including the padding. If we are in
             * a recursive call from Process Header, deliver EOF
             */
            if (eorStop) {
                stop = rdNext;
                isClosed = true;
                return;
            }
            /*
             * Fill header with bytes from input buffer - get the whole header.
             */
            count = Math.min(headerBuf.length - headerLen, buffStop - rdNext);
            count = Math.max(count, 0); // jr: why could it be negative ??
            System.arraycopy(buff, rdNext, headerBuf, headerLen, count);
            headerLen += count;
            rdNext += count;
            if (headerLen < headerBuf.length) {
                continue;
            }
            headerLen = 0;
            /*
             * Interperet the header. eorStop prevents ProcessHeader from
             * reading past the end of record when using stream to read content
             */
            eorStop = true;
            stop = rdNext;
            status = 0;
            status = new FCGIMessage(this).processHeader(headerBuf);
            eorStop = false;
            isClosed = false;
            switch (status) {
                case FCGIConstants.HEADER_STREAM_RECORD:
                    if (contentLen == 0) {
                        stop = rdNext;
                        isClosed = true;
                        return;
                    }
                    break;
                case FCGIConstants.HEADER_SKIP:
                    skip = true;
                    break;
                case FCGIConstants.HEADER_BEGIN_RECORD:
                /*
                 * If this header marked the beginning of a new request, return
                 * role info to caller
                 */
                    return;
                case FCGIConstants.HEADER_MANAGEMENT_RECORD:
                    break;
                default:
                /*
                 * ASSERT
                 */
                    setFCGIError(status);
                    return;

            }
        }
    }

    /**
     * Skips n bytes of input.
     *
     * @param n the number of bytes to be skipped
     * @return the actual number of bytes skipped.
     * @throws IOException If an I/O error has occurred.
     */
    @Override
    public long skip(long n) throws IOException {
        byte[] data = new byte[(int) n];
        return in.read(data);
    }

    void setFCGIError(int errnum) {
        request.errno = errnum;
        throw new FCGIException(errnum);
    }


    /*
     * Re-initializes the stream to read data of the specified type.
     */
    void setReaderType(int streamType) {

        type = streamType;
        eorStop = false;
        skip = false;
        contentLen = 0;
        paddingLen = 0;
        stop = rdNext;
        isClosed = false;
    }

    /*
     * Close the stream. This method does not really exist for
     * BufferedInputStream in java, but is implemented here for compatibility
     * with the FCGI structures being used. It doent really throw any
     * IOExceptions either, but that's there for compatiblity with the
     * InputStreamInterface.
     */
    @Override
    public void close() {
        isClosed = true;
        stop = rdNext;
    }

    /*
     * Returns the number of bytes that can be read without blocking.
     */
    @Override
    public int available() throws IOException {
        return stop - rdNext + in.available();
    }

    void setContentLen(final int contentLen) {
        this.contentLen = contentLen;
    }

    void setPaddingLen(final int paddingLen) {
        this.paddingLen = paddingLen;
    }

    public FCGIRequest getRequest() {
        return request;
    }

    public int getType() {
        return type;
    }

    public int getContentLen() {
        return contentLen;
    }
}
