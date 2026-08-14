package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final Map<String, Integer> userIdByToken = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> lastSeenByUserId = new ConcurrentHashMap<>();

    public String createSession(int userId) {
        String token = UUID.randomUUID().toString();
        userIdByToken.put(token, userId);
        return token;
    }

    public int resolve(String token) throws AuthenticationException {
        Integer userId = userIdByToken.get(token);
        if (userId == null) {
            throw new AuthenticationException("Invalid or expired session token");
        }
        lastSeenByUserId.put(userId, Instant.now());
        return userId;
    }

    public Optional<Instant> lastSeen(int userId) {
        return Optional.ofNullable(lastSeenByUserId.get(userId));
    }
}
