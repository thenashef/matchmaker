package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryGameSessionDao implements GameSessionDao {

    private final List<GameStateDTO> sessions = new ArrayList<>();
    private final Map<Integer, Instant> turnStartedAtBySessionId = new HashMap<>();
    private final List<MoveDTO> moves = new ArrayList<>();
    private final Map<Integer, Integer> rematchSessionIdBySessionId = new HashMap<>();
    private final AtomicInteger nextRematchSessionId = new AtomicInteger(100_000);
    private int gamesStartedTodayForTest = 0;

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

    public void addMove(MoveDTO move) {
        moves.add(move);
    }

    /** This fake has no notion of wall-clock StartTime, so tests set the "today" count directly. */
    public void setGamesStartedTodayForTest(int count) {
        this.gamesStartedTodayForTest = count;
    }

    @Override
    public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
        List<GameStateDTO> result = new ArrayList<>();
        for (GameStateDTO session : sessions) {
            boolean isParticipant = session.getPlayer1Id() == userId || session.getPlayer2Id() == userId;
            boolean isFinished = session.getStatus() == GameStatus.FINISHED || session.getStatus() == GameStatus.ABANDONED;
            if (isParticipant && isFinished) {
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
    public Optional<GameStateDTO> findById(int sessionId) {
        for (GameStateDTO session : sessions) {
            if (session.getSessionId() == sessionId) {
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
    public int countActive() {
        int count = 0;
        for (GameStateDTO session : sessions) {
            if (session.getStatus() == GameStatus.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int countStartedToday() {
        return gamesStartedTodayForTest;
    }

    @Override
    public List<MoveDTO> findMovesForSession(int sessionId) {
        List<MoveDTO> result = new ArrayList<>();
        for (MoveDTO move : moves) {
            if (move.getSessionId() == sessionId) {
                result.add(move);
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

    @Override
    public synchronized GameStateDTO createRematch(int finishedSessionId, String initialBoardState)
            throws AlreadyInGameException {
        Integer existingId = rematchSessionIdBySessionId.get(finishedSessionId);
        if (existingId != null) {
            GameStateDTO existing = findById(existingId).orElseThrow(
                    () -> new IllegalStateException("Dangling rematch id " + existingId));
            if (existing.getStatus() != GameStatus.ACTIVE) {
                throw new AlreadyInGameException("The rematch of session " + finishedSessionId
                        + " already ended (session " + existingId + ")");
            }
            return existing;
        }

        GameStateDTO finished = findById(finishedSessionId)
                .orElseThrow(() -> new IllegalArgumentException("No such session " + finishedSessionId));

        int newPlayer1Id = finished.getPlayer2Id();
        int newPlayer2Id = finished.getPlayer1Id();
        for (GameStateDTO session : sessions) {
            if (session.getStatus() != GameStatus.ACTIVE) {
                continue;
            }
            boolean involvesEither = session.getPlayer1Id() == newPlayer1Id || session.getPlayer2Id() == newPlayer1Id
                    || session.getPlayer1Id() == newPlayer2Id || session.getPlayer2Id() == newPlayer2Id;
            if (involvesEither) {
                throw new AlreadyInGameException("A participant of session is already in a different active game");
            }
        }

        int newSessionId = nextRematchSessionId.getAndIncrement();
        GameStateDTO created = new GameStateDTO(newSessionId, finished.getGameTypeId(),
                newPlayer1Id, newPlayer2Id, GameStatus.ACTIVE, newPlayer1Id, null, initialBoardState);
        sessions.add(created);
        turnStartedAtBySessionId.put(newSessionId, Instant.now());
        rematchSessionIdBySessionId.put(finishedSessionId, newSessionId);
        return created;
    }
}
