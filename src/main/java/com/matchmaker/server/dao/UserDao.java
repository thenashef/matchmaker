package com.matchmaker.server.dao;

import java.util.Optional;

public interface UserDao {
    Optional<UserRecord> insert(String username, String passwordHash);
    Optional<UserRecord> findByUsername(String username);
}
