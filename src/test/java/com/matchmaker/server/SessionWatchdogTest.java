package com.matchmaker.server;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.jms.InMemoryGameEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionWatchdogTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);

    private final SessionManager sessionManager = new SessionManager();
    private final InMemoryGameSessionDao gameSessionDao = new InMemoryGameSessionDao();
    private final InMemoryGameEventPublisher gameEventPublisher = new InMemoryGameEventPublisher();
    private final SessionWatchdog watchdog = new SessionWatchdog(
            sessionManager, gameSessionDao, gameEventPublisher, SHORT_TIMEOUT, SHORT_TIMEOUT);

    @Test
    void sweepOnce_oneParticipantSilent_abandonsWithOtherPlayerWinning() throws Exception {
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
        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);
        sessionManager.resolve(token1);
        sessionManager.resolve(token2);
        gameSessionDao.addActiveSession(new GameStateDTO(10, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}"));

        watchdog.sweepOnce();

        assertTrue(gameSessionDao.findActiveById(10).isPresent());
        assertTrue(gameEventPublisher.publishedToSessions().isEmpty());
    }
}
