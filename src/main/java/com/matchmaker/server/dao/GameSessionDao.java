package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GameSessionDao {
    List<GameStateDTO> findFinishedSessionsForUser(int userId);

    Optional<GameStateDTO> findActiveById(int sessionId);

    List<GameStateDTO> findAllActive();

    GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson);

    Optional<GameStateDTO> forceEnd(int sessionId);

    Optional<GameStateDTO> abandon(int sessionId, Integer winnerUserId);

    Optional<Instant> currentTurnStartedAt(int sessionId);
}
