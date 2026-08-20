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
     *
     * <p>{@code initialBoardState} is the opening position to store on the new session,
     * passed in rather than derived here so this stays game-agnostic: the queue persists
     * whatever opaque state string it is handed, exactly as it already treats move payloads,
     * and never needs to know which {@code GameEngine} a given {@code gameTypeId} maps to.
     * It must not be null — a session that exists always has a board, and readers that
     * would otherwise have to guess (the admin monitor, the watchdog's abandon push, an
     * admin force-end) previously crashed on the null this used to leave behind.
     */
    GameStateDTO join(int userId, int gameTypeId, String initialBoardState);

    void cancel(int userId);
}
