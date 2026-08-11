package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class InMemoryGameSessionDao implements GameSessionDao {

    private final List<GameStateDTO> sessions = new ArrayList<>();

    public void addFinishedSession(GameStateDTO session) {
        sessions.add(session);
    }

    public void addActiveSession(GameStateDTO session) {
        sessions.add(session);
    }

    @Override
    public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
        List<GameStateDTO> result = new ArrayList<>();
        for (GameStateDTO session : sessions) {
            if (session.getPlayer1Id() == userId || session.getPlayer2Id() == userId) {
                result.add(session);
            }
        }
        return result;
    }

    @Override
    public Optional<GameStateDTO> findActiveById(int sessionId) {
        for (GameStateDTO session : sessions) {
            if (session.getSessionId() == sessionId && session.getStatus() == GameStatus.ACTIVE) {
                return Optional.of(session);
            }
        }
        return Optional.empty();
    }

    @Override
    public GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson) {
        sessions.removeIf(session -> session.getSessionId() == updatedSession.getSessionId());
        sessions.add(updatedSession);
        return updatedSession;
    }
}
