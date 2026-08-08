package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;

public interface MatchmakingQueue {

    /**
     * Attempts to pair {@code userId} with a waiting opponent for {@code gameTypeId}; if
     * none is found, enqueues {@code userId} to wait instead.
     *
     * <p>Returns {@code null} if no opponent was waiting — the caller has been enqueued
     * and must wait for a future {@code join()} call (by someone else) to pair with them.
     * Returns a non-null {@link GameStateDTO} if a waiting opponent was found: the two
     * players are matched immediately, the resulting {@code GameSession} is created, and
     * that session is returned to the caller. The opponent who was already waiting is not
     * notified by this call — they only find out via a later mechanism (JMS, roadmap step
     * 6), since their own {@code join()} call already returned {@code null} before this
     * pairing happened.
     */
    GameStateDTO join(int userId, int gameTypeId);

    void cancel(int userId);
}
