package org.concentus;

/**
 * Exception thrown when an Opus encoding or decoding error occurs.
 */
public class OpusException extends Exception {
    public OpusException() {
        super();
    }

    public OpusException(String message) {
        super(message);
    }

    public OpusException(String message, Throwable cause) {
        super(message, cause);
    }
}
