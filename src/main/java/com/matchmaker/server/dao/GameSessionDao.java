package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;

import java.util.List;
import java.util.Optional;

public interface GameSessionDao {
    List<GameStateDTO> findFinishedSessionsForUser(int userId);

    Optional<GameStateDTO> findActiveById(int sessionId);

    GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson);
}
