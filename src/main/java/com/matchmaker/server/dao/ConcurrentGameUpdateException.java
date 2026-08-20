package com.matchmaker.server.dao;

public class ConcurrentGameUpdateException extends RuntimeException {
    public ConcurrentGameUpdateException(String message) {
        super(message);
    }
}
