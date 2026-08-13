package com.matchmaker.client.communication;

public class ServerCommunicationException extends RuntimeException {
    public ServerCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
