package com.matchmaker.common.exceptions;

/**
 * Thrown by {@code PlayerService.joinQueue} when the caller already has an ACTIVE
 * {@code GameSession}.
 *
 * <p>The matchmaking queue's "one slot per user" guard only ever consulted the
 * {@code MatchmakingQueue} table, so a player with a game already in progress could queue
 * again and be matched into a second concurrent session — leaving the first to run down its
 * turn timer and record a loss they never saw happen.
 *
 * <p>Deliberately a distinct exception rather than reusing {@code joinQueue}'s {@code null}
 * return, which already means "you're queued, wait" and would have parked the caller on the
 * waiting screen forever.
 */
public class AlreadyInGameException extends MatchmakerException {
    public AlreadyInGameException(String message) {
        super(message);
    }
}
