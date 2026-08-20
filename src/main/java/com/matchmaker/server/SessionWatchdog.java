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
import java.util.logging.Level;
import java.util.logging.Logger;

public class SessionWatchdog {

    private static final Logger LOG = Logger.getLogger(SessionWatchdog.class.getName());

    private final SessionManager sessionManager;
    private final GameSessionDao gameSessionDao;
    private final GameEventPublisher gameEventPublisher;
    private final Duration disconnectTimeout;
    private final Duration turnTimeout;
    private final Instant startedAt = Instant.now();

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
        try {
            sessionManager.evictExpired();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "SessionWatchdog: failed to evict expired sessions", e);
        }

        List<GameStateDTO> activeSessions;
        try {
            activeSessions = gameSessionDao.findAllActive();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "SessionWatchdog: failed to list active sessions", e);
            return;
        }
        for (GameStateDTO session : activeSessions) {
            try {
                checkSession(session);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "SessionWatchdog: failed to check session " + session.getSessionId(), e);
            }
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
        if (lastSeen.isEmpty()) {
            return isOlderThan(startedAt, disconnectTimeout);
        }
        return isOlderThan(lastSeen.get(), disconnectTimeout);
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
            LOG.log(Level.WARNING, "Failed to notify session " + sessionId + " of abandonment", e);
        }
    }
}
