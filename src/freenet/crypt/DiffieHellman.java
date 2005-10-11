/*
 * This code is part of the Java Adaptive Network Client by Ian Clarke. It is
 * distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

package freenet.crypt;

import java.math.BigInteger;
import java.util.Random;
import java.util.Stack;

import freenet.Core;

public class DiffieHellman {
	
	/**
	 * When the number of precalculations falls below this threshold generation
	 * starts up to make more.
	 */
	private static final int PRECALC_RESUME = 15;

	/** Maximum number of precalculations to create. */
	private static final int PRECALC_MAX = 30;

	/**
	 * How often to wake up and make sure the precalculation buffer is full
	 * regardless of how many are left, in milliseconds. This helps keep the
	 * buffer ready for usage spikes when it is being empties slowly.
	 */
	private static final int PRECALC_TIMEOUT = 193 * 1000;

	private static Random r = Core.getRandSource();
	private static DHGroup group = Global.DHgroupA;
	private static Stack precalcBuffer = new Stack();
	private static Object precalcerWaitObj = new Object();

	private static Thread precalcThread;

	static {
		precalcThread = new PrecalcBufferFill();
		precalcThread.start();
	}

	private static class PrecalcBufferFill extends Thread {

		public PrecalcBufferFill() {
			setName("Diffie-Helman-Precalc");
			setDaemon(true);
		}

		public void run() {
			while (true) {
				while (precalcBuffer.size() < PRECALC_MAX) {
					precalcBuffer.push(genParams());
					synchronized (precalcBuffer) {
						// Notify a waiting thread, that new data is available
						precalcBuffer.notify();
					}
				}

				// Reset the thread priority to normal because it may have been
				// set to MAX if the buffer was emptied.
				precalcThread.setPriority(Thread.NORM_PRIORITY);

				synchronized (precalcerWaitObj) {
						try {
						// Do not set the thread priority here because the
						// thread may have been stopped while holding the
						// precalcerWaitObj lock. The stop causes the thread
						// group to be cleared and setPriority to throw a NPE.
						precalcerWaitObj.wait(PRECALC_TIMEOUT);
						// TODO: this timeout might very well be unneccsary
						} catch (InterruptedException ie) {
							// Ignored.
						}
					}
				}
			}
		}

	/**
	 * This method does not do anything, but calling it causes the
	 * PrecalcBufferFill thread to be started by the static block at the class
	 * scope.
	 */
	public static void init() {
		// Intentionally empty.
	}

	/** Will ask the precalc thread to refill the buffer if neccessary */
	private static void askRefill() {
		// If the buffer size is below the threshold then wake the precalc
		// thread
		if (precalcBuffer.size() < PRECALC_RESUME) {
			if (precalcBuffer.isEmpty()) {
				// If it is all empty, try to fill it up even faster
				precalcThread.setPriority(Thread.MAX_PRIORITY);
			}
			synchronized (precalcerWaitObj) {
				precalcerWaitObj.notify();
			}
		}
			}

	public static BigInteger[] getParams() {
		synchronized (precalcBuffer) {
			//Ensure that we will have something to pop (at least pretty soon)
			askRefill(); 

			//Wait until we actually have something to pop
			while (precalcBuffer.isEmpty()) {
				try {
					precalcBuffer.wait();
				} catch (InterruptedException e) {
					// Ignored.
				}
			}

			BigInteger[] result = (BigInteger[]) precalcBuffer.pop();

			//Hint the precalcer that it might have something to do now
			askRefill();

			//Release possible other precalc value waiters
			precalcBuffer.notify();

			return result;
		}
	}

	private static BigInteger[] genParams() {
		BigInteger params[] = new BigInteger[2];
		// Don't need NativeBigInteger?
		params[0] = new BigInteger(256, r);
		params[1] = group.getG().modPow(params[0], group.getP());
		return params;
	}

	public static DHGroup getGroup() {
		return group;
	}
	
}
