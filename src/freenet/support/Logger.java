/*
 * Created on Mar 18, 2004
 *
 */
package freenet.support;

/**
 * @author Iakin
 
 */
public interface Logger {

	/** These indicate the verbosity levels for calls to log() * */

	/** This message indicates an error which prevents correct functionality* */
	public static final int ERROR = 16;

	/** A normal level occurrence * */
	public static final int NORMAL = 8;

	/** A minor occurrence that wouldn't normally be of interest * */
	public static final int MINOR = 4;

	/** An occurrence which would only be of interest during debugging * */
	public static final int DEBUG = 2;

	/** Internal occurrances used for eg distribution stats * */
	public static final int INTERNAL = 1;
	
	/**
	 * Log a message
	 * 
	 * @param o
	 *            The object where this message was generated.
	 * @param source
	 *            The class where this message was generated.
	 * @param message
	 *            A clear and verbose message describing the event
	 * @param e
	 *            Logs this exception with the message.
	 * @param priority
	 *            The priority of the mesage, one of Logger.ERROR,
	 *            Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
	 */
	public abstract void log(
		Object o,
		Class source,
		String message,
		Throwable e,
		int priority);
	
	 /**
     * Log a message.
     * @param source        The source object where this message was generated
     * @param message A clear and verbose message describing the event
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     **/
    public void log(Object source, String message, int priority);

    /** 
     * Log a message with an exception.
     * @param o   The source object where this message was generated.
     * @param message  A clear and verbose message describing the event.
     * @param e        Logs this exception with the message.
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     * @see #log(Object o, String message, int priority)
     */
    public void log(Object o, String message, Throwable e, 
                    int priority);
    /**
     * Log a message from static code.
     * @param c        The class where this message was generated.
     * @param message  A clear and verbose message describing the event
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     */
    public void log(Class c, String message, int priority);

    /**
     * Log a message from static code.
     * @param c     The class where this message was generated.
     * @param message A clear and verbose message describing the event
     * @param e        Logs this exception with the message.
     * @param priority The priority of the mesage, one of Logger.ERROR,
     *                 Logger.NORMAL, Logger.MINOR, or Logger.DEBUGGING.
     */
    public void log(Class c, String message, Throwable e,
                    int priority);
	
	public boolean shouldLog(int priority, Class c);

	public boolean shouldLog(int prio, Object o);
	
	/**
	 * Changes the priority threshold.
	 * 
	 * @param thresh
	 *            The new threshhold
	 */
	public void setThreshold(int thresh);
	
	/**
	 * Changes the priority threshold.
	 * 
	 * @param symbolicThreshold
	 *            The new threshhold, must be one of ERROR,NORMAL etc.. 
	 */
	public void setThreshold(String symbolicThreshold);
	
	/**
	 * @return The currently used logging threshold
	 */
	public int getThreshold();
	
	public void setDetailedThresholds(String details);
}
