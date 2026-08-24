package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameHistoryDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.exceptions.AlreadyInGameException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GameSessionDao {
    List<GameStateDTO> findFinishedSessionsForUser(int userId);

    List<GameHistoryDTO> findHistoryForUser(int userId);

    Optional<GameStateDTO> findActiveById(int sessionId);

    /** Looks up a session by ID regardless of status. */
    Optional<GameStateDTO> findById(int sessionId);

    List<GameStateDTO> findAllActive();

    int countActive();

    int countStartedToday();

    List<MoveDTO> findMovesForSession(int sessionId);

    GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson);

    Optional<GameStateDTO> forceEnd(int sessionId);

    Optional<GameStateDTO> abandon(int sessionId, Integer winnerUserId);

    Optional<Instant> currentTurnStartedAt(int sessionId);

    /**
     * Creates a fresh ACTIVE session for a rematch of {@code finishedSessionId}, with players
     * swapped so turn order alternates. Idempotent: a repeated call for the same
     * {@code finishedSessionId} returns the session already created by the first call rather than
     * creating a duplicate. Throws {@link AlreadyInGameException} if either player already has a
     * different ACTIVE session.
     */
    GameStateDTO createRematch(int finishedSessionId, String initialBoardState) throws AlreadyInGameException;
}
