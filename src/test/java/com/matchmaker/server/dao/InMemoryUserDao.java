package com.matchmaker.server.dao;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryUserDao implements UserDao {

    private final Map<String, UserRecord> usersByUsername = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    @Override
    public synchronized Optional<UserRecord> insert(String username, String passwordHash) {
        if (usersByUsername.containsKey(username)) {
            return Optional.empty();
        }
        UserRecord record = new UserRecord(nextId.getAndIncrement(), username, passwordHash,
                false, 0, 0, 0, 1200, LocalDateTime.now());
        usersByUsername.put(username, record);
        return Optional.of(record);
    }

    @Override
    public synchronized Optional<UserRecord> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }
}
