package freenet.support;
import freenet.support.io.*;
import java.io.*;

public class NullBucket implements Bucket {

    public final OutputStream nullOut = new NullOutputStream();
    public final InputStream  nullIn  = new NullInputStream();

    public final long length;
    
    public NullBucket() {
        this(0);
    }

    public NullBucket(long length) {
        this.length = length;
    }
    
    /**
     * Returns an OutputStream that is used to put data in this Bucket.
     **/
    public OutputStream getOutputStream() { return nullOut; }

    /**
     * Returns an InputStream that reads data from this Bucket. If there is
     * no data in this bucket, null is returned.
     **/
    public InputStream getInputStream() { return nullIn; }

    /**
     * If resetWrite() is called on the object, the next getOutputStream
     * should overwrite any other data in the bucket from the beginning,
     * otherwise it should append it.
     **/
    public void resetWrite() {}

    /**
     * Returns the amount of data currently in this bucket.
     **/
    public long size() {
        return length;
    }

    /** Returns the name of this NullBucket. */
    public String getName() {
	return "President George W. NullBucket";
    }
}

