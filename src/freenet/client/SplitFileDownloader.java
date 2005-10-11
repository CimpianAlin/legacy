// REDFLAG: handle releasing ArrayBucketSink buckets on failure before FEC decode.

package freenet.client;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Vector;

import freenet.Connection;
import freenet.Core;
import freenet.client.events.DataNotFoundEvent;
import freenet.client.events.RouteNotFoundEvent;
import freenet.client.events.StateReachedEvent;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.SplitFile;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;
import freenet.support.ArrayBucketSink;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketSequence;
import freenet.support.BucketSink;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.NullBucket;

// REDFLAG: double check nested locks
//          Have another look at the 
//          SplitFileLoader.this / retryQueue
//          nested lock issue.
//
//          I think it's ok because only
//          the worker thread holds nested locks.

/*
  This code is distributed under the GNU Public Licence (GPL)
  version 2.  See http://www.gnu.org/ for further details of the GPL.
*/

/**
 * Class to do streamed downloads of Freenet 0.4 SplitFiles
 * using multiple concurrent threads.
 *
 * @author giannij
 **/
public class SplitFileDownloader {

	public final static int STARTING = 1;
	public final static int DOWNLOADING_SEGMENT = 2;
	public final static int FEC_DECODING = 3;
	public final static int FINISHED = 4;
	public final static int ABORTED = 5;
	public final static int FAILED = 6;

	//public final static int TRANSFERRING = 4; // REDFLAG: untenable?

	private int state = STARTING;
	private Status finalStatus = new Status();

	private static boolean logDebug = true;

	private final synchronized void setState(int value) {
		state = value;
	}

	// For UI. *not* guaranteed to be in a consistent state.
	public static class Status {
		public int segment;
		public int segments;
		// hmmm. Digging myself in even deeper with 
		// fixed block size assumption.
		public int blockSize;
		public int blocksRequired;
		public int runningThreads;
		public int blocksQueued;
		public int blocksDownloaded;
		// definitively, after retries exhausted.
		public int blocksFailed;
		// Can happen more than once per block
		// if retrying is enabled.
		public int dnfCount;
		public int rnfCount;
		// Requests that RNFed without ever leaving the 
		// node.
		public int localRNFCount;
		public long lastActiveMs;

		// LATER: for msg, "fec decoding 15 missing data blocks..."
		//public int nDataBlocks;
		public int retries;
		public int state;
	}

	// REDFLAG: event handler?

	public synchronized Status getStatus() {
		if (!isRunning()) {
			return finalStatus;
		}

		Status ret = new Status();
		ret.segments = segments;
		ret.segment = currentSegment;
		ret.blockSize = reportedBlockSize;
		ret.blocksRequired = requiredSuccesses;
		ret.runningThreads = nPending;
		ret.blocksQueued = requestQueue.size();
		ret.blocksDownloaded = successes;
		ret.blocksFailed = failures;
		ret.dnfCount = dnfCount;
		ret.rnfCount = rnfCount;
		ret.localRNFCount = localRNFCount;
		ret.retries = cumulativeRetries;
		ret.lastActiveMs = lastActiveMs;
		ret.state = state;

		return ret;
	}

	public SplitFileDownloader(int nThreads) {
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		this.nThreads = nThreads;
		this.finalStatus.lastActiveMs = System.currentTimeMillis();
	}

	public final void setHtl(int htl) {
		this.htl = htl;
	}
	public final int getHtl() {
		return htl;
	}

	public final void setMaxRetries(int retries) {
		this.maxRetries = retries;
	}
	public final int getMaxRetries() {
		return maxRetries;
	}

	public final void setRetryHtlIncrement(int increment) {
		this.retryHtlIncrement = increment;
	}
	public final int getRetryHtlIncrement() {
		return retryHtlIncrement;
	}

	public final void setThreads(int nThreads) {
		this.nThreads = nThreads;
	}
	public final int getThreads() {
		return nThreads;
	}

	public final void setSkipDataStore(boolean skip) {
		skipDS = skip;
	}
	// runs in another thread.
	public synchronized BucketSequence start(
		SplitFile sf,
		ClientFactory factory,
		BucketFactory bucketFactory,
		int offset,
		int nBytes,
		Connection conn) {

		if (isRunning()) {
			throw new IllegalStateException("The SplitFileDownloader is already running.");
		}

		this.factory = factory;
		this.bucketFactory = bucketFactory;
		this.blockURIs = sf.getBlockURIs();
		this.conn = conn;
		this.reportedBlockSize = sf.getBlockSize();

		// Reset state
		nPending = 0;
		failing = false;
		stopping = false;
		startedCanceling = false;
		aborted = false;
		currentSegment = 0;
		segments = 1;
		cumulativeRetries = 0;
		dnfCount = 0;
		rnfCount = 0;
		localRNFCount = 0;

		startBlock = offset / sf.getBlockSize();
		int startBlockOffset = offset - startBlock * sf.getBlockSize();
		endBlock = (offset + nBytes) / sf.getBlockSize();

		this.sequence =
			new BucketSequence(
				startBlock,
				endBlock,
				startBlockOffset,
				nBytes,
				bucketFactory);

		sink = sequence;
		allowedFailures = 0;
		requiredSuccesses = sf.getBlockCount();

		Thread t = new Thread(task, "SplitFileDownloader -- worker thread");
		t.start();
		worker = t;
		setState(STARTING);
		return sequence;
	}

	// Safer, doesn't depend on blockSize.
	// Runs in another thread.
	public synchronized BucketSequence start(
		SplitFile sf,
		ClientFactory factory,
		BucketFactory bucketFactory,
		FECTools tools,
		Connection conn)
		throws IOException {

		if (isRunning()) {
			throw new IllegalStateException("The SplitFileDownloader is already running.");
		}

		this.factory = factory;
		this.bucketFactory = bucketFactory;
		this.blockURIs = sf.getBlockURIs();
		this.checkURIs = sf.getCheckBlockURIs();
		this.conn = conn;
		this.reportedBlockSize = sf.getBlockSize();

		// Reset state
		nPending = 0;
		failing = false;
		stopping = false;
		startedCanceling = false;
		aborted = false;
		currentSegment = 0;
		segments = 1;
		cumulativeRetries = 0;
		dnfCount = 0;
		rnfCount = 0;
		localRNFCount = 0;

		// REDFLAG: size changed to long. hmmm... problems in other places?
		this.sequence = new BucketSequence((int) sf.getSize(), bucketFactory);
		// By default buckets go directly into the Bucket Sequence.
		sink = sequence;

		// Defaults for non-redundant downloading.
		allowedFailures = 0;
		requiredSuccesses = sf.getBlockCount();
		startBlock = 0;
		endBlock = sf.getBlockCount() - 1;

		fecTools = null;
		headersAndMaps = null;
		if ((tools != null) && (sf.getCheckBlockCount() > 0)) {
			Bucket sfMeta = bucketFactory.makeBucket(-1); // REDFLAG: GRRRRR
			try {
				DocumentCommand doc =
					new DocumentCommand(new MetadataSettings());
				doc.addPart(sf);

				Metadata md = new Metadata(new MetadataSettings());
				md.addDocument(doc);
				md.writeTo(sfMeta.getOutputStream());
				// Throws if it can't find a decoder.
				headersAndMaps = tools.segmentSplitFile(-1, sfMeta);
				fecTools = tools;
			} catch (IOException ioe) {
				System.err.println("Couldn't get decoder!");
			} catch (InvalidPartException ioe) {
				// Something went very wrong.
				throw new RuntimeException(ioe.toString());
			} finally {
				bucketFactory.freeBucket(sfMeta);
			}

			segments = headersAndMaps[0].header.getSegments();

			// startBlock, endBlock, not used by FEC codePath
			// allowedFailures, requiredSuccesses will be set by
			// createRequests(). 
		}

		Thread t = new Thread(task, "SplitFileDownloader -- worker thread");
		t.start();
		worker = t;
		setState(STARTING);
		return sequence;
	}

	private void setupSinkForSegment(int n) {
		ArrayBucketSink a = new ArrayBucketSink();
		a.setLength(
			headersAndMaps[n].header.getBlockCount()
				+ headersAndMaps[n].header.getCheckBlockCount());

		sink = a;
	}

	public synchronized void cancel() {
		aborted = true;
		failing = true;
		stopping = true;
		if (worker != null) {
			worker.interrupt();
		}
		notifyAll();
	}

	public synchronized boolean isRunning() {
		return worker != null;
	}

	////////////////////////////////////////////////////////////

	private final synchronized boolean handleBucket(
		Bucket b,
		int blockNum,
		boolean success) {
		boolean ret = success;
		try {
			if (success && (!stopping)) {
				// hmmm... We touch sequence / FECDecoder with a lock on
				// SplitFileDownloader, ok?
				//System.err.println("putting bucket: " + blockNum);
				sink.putBucket(b, blockNum);
				ret = true;
			} else {
				//System.err.println("discarding bucket: " + blockNum);
				bucketFactory.freeBucket(b);
			}
		} catch (Exception e) {
			//e.printStackTrace();
			// REDFLAG: hiding InterruptedException ok?
			try {
				bucketFactory.freeBucket(b);
			} catch (Exception e1) {
			}
		}
		return ret;
	}

	private final synchronized void requestFinished(
		Bucket b,
		int blockNum,
		boolean success) {
		//  System.err.println("SplitFileDownloader.requestFinished -- " + blockNum + " " +
		//                             success);

		if (!handleBucket(b, blockNum, success)) {
			failures++;
			if (failures > allowedFailures) {
				failing = true;
				stopping = true;
			}
		} else {
			successes++;
			if (successes >= requiredSuccesses) {
				stopping = true;
			}
		}
		nPending--;
		SplitFileDownloader.this.notifyAll();
	}

	private final void queueRetry(BlockRequest request) {
		synchronized (requestQueue) {
			// IMPORTANT: 
			// Non-redundant downloads won't work if 
			// even a single block fails, so retry 
			// the failed request as soon as possible.
			if (fecTools == null) {
				// Retry this request next.
				requestQueue.insertElementAt(request, 0);
			} else {
				// Retry after all other pending 
				// requests are tried.
				requestQueue.addElement(request);
			}
		}
		synchronized (this) {
			notifyAll();
		}
	}

	private final void cancelRunningRequests() {
		Vector copy = null;
		synchronized (this) {
			// runningRequests could shrink while the loop
			// is running.
			copy = (Vector) runningRequests.clone();
			startedCanceling = true;
		}

		// IMPORTANT:^*&^*&(^*&^*&!@^&!^ InternalClient work around.
		// Can't hold locks on any client objects while canceling
		// or we may deadlock.

		for (int i = 0; i < copy.size(); i++) {
			BlockRequest request = (BlockRequest) copy.elementAt(i);
			if (request != null) {
				request.cancel();
			}
		}
	}

	// This is a hack to detect when the client has
	// dropped the connection.  It is ugly, but
	// it keeps us from running many unnecessary
	// conncurrent requests.
	private final boolean pingConnection() {
	    return conn.isInClosed();
	}

	private static void shuffle(Vector requests) {
		Vector list = (Vector) requests.clone();
		int count = 0;
		while (list.size() > 0) {
			int index = (int) (Math.random() * list.size());
			requests.setElementAt(list.elementAt(index), count);
			list.removeElementAt(index);
			count++;
		}
	}

	private final synchronized void createRequests(Vector requests)
		throws IOException {
		if (failing) {
			return;
		}

		// Reset for the new segment.
		stopping = false;

		requests.removeAllElements();
		if (fecTools == null) {
			final int len = endBlock - startBlock + 1;
			for (int i = 0; i < len; i++) {
				// Only data, no check blocks.
				requests.addElement(
					new BlockRequest(
						blockURIs[i + startBlock],
						i + startBlock,
						htl));
			}
		} else {
			SegmentHeader header = headersAndMaps[currentSegment].header;
			BlockMap map = headersAndMaps[currentSegment].map;

			final int k = header.getBlockCount();
			final int n = header.getBlockCount() + header.getCheckBlockCount();

			String[] dataCHKs = map.getDataBlocks();
			int i = 0;
			for (i = 0; i < dataCHKs.length; i++) {
				requests.addElement(new BlockRequest(dataCHKs[i], i, htl));
			}

			String[] checkCHKs = map.getCheckBlocks();
			for (i = 0; i < checkCHKs.length; i++) {
				requests.addElement(
					new BlockRequest(checkCHKs[i], i + dataCHKs.length, htl));
			}

			allowedFailures = n - k;
			requiredSuccesses = k;

			System.err.println(
				"Starting redundant download of segment "
					+ currentSegment
					+ ". Need "
					+ k
					+ " of "
					+ n
					+ " blocks.");

			// Shuffle to keep some blocks from becoming more popular
			// than others in Freenet.
			shuffle(requests);

			// Grab the buckets so we can decode them.
			setupSinkForSegment(currentSegment);
		}
	}

	// REDFLAG: remove eventually
	private static final String arrayToString(int[] array) {
		StringBuffer ret = new StringBuffer();
		for (int i = 0; i < array.length; i++) {
			ret.append(Integer.toString(array[i]) + " ");
		}
		return ret.toString().trim();
	}

	// Returns true if there are more segments to be decoded.
	private final boolean doFECDecode() throws IOException {
		if ((fecTools != null) && !failing) {
			setState(FEC_DECODING);

			SegmentHeader header = headersAndMaps[currentSegment].header;

			Bucket[] blocks = null;
			Bucket[] decoded = null;

			try {
				blocks = ((ArrayBucketSink) sink).getBuckets();
				// We own the buckets now.  If any other
				// thread is trying to put buckets that's
				// a bug, so make sure we see it.
				 ((ArrayBucketSink) sink).setLength(0);

				Vector list = new Vector();
				int i = 0;
				// Find data indices
				for (i = 0; i < header.getBlockCount(); i++) {
					if (blocks[i] != null) {
						list.addElement(new Integer(i));
					}
				}
				int[] dataIndices = new int[list.size()];
				for (i = 0; i < list.size(); i++) {
					dataIndices[i] = ((Integer) list.elementAt(i)).intValue();
				}

				list.removeAllElements();
				// Find check  indices
				for (i = 0; i < header.getCheckBlockCount(); i++) {
					if (blocks[i + header.getBlockCount()] != null) {
						list.addElement(
							new Integer(i + header.getBlockCount()));
					}
				}
				int[] checkIndices = new int[list.size()];
				for (i = 0; i < list.size(); i++) {
					checkIndices[i] = ((Integer) list.elementAt(i)).intValue();
				}

				list.removeAllElements();
				// Find required data  indices
				for (i = 0; i < header.getBlockCount(); i++) {
					if (blocks[i] == null) {
						list.addElement(new Integer(i));
					}
				}
				int[] requestedIndices = new int[list.size()];
				for (i = 0; i < list.size(); i++) {
					requestedIndices[i] =
						((Integer) list.elementAt(i)).intValue();
				}
				list.removeAllElements();

				// Get non-null blocks
				Bucket[] nonNulls =
					new Bucket[dataIndices.length + checkIndices.length];
				for (i = 0; i < dataIndices.length; i++) {
					nonNulls[i] = blocks[dataIndices[i]];
				}
				for (i = 0; i < checkIndices.length; i++) {
					nonNulls[i + dataIndices.length] = blocks[checkIndices[i]];
				}

				// Only decode if we are missing some data blocks.
				if (dataIndices.length < header.getBlockCount()) {
					// FEC decode. Throws on failure
					decoded =
						fecTools.decodeSegment(
							header,
							dataIndices,
							checkIndices,
							requestedIndices,
							nonNulls);
					// Add the decoded blocks.
					for (i = 0; i < requestedIndices.length; i++) {
						blocks[requestedIndices[i]] = decoded[i];
					}

					decoded = null;
				}
				// Now write the entire segment to the sequence.
				for (i = 0; i < header.getBlockCount(); i++) {
					sequence.putBucket(
						blocks[i],
						i + header.getDataBlockOffset());
					blocks[i] = null; // Give up ownership of data blocks.
				}

				Core.logger.log(this, "FEC Decode succeeded.", Logger.DEBUG);
			} catch (IOException e) {
				Core.logger.log(this, "FEC Decode failed.", Logger.DEBUG);
				throw e;
			} finally {
				BucketTools.freeBuckets(bucketFactory, blocks);
				// NOTE: We always free the check blocks
				//       even on success.
				BucketTools.freeBuckets(bucketFactory, decoded);
			}

			currentSegment++;
			if (currentSegment >= segments) {
				return false;
			}
			// createRequests handles setting up the FEC for the
			// next segment.
			return true;
		}
		return false;
	}

	// Runs requests in requestQueue and waits for 
	// them to finish. Waits even on exception.
	private final void runRequests() throws InterruptedException, IOException {
		Throwable t = null;

		boolean needToCancel = false;
		unsynchronized_loop : for (;;) {
			if (needToCancel) {
				// IMPORTANT: 
				// The purpose of all of this flow control gobbledygook 
				// is to cancel in an unlocked scope.
				cancelRunningRequests();
			}

			needToCancel = false;
			synchronized (this) {
				while (((requestQueue.size() > 0) && (!stopping))
					|| (nPending > 0)) {

					logDebug=Core.logger.shouldLog(Logger.DEBUG,this);

					if (!stopping) {
						stopping = pingConnection();
					}
					if (logDebug)
						Core.logger.log(
							this,
							"early: nPending = "
								+ nPending
								+ ", nThreads = "
								+ nThreads
								+ ", rq.size = "
								+ requestQueue.size()
								+ ", stopping = "
								+ stopping
								+ ", startedCanceling = "
								+ startedCanceling,
							Logger.DEBUG);

					if (stopping && (!startedCanceling)) {
						needToCancel = true;
						if (logDebug)
							Core.logger.log(
								this,
								"Continuing as stopping",
								Logger.DEBUG);
						continue unsynchronized_loop;
					}
					if (logDebug)
						Core.logger.log(this, "Still here", Logger.DEBUG);
					// Start tasks.
					while ((nPending < nThreads)
						&& (requestQueue.size() > 0)
						&& (!stopping)) {
						if (logDebug)
							Core.logger.log(
								this,
								"In runRequests.while: "
									+ "nPending = "
									+ nPending
									+ ", nThreads = "
									+ nThreads
									+ ", rq.size = "
									+ requestQueue.size(),
								Logger.DEBUG);
						BlockRequest request = null;

						// hmmm...
						// nested locks: (this), requestQueue.
						synchronized (requestQueue) {
							request = (BlockRequest) requestQueue.elementAt(0);
							requestQueue.removeElementAt(0);
						}

						boolean reallyStarted = false;
						try {
							Client c = factory.getClient(request);
							request.setClient(c);
							if (logDebug)
								Core.logger.log(
									this,
									"requesting block "
										+ request.getBlockNumber(),
									Logger.DEBUG);
							nPending++;
							c.start();
							reallyStarted = true;
						} catch (Throwable uho) {
							Core.logger.log(
								this,
								"IGNORED EXCEPTION",
								uho,
								Logger.MINOR);
							System.err.println("IGNORED EXCEPTION: ");
							uho.printStackTrace();
							cancel();
							t = uho; // hmmm... Exception masking
						} finally {
							if (logDebug)
								Core.logger.log(
									this,
									"Running runRequests.finally",
									Logger.DEBUG);
							if (reallyStarted) {
								runningRequests.addElement(request);
								if (Core.logger.shouldLog(Logger.DEBUG, this))
									Core.logger.log(
										this,
										"Added element " + request,
										Logger.DEBUG);
							} else {
								nPending--;
							}
							if (logDebug)
								Core.logger.log(
									this,
									"nPending now " + nPending,
									Logger.DEBUG);
						}
					}
					// REDFLAG: Stall???
					try {
						if (logDebug)
							Core.logger.log(this, "Trying...", Logger.DEBUG);
						SplitFileDownloader.this.wait(POLLINGINTERVAL_MS);
					} catch (InterruptedException ie) {
						t = ie; // hmmm... Exception masking
					}
					if (logDebug)
						Core.logger.log(this, "Leaving...", Logger.DEBUG);
				}
			}
			break unsynchronized_loop;
		}
		if (logDebug)
			Core.logger.log(this, "Out of while()", Logger.DEBUG);
		// Throw only after all pending requests have been
		// canceled.
		if (t != null) {
			if (t instanceof InterruptedException) {
				throw (InterruptedException) t;
			} else if (t instanceof IOException) {
				throw (IOException) t;
			} else {
				// Somewhat underwhelming style.
				if (logDebug)
					Core.logger.log(
						this,
						"runRequests throwing because: ",
						t,
						Logger.DEBUG);
				throw new IOException(t.toString());
			}
		}
		if (logDebug)
			Core.logger.log(
				this,
				"Exiting runRequests without throwing",
				Logger.DEBUG);
	}

	public class RunnableImpl implements Runnable {
		public void run() {
			boolean userAbort = false;
			try {
				boolean moreSegments = false;
				do {
					// Reset for each segment.
					failures = 0;
					successes = 0;
					nPending = 0;
					cumulativeRetries = 0;
					dnfCount = 0;
					rnfCount = 0;
					localRNFCount = 0;

					runningRequests.removeAllElements();
					createRequests(requestQueue);
					setState(DOWNLOADING_SEGMENT);
					runRequests();
					if (!failing) {
						lastActiveMs = System.currentTimeMillis();
						moreSegments = doFECDecode();
						lastActiveMs = System.currentTimeMillis();
					}
				} while (moreSegments && (!failing));
			} catch (Exception e) {
				if (Core.logger.shouldLog(Logger.DEBUG, this))
					Core.logger.log(this, e.toString(), Logger.DEBUG);
				e.printStackTrace();
				// So that we can distinguish user abort
				// for exit status.
				userAbort = aborted;
				cancel();
			} finally {
				if (sequence != null) {
					if (failing) {
						// Frees buckets and forces an error on the client input stream.
						sequence.abort(
							"Couldn't read one or more segments from Freenet.",
							-1);
					} else {
						// REDFLAG: really nesc.???
						// Let the bucket sequence know that there's no more data coming.
						sequence.eod();
					}
				}

				synchronized (SplitFileDownloader.this) {
					if ((successes >= requiredSuccesses) && (!failing)) {
						setState(FINISHED);
					} else if (userAbort) {
						setState(ABORTED);
					} else {
						Core.logger.log(
							this,
							"run(): successes="
								+ successes
								+ " requiredSuccesses="
								+ requiredSuccesses
								+ " failing="
								+ failing,
							Logger.NORMAL);
						setState(FAILED);
					}
					// Note: 
					// The BucketSequence can still be streaming data
					// when we return...

					finalStatus = getStatus();
					worker = null;
					SplitFileDownloader.this.notifyAll();
				}
			}
		}
	}

	// REDFLAG: Remove once Freenet is behaving?
	private final String getCHK(String cipherName, Bucket meta, Bucket data) {
		ComputeCHKRequest request =
			new ComputeCHKRequest(cipherName, meta, data);

		FreenetURI chkURI = null;
		try {
			factory.getClient(request).blockingRun();
		} catch (Exception e) {
			System.err.println(
				"SplitFileDownloader.getCHK -- ignored exception: " + e);
			e.printStackTrace();
		}

		chkURI = request.getURI();
		if (chkURI == null) {
			return "";
		} else {
			return chkURI.toString();
		}
	}

	////////////////////////////////////////////////////////////
	// Extending GetRequest to be a ClientEventListener is risky
	// business.  REDFLAG:Grok code for deadlocks. Smells rotten...
	//
	// PESTER:
	// I would have preferred to pull the BlockRequest ref out of
	// ClientEvent by upcasting java.util.EventObject.getSource()
	// as is the common Java idiom. But ClientEvent doesn't extend
	// java.util.EventObject...
	//
	public class BlockRequest
		extends GetRequest
		implements ClientEventListener {
		public BlockRequest(String uri, int blockNumber, int htl)
			throws MalformedURLException, IOException {
			// PESTER: modify BucketFactory, get rid of -1
			super(
				htl,
				uri,
				new NullBucket(),
				bucketFactory.makeBucket(-1),
				skipDS);
			this.blockNumber = blockNumber;
			this.lastHtlUsed = htl;

			logDebug=Core.logger.shouldLog(Logger.DEBUG,this);

			addEventListener(this);
		}

		public BlockRequest(
			String uri,
			int blockNumber,
			int htl,
			int retries,
			Bucket data)
			throws MalformedURLException, IOException {
			super(htl, uri, new NullBucket(), data);
			this.blockNumber = blockNumber;
			this.lastHtlUsed = htl;
			this.retries = retries;

			logDebug=Core.logger.shouldLog(Logger.DEBUG,this);

			addEventListener(this);
		}

		private boolean logDebug=true;

		private final void removeFromRunningList() {
			// NO! deadlock.
			//synchronized(SplitFileDownloader.this) {

			// Be sloppy and sometimes cancel a request that
			// has already stopped.

			// Clear the entry in the list used to cancel
			// running requests.
			if (runningRequests.contains(BlockRequest.this)) {
				runningRequests.removeElement(BlockRequest.this);
			}

			//}
		}

		// 021019 gj (actually this was commented out a while back)
		// Note: 0) Looks like courruption problem is fixed.
		//       1) This gets called on the stack of an InternalClient
		//          event, which seemed to cause weird intermittent stalls.
		//          I never figured out whether it was a lock contention
		//          problem in my code or a problem with InternalClient.
		// Note: 
		// I added this on 020128 because corrupted blocks can be
		// retrieved without error.
		// REDFLAG: Remove once bugs are beaten out of the new
		//          DS merged code.
		//
		// Paranoid checking to catch bad blocks before they get
		// tossed to the FEC decoder.
		// ASSUMES CHK's have no metadata.
		// ASSUMES Twofish encryption
		private final boolean checkCHK() {
			//              if (!uri.toString().startsWith("freenet:CHK")) {
			//                  System.err.println("SplitFileDownloader.BlockRequester.checkCHK -- ignored:  " + uri);
			//                  return true;
			//              }

			//              String candidate = getCHK("Twofish", new NullBucket(), data);

			//              if (!candidate.equals(uri.toString())) {
			//                  String msg = "expected: " + uri +
			//                      " got: " + candidate;
			//                  System.err.println("SplitFileDownloader.BlockRequester.checkCHK -- " + msg);
			//                  Core.logger.log(this, msg, 
			//                                  Logger.MINOR);
			//                  return false;
			//              }
			// REDFLAG: remove?
			return true;
		}

		public void receive(ClientEvent ce) {

			//hmmm perhaps too vociferous even for debug logging

			//heh, you should see NativeFSDirectory.java :) - amphibian
			if (logDebug)
				Core.logger.log(
					this,
					"block: " + blockNumber + " " + ce.getDescription(),
					Logger.DEBUG);

			lastActiveMs = System.currentTimeMillis();

			if (ce instanceof StateReachedEvent) {
				StateReachedEvent sr = (StateReachedEvent) ce;

				switch (sr.getState()) {
					case Request.FAILED :
						removeFromRunningList();
						attemptRetry();
						break;
					case Request.CANCELLED :
						removeFromRunningList();
						requestFinished(data, blockNumber, false);
						break;
					case Request.DONE :
						{
							removeFromRunningList();
							// Double check for corrupted data blocks 
							// if we are FEC decoding.
							//
							// You need to know the metadata and cipher
							// in order to check the CHK.  I know this
							// for FEC SplitFiles because I wrote the only
							// insertion tools.  I don't check non-FEC
							// SplitFiles because for them I don't know.
							// 
							// REDFLAG: remove obsolete code?
							//
							//if ((fecTools == null) || checkCHK()) {
							requestFinished(data, blockNumber, true);
							//}
							//else {
							//    attemptRetry();
							//}
						}
						break;
					default :
						// NOP
				}
			} else if (ce instanceof RouteNotFoundEvent) {
				rnf = (RouteNotFoundEvent) ce;
				boolean localFailure = false;
				final int total =
					rnf.getUnreachable()
						+ rnf.getRestarted()
						+ rnf.getRejected();
				if (total == rnf.getUnreachable()) {
					localFailure = true;
				}
				synchronized (SplitFileDownloader.this) {
					rnfCount++;
					if (localFailure) {
						localRNFCount++;
					}
				}
			} else if (ce instanceof DataNotFoundEvent) {
				dnf = (DataNotFoundEvent) ce;
				synchronized (SplitFileDownloader.this) {
					dnfCount++;
				}
			}
		}

		// Keep track of client so we can cancel.
		public void setClient(Client c) {
			client = c;
		}

		public void cancel() {
			if (client != null) {
				client.cancel();
			}
		}

		public final int getBlockNumber() {
			return blockNumber;
		}
		public final int getHtl() {
			return BlockRequest.this.htl;
		}

		public void release() {
			try {
				bucketFactory.freeBucket(data);
			} catch (Exception e) {
			}
		}

		private void attemptRetry() {
			synchronized (SplitFileDownloader.this) {
				if (retries >= maxRetries) {
					requestFinished(data, blockNumber, false);
					return;
				}
			}

			// REDFLAG: hmmm...
			//if (rnf != null) {
			//	// Can't really recover from RNF
			//	requestFinished(data, blockNumber, false);
			//	return;
			//}

			if ((dnf == null) && (rnf == null)) {
				// We don't know what the hell happened, but
				// we assume it wasn't good.
				requestFinished(data, blockNumber, false);
				return;
			}

			// Reuse the data bucket.
			try {
				data.resetWrite();
			} catch (IOException ioe) {
				requestFinished(data, blockNumber, false);
				return;
			}

			BlockRequest next = null;
			try {
				// Make a new request because I am not sure that
				// Client implementations support re-using requests.
				next =
					new BlockRequest(
						uri.toString(),
						blockNumber,
						lastHtlUsed + retryHtlIncrement,
						retries + 1,
						data);
			} catch (Exception e) {
				Core.logger.log(
					this,
					"Exception creating retry request. THIS SHOULD NEVER HAPPEN.",
					Logger.ERROR);

				requestFinished(data, blockNumber, false);
				return;
			}

			queueRetry(next);
			synchronized (SplitFileDownloader.this) {
				// dec *after* queing the retry.
				nPending--;
				cumulativeRetries++;
				SplitFileDownloader.this.notifyAll();
			}
		}

		private RouteNotFoundEvent rnf = null;
		private DataNotFoundEvent dnf = null;

		private int lastHtlUsed = -1;
		private int retries;

		private int blockNumber = -1;
		private Client client = null;
	}

	////////////////////////////////////////////////////////////
	private final static int POLLINGINTERVAL_MS = 5000;

	ClientFactory factory = null;
	BucketFactory bucketFactory = null;
	Connection conn = null;
	BucketSink sink = null;

	int nThreads = -1;
	volatile int nPending = -1;

	boolean failing = false;
	boolean stopping = false;
	boolean aborted = false;

	int failures = 0;
	int successes = 0;
	int allowedFailures = 0;
	int requiredSuccesses = 0;

	boolean startedCanceling = false;
	Thread worker = null;
	Runnable task = new RunnableImpl();

	String[] blockURIs = null;
	String[] checkURIs = null;

	int startBlock = -1;
	int endBlock = -1;
	int htl = -1;
	int retryHtlIncrement = 20;
	int maxRetries = 1;
	boolean skipDS = false;

	int segments = 1;
	int currentSegment = 0;
	int reportedBlockSize = -1;
	int cumulativeRetries = -1;
	int dnfCount = 0;
	int rnfCount = 0;
	int localRNFCount = 0;

	long lastActiveMs = System.currentTimeMillis();

	Vector requestQueue = new Vector();
	Vector runningRequests = new Vector();

	BucketSequence sequence = null;

	FECTools fecTools = null;
	FECTools.HeaderAndMap[] headersAndMaps = null;
}
