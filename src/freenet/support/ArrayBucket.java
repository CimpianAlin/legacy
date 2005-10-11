package freenet.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * A bucket that stores data in the memory.
 * 
 * @author oskar
 */
public class ArrayBucket implements Bucket {

	private ArrayList data;
	private boolean reset;
	private String name;

	public ArrayBucket() {
		this("ArrayBucket");
	}

	public ArrayBucket(byte[] initdata) {
		this("ArrayBucket");
		data.add(initdata);
	}

	public ArrayBucket(String name) {
		data = new ArrayList();
		this.name = name;
	}

	public OutputStream getOutputStream() {
		return new ArrayBucketOutputStream(reset);
	}

	public InputStream getInputStream() {
		return new ArrayBucketInputStream();
	}

	public String toString() {
		StringBuffer s = new StringBuffer(250);
		for (Iterator i = data.iterator(); i.hasNext();) {
			byte[] b = (byte[]) i.next();
			s.append(new String(b));
		}
		return new String(s);
	}

	public void read(InputStream in) throws IOException {
		OutputStream out = new ArrayBucketOutputStream(reset);
		int i;
		byte[] b = new byte[8 * 1024];
		while ((i = in.read(b)) != -1) {
			out.write(b, 0, i);
		}
		out.close();
	}

	public long size() {
		long size = 0;
		for (Iterator i = data.iterator(); i.hasNext();) {
			byte[] b = (byte[]) i.next();
			size += b.length;
		}
		return size;
	}

	public String getName() {
		return name;
	}

	public void resetWrite() {
		reset = true;
	}

	private class ArrayBucketOutputStream extends ByteArrayOutputStream {
		
		private boolean reset;
		
		public ArrayBucketOutputStream(boolean reset) {
			super();
			this.reset = reset;
		}

		public void close() {
			if (reset) {
				data.clear();
				data.trimToSize();
			}
			reset = false;
			data.add(toByteArray());
		}
	}

	private class ArrayBucketInputStream extends InputStream {
		
		private Iterator i;
		private ByteArrayInputStream in;

		public ArrayBucketInputStream() {
			i = data.iterator();
		}

		public int read() {
			return priv_read();
		}

		private int priv_read() {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream((byte[]) i.next());
				} else {
					return -1;
				}
			}
			int i = in.read();
			if (i == -1) {
				in = null;
				return priv_read();
			} else {
				return i;
			}
		}

		public int read(byte[] b) {
			return priv_read(b, 0, b.length);
		}

		public int read(byte[] b, int off, int len) {
			return priv_read(b, off, len);
		}

		private int priv_read(byte[] b, int off, int len) {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream((byte[]) i.next());
				} else {
					return -1;
				}
			}
			int i = in.read(b, off, len);
			if (i == -1) {
				in = null;
				return priv_read(b, off, len);
			} else {
				return i;
			}
		}

		public int available() {
			if (in == null) {
				if (i.hasNext()) {
					in = new ByteArrayInputStream((byte[]) i.next());
				} else {
					return 0;
				}
			}
			return in.available();
		}

	}
}
