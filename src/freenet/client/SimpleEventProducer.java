package freenet.client;
import java.util.Vector;
import java.util.Enumeration;
import java.util.NoSuchElementException;

// We need to sort out client logging
import freenet.Core;
import freenet.support.Logger;

/**
 * Event handeling for clients. SimpleEventProducer is a simple
 * ClientEventProducer implementation that can be used for others.
 *
 * @author oskar
 **/
public class SimpleEventProducer implements ClientEventProducer {

    private Vector listeners;

    /**
     * Create a new SimpleEventProducer
     *
     **/
    public SimpleEventProducer() {
	listeners = new Vector();
    }
    
    /** Create a new SimpleEventProducer with the given listeners. */
    public SimpleEventProducer(ClientEventListener[] cela) {
	this();
	for (int i = 0 ; i < cela.length ; i++)
	    addEventListener(cela[i]);
    }
    
    public void addEventListener(ClientEventListener cel) {
	if(cel != null)
	    listeners.addElement(cel);
	else
	    throw new IllegalArgumentException("Adding a null listener!");
    }
    
    public boolean removeEventListener(ClientEventListener cel) {
	boolean b = listeners.removeElement(cel);
	listeners.trimToSize();
	return b;
    }

    /**
     * Sends the ClientEvent to all registered listeners of this object.
     **/
    public void produceEvent(ClientEvent ce) {
	for (Enumeration e = listeners.elements() ; 
	     e.hasMoreElements();) {
            try {
                ((ClientEventListener) e.nextElement()).receive(ce);
            } catch (NoSuchElementException ne) {
		Core.logger.log(this, "Concurrent modification in "+
				"produceEvent!: "+this, Logger.NORMAL);
	    } catch (Exception ue) {
                System.err.println("---Unexpected Exception------------------");
                ue.printStackTrace();
                System.err.println("-----------------------------------------");
            }
	}
    }
    
    /** Returns the listeners as an array. */
    public ClientEventListener[] getEventListeners() {
	ClientEventListener[] ret =
	    new ClientEventListener[listeners.size()];
	listeners.copyInto(ret);
	return ret;
    }

    /** Adds all listeners in the given array. */
    public void addEventListeners(ClientEventListener[] cela) {
	for (int i = 0 ; i < cela.length ; i++)
	    addEventListener(cela[i]);
    }
}
