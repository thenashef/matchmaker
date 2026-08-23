package com.matchmaker.server;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.jms.InMemoryGameEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionWatchdogTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(10);

    private final SessionManager sessionManager = new SessionManager();
    private final InMemoryGameSessionDao gameSessionDao = new InMemoryGameSessionDao();
    private final InMemoryGameEventPublisher gameEventPublisher = new InMemoryGameEventPublisher();

    @Test
    void sweepOnce_oneParticipantSilent_abandonsWithOtherPlayerWinning() throws Exception {
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, gameSessionDao, gameEventPublisher, SHORT_TIMEOUT, LONG_TIMEOUT);
        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));

        Thread.sleep(80);
        sessionManager.resolve(token2);

        watchdog.sweepOnce();

        assertTrue(gameSessionDao.findActiveById(10).isEmpty());
        assertEquals(1, gameEventPublisher.publishedToSessions().size());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertEquals(10, published.sessionId());
        assertEquals(GameEventType.SESSION_ABANDONED, published.event().getType());
        assertEquals(Integer.valueOf(2), published.event().getGameState().getWinnerId());
    }

    @Test
    void sweepOnce_bothParticipantsSilent_abandonsWithNoWinner() throws Exception {
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, gameSessionDao, gameEventPublisher, SHORT_TIMEOUT, LONG_TIMEOUT);
        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));

        Thread.sleep(80);

        watchdog.sweepOnce();

        assertTrue(gameSessionDao.findActiveById(10).isEmpty());
        assertEquals(1, gameEventPublisher.publishedToSessions().size());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertEquals(GameEventType.SESSION_ABANDONED, published.event().getType());
        assertNull(published.event().getGameState().getWinnerId());
    }

    @Test
    void sweepOnce_turnExpiredButBothPlayersPresent_abandonsWithNonCurrentTurnPlayerWinning() throws Exception {
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, gameSessionDao, gameEventPublisher, SHORT_TIMEOUT, SHORT_TIMEOUT);
        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));

        Thread.sleep(80);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);

        watchdog.sweepOnce();

        assertTrue(gameSessionDao.findActiveById(10).isEmpty());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertEquals(GameEventType.SESSION_ABANDONED, published.event().getType());
        assertEquals(Integer.valueOf(2), published.event().getGameState().getWinnerId());
    }

    @Test
    void sweepOnce_healthySession_leavesItUntouched() throws Exception {
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, gameSessionDao, gameEventPublisher, LONG_TIMEOUT, LONG_TIMEOUT);
        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));
        gameSessionDao.setTurnStartedAt(10, Instant.now());

        watchdog.sweepOnce();

        assertTrue(gameSessionDao.findActiveById(10).isPresent());
        assertTrue(gameEventPublisher.publishedToSessions().isEmpty());
    }

    @Test
    void sweepOnce_participantNeverSeen_leavesSessionUntouchedWithinGracePeriod() {
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, gameSessionDao, gameEventPublisher, LONG_TIMEOUT, LONG_TIMEOUT);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));
        gameSessionDao.setTurnStartedAt(10, Instant.now());

        watchdog.sweepOnce();

        assertTrue(gameSessionDao.findActiveById(10).isPresent());
        assertTrue(gameEventPublisher.publishedToSessions().isEmpty());
    }

    @Test
    void sweepOnce_participantNeverSeenPastGracePeriod_abandonsWithNoWinner() throws Exception {
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, gameSessionDao, gameEventPublisher, SHORT_TIMEOUT, LONG_TIMEOUT);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));

        Thread.sleep(80);

        watchdog.sweepOnce();

        assertTrue(gameSessionDao.findActiveById(10).isEmpty());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertNull(published.event().getGameState().getWinnerId());
    }

    @Test
    void sweepOnce_findAllActiveThrows_doesNotPropagate() {
        GameSessionDao throwingDao = new ThrowingGameSessionDao(gameSessionDao, true, -1);
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, throwingDao, gameEventPublisher, SHORT_TIMEOUT, SHORT_TIMEOUT);

        assertDoesNotThrow(watchdog::sweepOnce);
    }

    @Test
    void sweepOnce_oneSessionThrows_stillChecksTheOtherSessionInTheSameTick() throws Exception {
        GameSessionDao throwingDao = new ThrowingGameSessionDao(gameSessionDao, false, 10);
        SessionWatchdog watchdog = new SessionWatchdog(
                sessionManager, throwingDao, gameEventPublisher, SHORT_TIMEOUT, LONG_TIMEOUT);

        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);
        String token3 = sessionManager.createSession(3);
        String token4 = sessionManager.createSession(4);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);
        sessionManager.resolve(token3);
        sessionManager.resolve(token4);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));
        gameSessionDao.addActiveSession(new GameStateDTO(20, 1, 3, 4, GameStatus.ACTIVE, 3, null, "{}"));

        Thread.sleep(80);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);
        sessionManager.resolve(token4);

        assertDoesNotThrow(watchdog::sweepOnce);

        assertTrue(gameSessionDao.findActiveById(10).isPresent(), "session 10 was never actually abandoned -- it just threw");
        assertTrue(gameSessionDao.findActiveById(20).isEmpty(), "session 20 should still have been abandoned");
    }

    private static class ThrowingGameSessionDao implements GameSessionDao {
        private final GameSessionDao delegate;
        private final boolean alwaysThrowOnFindAllActive;
        private final int throwForSessionId;

        ThrowingGameSessionDao(GameSessionDao delegate, boolean alwaysThrowOnFindAllActive, int throwForSessionId) {
            this.delegate = delegate;
            this.alwaysThrowOnFindAllActive = alwaysThrowOnFindAllActive;
            this.throwForSessionId = throwForSessionId;
        }

        @Override
        public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
            return delegate.findFinishedSessionsForUser(userId);
        }

        @Override
        public Optional<GameStateDTO> findActiveById(int sessionId) {
            return delegate.findActiveById(sessionId);
        }

        @Override
        public Optional<GameStateDTO> findById(int sessionId) {
            return delegate.findById(sessionId);
        }

        @Override
        public List<GameStateDTO> findAllActive() {
            if (alwaysThrowOnFindAllActive) {
                throw new RuntimeException("simulated DB failure");
            }
            return delegate.findAllActive();
        }

        @Override
        public GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson) {
            return delegate.recordMove(updatedSession, movingUserId, movePayloadJson);
        }

        @Override
        public Optional<GameStateDTO> forceEnd(int sessionId) {
            return delegate.forceEnd(sessionId);
        }

        @Override
        public Optional<GameStateDTO> abandon(int sessionId, Integer winnerUserId) {
            return delegate.abandon(sessionId, winnerUserId);
        }

        @Override
        public Optional<Instant> currentTurnStartedAt(int sessionId) {
            if (sessionId == throwForSessionId) {
                throw new RuntimeException("simulated DB failure for session " + sessionId);
            }
            return delegate.currentTurnStartedAt(sessionId);
        }

        @Override
        public int countActive() {
            return delegate.countActive();
        }

        @Override
        public int countStartedToday() {
            return delegate.countStartedToday();
        }

        @Override
        public List<MoveDTO> findMovesForSession(int sessionId) {
            return delegate.findMovesForSession(sessionId);
        }

        @Override
        public GameStateDTO createRematch(int finishedSessionId, String initialBoardState)
                throws com.matchmaker.common.exceptions.AlreadyInGameException {
            return delegate.createRematch(finishedSessionId, initialBoardState);
        }
    }
}
