package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final Map<String, Integer> userIdByToken = new ConcurrentHashMap<>();

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
        return userId;
    }
}
