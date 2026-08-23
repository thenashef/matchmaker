package com.matchmaker.server.dao;

import com.matchmaker.common.dto.UserDTO;

import java.time.LocalDateTime;

public record UserRecord(int id, String username, String passwordHash, boolean admin,
                          int wins, int losses, int draws, int rating, LocalDateTime createdAt) {

    public UserDTO toUserDTO() {
        return new UserDTO(id, username, admin, wins, losses, draws, rating);
    }

    public UserDTO toPublicUserDTO() {
        return new UserDTO(id, username, false, wins, losses, draws, rating);
    }
}
