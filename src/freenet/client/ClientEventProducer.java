package freenet.client;

/**
 * Event handling for clients.
 *
 * @author oskar
 */
public interface ClientEventProducer {

    /**
     * Sends the event to all registered EventListeners.
     * @param ce  the ClientEvent to raise
     */
    void produceEvent(ClientEvent ce);
        
    /**
     * Adds an EventListener that will receive all events produced
     * by the implementing object.
     * @param cel The ClientEventListener to add.
     */
    void addEventListener(ClientEventListener cel);

    /**
     * Removes an EventListener that will no loger receive events
     * produced by the implementing object.
     * @param cel  The ClientEventListener to remove.
     * @return     true if a Listener was removed, false otherwise.
     */
    boolean removeEventListener(ClientEventListener cel);
}


