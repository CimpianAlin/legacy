package freenet.support;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import freenet.Core;

/**
 * Helper functions for working with Buckets.
 */
public class BucketTools {

	/**
	 * Copy from the input stream of <code>src</code> to the output stream of
	 * <code>dest</code>.
	 * 
	 * @param src
	 * @param dst
	 * @throws IOException
	 */
	public final static void copy(Bucket src, Bucket dst) throws IOException {
		OutputStream out = dst.getOutputStream();
		InputStream in = src.getInputStream();
		ReadableByteChannel readChannel = Channels.newChannel(in);
		WritableByteChannel writeChannel = Channels.newChannel(out);

		ByteBuffer buffer = ByteBuffer.allocateDirect(Core.blockSize);
		while (readChannel.read(buffer) != -1) {
			buffer.flip();
			writeChannel.write(buffer);
			buffer.clear();
		}

		writeChannel.close();
		readChannel.close();
		out.close();
		in.close();
	}

	public final static void zeroPad(Bucket b, long size) throws IOException {
		OutputStream out = b.getOutputStream();

		// Initialized to zero by default.
		byte[] buffer = new byte[16384];

		long count = 0;
		while (count < size) {
			long nRequired = buffer.length;
			if (nRequired > size - count) {
				nRequired = size - count;
			}
			out.write(buffer, 0, (int) nRequired);
			count += nRequired;
		}

		out.close();
	}

	public final static void paddedCopy(
		Bucket from,
		Bucket to,
		long nBytes,
		int blockSize)
		throws IOException {

		if (nBytes > blockSize) {
			throw new IllegalArgumentException("nBytes > blockSize");
		}

		OutputStream out = to.getOutputStream();
		byte[] buffer = new byte[16384];
		InputStream in = from.getInputStream();

		long count = 0;
		while (count != nBytes) {
			long nRequired = nBytes - count;
			if (nRequired > buffer.length) {
				nRequired = buffer.length;
			}
			long nRead = in.read(buffer, 0, (int) nRequired);
			if (nRead == -1) {
				throw new IOException("Not enough data in source bucket.");
			}
			out.write(buffer, 0, (int) nRead);
			count += nRead;
		}

		if (count < blockSize) {
			// hmmm... better to just allocate a new buffer
			// instead of explicitly zeroing the old one?
			// Zero pad to blockSize
			long padLength = buffer.length;
			if (padLength > blockSize - nBytes) {
				padLength = blockSize - nBytes;
			}
			for (int i = 0; i < padLength; i++) {
				buffer[i] = 0;
			}

			while (count != blockSize) {
				long nRequired = blockSize - count;
				if (blockSize - count > buffer.length) {
					nRequired = buffer.length;
				}
				out.write(buffer, 0, (int) nRequired);
				count += nRequired;
			}
		}
		in.close();
		out.close();
	}

	public static class BucketFactoryWrapper implements BucketFactory {
		public BucketFactoryWrapper(BucketFactory bf) {
			BucketFactoryWrapper.this.bf = bf;
		}
		public Bucket makeBucket(long size) throws IOException {
			return bf.makeBucket(size);
		}

		public void freeBucket(Bucket b) throws IOException {
			if (b instanceof RandomAccessFileBucket) {
				((RandomAccessFileBucket) b).release();
				return;
			}
			bf.freeBucket(b);
		}
		private BucketFactory bf = null;
	}

	public static Bucket[] makeBuckets(BucketFactory bf, int count, int size)
		throws IOException {
		Bucket[] ret = new Bucket[count];
		for (int i = 0; i < count; i++) {
			ret[i] = bf.makeBucket(size);
		}
		return ret;
	}

	/**
	 * Free buckets. Get yer free buckets here! No charge! All you can carry
	 * free buckets!
	 * <p>
	 * If an exception happens the method will attempt to free the remaining
	 * buckets then retun the first exception. Buckets successfully freed are
	 * made <code>null</code> in the array.
	 * </p>
	 * 
	 * @param bf
	 * @param buckets
	 * @throws IOException
	 *             the first exception The <code>buckets</code> array will
	 */
	public static void freeBuckets(BucketFactory bf, Bucket[] buckets)
		throws IOException {
		if (buckets == null) {
			return;
		}

		IOException firstIoe = null;

		for (int i = 0; i < buckets.length; i++) {
			// Make sure we free any temp buckets on exception
			try {
				if (buckets[i] != null) {
					bf.freeBucket(buckets[i]);
				}
				buckets[i] = null;
			} catch (IOException e) {
				if (firstIoe == null) {
					firstIoe = e;
				}
			}
		}

		if (firstIoe != null) {
			throw firstIoe;
		}
	}

	// Note: Not all buckets are allocated by the bf.
	//       You must use the BucketFactoryWrapper class above
	//       to free the returned buckets.
	//
	// Always returns blocks, blocks, even if it has to create
	// zero padded ones.
	public static Bucket[] splitFile(
		File file,
		int blockSize,
		long offset,
		int blocks,
		boolean readOnly,
		BucketFactoryWrapper bf)
		throws IOException {

		long len = file.length() - offset;
		if (len > blocks * blockSize) {
			len = blocks * blockSize;
		}

		long padBlocks = 0;
		if ((blocks * blockSize) - len >= blockSize) {
			padBlocks = ((blocks * blockSize) - len) / blockSize;
		}

		Bucket[] ret = new Bucket[blocks];
		Bucket[] rab =
			RandomAccessFileBucket.segment(
				file,
				blockSize,
				offset,
				(int) (blocks - padBlocks),
				true);
		System.arraycopy(rab, 0, ret, 0, rab.length);

		boolean groovy = false;
		try {
			if (len % blockSize != 0) {
				// Copy and zero pad final partial block
				Bucket partial = ret[rab.length - 1];
				ret[rab.length - 1] = bf.makeBucket(blockSize);
				paddedCopy(
					partial,
					ret[rab.length - 1],
					len % blockSize,
					blockSize);
			}

			// Trailing zero padded blocks
			for (int i = rab.length; i < ret.length; i++) {
				ret[i] = bf.makeBucket(blockSize);
				zeroPad(ret[i], blockSize);
			}
			groovy = true;
		} finally {
			if (!groovy) {
				freeBuckets(bf, ret);
			}
		}
		return ret;
	}

	public final static int[] nullIndices(Bucket[] array) {
		List list = new ArrayList();
		for (int i = 0; i < array.length; i++) {
			if (array[i] == null) {
				list.add(new Integer(i));
			}
		}

		int[] ret = new int[list.size()];
		for (int i = 0; i < list.size(); i++) {
			ret[i] = ((Integer) list.get(i)).intValue();
		}
		return ret;
	}

	public final static int[] nonNullIndices(Bucket[] array) {
		List list = new ArrayList();
		for (int i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.add(new Integer(i));
			}
		}

		int[] ret = new int[list.size()];
		for (int i = 0; i < list.size(); i++) {
			ret[i] = ((Integer) list.get(i)).intValue();
		}
		return ret;
	}

	public final static Bucket[] nonNullBuckets(Bucket[] array) {
		List list = new ArrayList(array.length);
		for (int i = 0; i < array.length; i++) {
			if (array[i] != null) {
				list.add(array[i]);
			}
		}

		Bucket[] ret = new Bucket[list.size()];
		return (Bucket[]) list.toArray(ret);
	}
}
