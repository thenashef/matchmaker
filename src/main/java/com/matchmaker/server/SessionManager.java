package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
        sessionByToken.put(token, new Session(userId, Instant.now()));
        return token;
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
        if (token != null) {
            sessionByToken.remove(token);
        }
    }

    public int evictExpired() {
        Instant now = Instant.now();
        int before = sessionByToken.size();
        sessionByToken.values().removeIf(session -> isExpired(session, now));
        return before - sessionByToken.size();
    }

    public Optional<Instant> lastSeen(int userId) {
        return Optional.ofNullable(lastSeenByUserId.get(userId));
    }

    private boolean isExpired(Session session, Instant now) {
        return Duration.between(session.lastUsed(), now).compareTo(tokenTtl) > 0;
    }

    private record Session(int userId, Instant lastUsed) {
    }
}
