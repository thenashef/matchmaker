package com.matchmaker.common.dto;

import java.io.Serializable;

public class UserDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String username;
    private final boolean admin;
    private final int wins;
    private final int losses;
    private final int draws;
    private final int rating;

    public UserDTO(int id, String username, boolean admin, int wins, int losses, int draws, int rating) {
        this.id = id;
        this.username = username;
        this.admin = admin;
        this.wins = wins;
        this.losses = losses;
        this.draws = draws;
        this.rating = rating;
    }

    public int getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public boolean isAdmin() {
        return admin;
    }

    public int getWins() {
        return wins;
    }

    public int getLosses() {
        return losses;
    }

    public int getDraws() {
        return draws;
    }

    public int getRating() {
        return rating;
    }
}
