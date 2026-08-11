package com.matchmaker.server.jms;

public class JmsPublishException extends RuntimeException {
    public JmsPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
