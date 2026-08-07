package com.matchmaker.server.dao;

import java.time.LocalDateTime;

public record UserRecord(int id, String username, String passwordHash, boolean admin,
                          int wins, int losses, int draws, int rating, LocalDateTime createdAt) {
}
