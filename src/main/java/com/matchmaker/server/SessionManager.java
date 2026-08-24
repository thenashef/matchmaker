package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(30);

    private final Map<String, Session> sessionByToken = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> lastSeenByUserId = new ConcurrentHashMap<>();
    private final Duration tokenTtl;

    public SessionManager() {
        this(DEFAULT_TOKEN_TTL);
    }

    SessionManager(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String createSession(int userId) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        sessionByToken.put(token, new Session(userId, now));
        lastSeenByUserId.put(userId, now);
        return token;
    }

    public void invalidateAllForUser(int userId) {
        sessionByToken.entrySet().removeIf(entry -> entry.getValue().userId() == userId);
        lastSeenByUserId.remove(userId);
    }

    public int resolve(String token) throws AuthenticationException {
        if (token == null) {
            throw new AuthenticationException("Invalid or expired session token");
        }
        Instant now = Instant.now();
        Session session = sessionByToken.computeIfPresent(token,
                (key, existing) -> isExpired(existing, now) ? null : new Session(existing.userId(), now));
        if (session == null) {
            throw new AuthenticationException("Invalid or expired session token");
        }
        lastSeenByUserId.put(session.userId(), now);
        return session.userId();
    }

    public void invalidate(String token) {
        if (token == null) {
            return;
        }
        Session removed = sessionByToken.remove(token);
        if (removed == null) {
            return;
        }
        boolean stillHasAnotherSession = sessionByToken.values().stream()
                .anyMatch(session -> session.userId() == removed.userId());
        if (!stillHasAnotherSession) {
            lastSeenByUserId.remove(removed.userId());
        }
    }

    public int evictExpired() {
        Instant now = Instant.now();
        int before = sessionByToken.size();
        sessionByToken.values().removeIf(session -> isExpired(session, now));
        Set<Integer> remainingUserIds = new HashSet<>();
        for (Session session : sessionByToken.values()) {
            remainingUserIds.add(session.userId());
        }
        lastSeenByUserId.keySet().removeIf(userId -> !remainingUserIds.contains(userId));
        return before - sessionByToken.size();
    }

    public Optional<Instant> lastSeen(int userId) {
        return Optional.ofNullable(lastSeenByUserId.get(userId));
    }

    /**
     * Distinct user IDs with both a currently-unexpired session token and recent activity within
     * {@code livenessWindow} (players and admins alike). Token validity alone isn't enough -- the
     * token TTL is much longer than a realistic disconnect window, so a client that vanished
     * without logging out would otherwise still count as "online" for the rest of the TTL.
     */
    public Set<Integer> onlineUserIds(Duration livenessWindow) {
        Instant now = Instant.now();
        Set<Integer> ids = new HashSet<>();
        for (Session session : sessionByToken.values()) {
            if (isExpired(session, now)) {
                continue;
            }
            Instant seen = lastSeenByUserId.get(session.userId());
            if (seen != null && Duration.between(seen, now).compareTo(livenessWindow) <= 0) {
                ids.add(session.userId());
            }
        }
        return ids;
    }

    private boolean isExpired(Session session, Instant now) {
        return Duration.between(session.lastUsed(), now).compareTo(tokenTtl) > 0;
    }

    private record Session(int userId, Instant lastUsed) {
    }
}
