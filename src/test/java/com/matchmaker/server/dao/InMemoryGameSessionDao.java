package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryGameSessionDao implements GameSessionDao {

    private final List<GameStateDTO> sessions = new ArrayList<>();
    private final Map<Integer, Instant> turnStartedAtBySessionId = new HashMap<>();

    public void addFinishedSession(GameStateDTO session) {
        sessions.add(session);
    }

    public void addActiveSession(GameStateDTO session) {
        sessions.add(session);
        turnStartedAtBySessionId.put(session.getSessionId(), Instant.now());
    }

    public void setTurnStartedAt(int sessionId, Instant turnStartedAt) {
        turnStartedAtBySessionId.put(sessionId, turnStartedAt);
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
        turnStartedAtBySessionId.put(updatedSession.getSessionId(), Instant.now());
        return updatedSession;
    }

    @Override
    public List<GameStateDTO> findAllActive() {
        List<GameStateDTO> result = new ArrayList<>();
        for (GameStateDTO session : sessions) {
            if (session.getStatus() == GameStatus.ACTIVE) {
                result.add(session);
            }
        }
        return result;
    }

    @Override
    public Optional<GameStateDTO> forceEnd(int sessionId) {
        for (GameStateDTO session : sessions) {
            if (session.getSessionId() == sessionId && session.getStatus() == GameStatus.ACTIVE) {
                GameStateDTO ended = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                        session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.ABANDONED, null, null,
                        session.getBoardState());
                sessions.removeIf(s -> s.getSessionId() == sessionId);
                sessions.add(ended);
                return Optional.of(ended);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<GameStateDTO> abandon(int sessionId, Integer winnerUserId) {
        for (GameStateDTO session : sessions) {
            if (session.getSessionId() == sessionId && session.getStatus() == GameStatus.ACTIVE) {
                GameStateDTO ended = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                        session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.ABANDONED, null, winnerUserId,
                        session.getBoardState());
                sessions.removeIf(s -> s.getSessionId() == sessionId);
                sessions.add(ended);
                return Optional.of(ended);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Instant> currentTurnStartedAt(int sessionId) {
        return Optional.ofNullable(turnStartedAtBySessionId.get(sessionId));
    }
}
