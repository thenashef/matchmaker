package com.matchmaker.server.dao;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryUserDao implements UserDao {

    private final Map<String, UserRecord> usersByUsername = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    @Override
    public synchronized Optional<UserRecord> insert(String username, String passwordHash, boolean isAdmin) {
        if (usersByUsername.containsKey(username)) {
            return Optional.empty();
        }
        UserRecord record = new UserRecord(nextId.getAndIncrement(), username, passwordHash,
                isAdmin, 0, 0, 0, 1200, LocalDateTime.now());
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

    @Override
    public synchronized Set<Integer> findAdminUserIds() {
        Set<Integer> result = new HashSet<>();
        for (UserRecord record : usersByUsername.values()) {
            if (record.admin()) {
                result.add(record.id());
            }
        }
        return result;
    }

    @Override
    public synchronized Optional<UserRecord> setAdmin(int userId, boolean admin) {
        Optional<UserRecord> found = findById(userId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UserRecord updated = withAdmin(found.get(), admin);
        usersByUsername.put(updated.username(), updated);
        return Optional.of(updated);
    }

    public synchronized void markAdmin(int userId) {
        setAdmin(userId, true);
    }

    private static UserRecord withAdmin(UserRecord record, boolean admin) {
        return new UserRecord(record.id(), record.username(), record.passwordHash(), admin,
                record.wins(), record.losses(), record.draws(), record.rating(), record.createdAt());
    }

    public synchronized void setStats(int userId, int wins, int losses, int draws, int rating) {
        usersByUsername.replaceAll((username, record) -> record.id() == userId
                ? new UserRecord(record.id(), record.username(), record.passwordHash(), record.admin(),
                        wins, losses, draws, rating, record.createdAt())
                : record);
    }
}
