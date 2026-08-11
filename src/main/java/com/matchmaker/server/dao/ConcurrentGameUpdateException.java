package com.matchmaker.server.dao;

/**
 * Thrown by {@link GameSessionDao#recordMove} when the session's turn/status no longer
 * matches what the caller validated against -- i.e. someone else's move (or another call
 * for the same session) already committed first. Distinct from {@link DaoException}: this
 * is an expected outcome of a race, not an unexpected database failure.
 */
public class ConcurrentGameUpdateException extends RuntimeException {
    public ConcurrentGameUpdateException(String message) {
        super(message);
    }
}
