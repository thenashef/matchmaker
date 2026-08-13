package com.matchmaker.server.dao;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Override
    public synchronized Optional<UserRecord> findById(int id) {
        return usersByUsername.values().stream().filter(u -> u.id() == id).findFirst();
    }

    @Override
    public synchronized List<UserRecord> findAll() {
        return new ArrayList<>(usersByUsername.values());
    }

    /** Test-only helper -- insert() always creates a non-admin record, mirroring real
     *  registration, so tests that need an admin account promote one afterward. */
    public synchronized void markAdmin(int userId) {
        usersByUsername.replaceAll((username, record) -> record.id() == userId
                ? new UserRecord(record.id(), record.username(), record.passwordHash(), true,
                        record.wins(), record.losses(), record.draws(), record.rating(), record.createdAt())
                : record);
    }
}
