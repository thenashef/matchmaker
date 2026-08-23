package com.matchmaker.server.dao;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserDao {
    default Optional<UserRecord> insert(String username, String passwordHash) {
        return insert(username, passwordHash, false);
    }

    Optional<UserRecord> insert(String username, String passwordHash, boolean isAdmin);

    Optional<UserRecord> findByUsername(String username);
    Optional<UserRecord> findById(int id);
    List<UserRecord> findAll();
    Set<Integer> findAdminUserIds();
}
