package com.matchmaker.server;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsPublishException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionWatchdog {

    private final SessionManager sessionManager;
    private final GameSessionDao gameSessionDao;
    private final GameEventPublisher gameEventPublisher;
    private final Duration disconnectTimeout;
    private final Duration turnTimeout;

    private ScheduledExecutorService executor;

    public SessionWatchdog(SessionManager sessionManager, GameSessionDao gameSessionDao,
                            GameEventPublisher gameEventPublisher, Duration disconnectTimeout, Duration turnTimeout) {
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameEventPublisher = gameEventPublisher;
        this.disconnectTimeout = disconnectTimeout;
        this.turnTimeout = turnTimeout;
    }

    public void start(Duration tickInterval) {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = tickInterval.toMillis();
        executor.scheduleAtFixedRate(this::sweepOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void sweepOnce() {
        List<GameStateDTO> activeSessions = gameSessionDao.findAllActive();
        for (GameStateDTO session : activeSessions) {
            checkSession(session);
        }
    }

    private void checkSession(GameStateDTO session) {
        int sessionId = session.getSessionId();
        int player1Id = session.getPlayer1Id();
        int player2Id = session.getPlayer2Id();
        boolean player1Silent = isSilent(player1Id);
        boolean player2Silent = isSilent(player2Id);

        if (player1Silent && player2Silent) {
            abandon(sessionId, null);
            return;
        }
        if (player1Silent) {
            abandon(sessionId, player2Id);
            return;
        }
        if (player2Silent) {
            abandon(sessionId, player1Id);
            return;
        }

        Integer currentTurnUserId = session.getCurrentTurnUserId();
        if (currentTurnUserId == null) {
            return;
        }
        Optional<Instant> turnStartedAt = gameSessionDao.currentTurnStartedAt(sessionId);
        if (turnStartedAt.isPresent() && isOlderThan(turnStartedAt.get(), turnTimeout)) {
            int winnerUserId = currentTurnUserId == player1Id ? player2Id : player1Id;
            abandon(sessionId, winnerUserId);
        }
    }

    private boolean isSilent(int userId) {
        Optional<Instant> lastSeen = sessionManager.lastSeen(userId);
        return lastSeen.isEmpty() || isOlderThan(lastSeen.get(), disconnectTimeout);
    }

    private boolean isOlderThan(Instant instant, Duration threshold) {
        return Duration.between(instant, Instant.now()).compareTo(threshold) > 0;
    }

    private void abandon(int sessionId, Integer winnerUserId) {
        Optional<GameStateDTO> abandoned = gameSessionDao.abandon(sessionId, winnerUserId);
        if (abandoned.isEmpty()) {
            return;
        }
        try {
            gameEventPublisher.publishToSession(sessionId,
                    new GameEventDTO(GameEventType.SESSION_ABANDONED, sessionId, abandoned.get()));
        } catch (JmsPublishException e) {
            // The abandon already committed to the DB -- a failed notification shouldn't undo
            // it. Mirrors PlayerServiceImpl.makeMove()'s and joinQueue()'s identical handling.
            System.err.println("Failed to notify session " + sessionId + " of abandonment: " + e.getMessage());
        }
    }
}
