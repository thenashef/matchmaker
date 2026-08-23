package com.matchmaker.common.dto;

import java.io.Serializable;

public class AdminDashboardStatsDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int onlinePlayers;
    private final int activeGames;
    private final int gamesToday;
    private final int openInQueue;

    public AdminDashboardStatsDTO(int onlinePlayers, int activeGames, int gamesToday, int openInQueue) {
        this.onlinePlayers = onlinePlayers;
        this.activeGames = activeGames;
        this.gamesToday = gamesToday;
        this.openInQueue = openInQueue;
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public int getActiveGames() {
        return activeGames;
    }

    public int getGamesToday() {
        return gamesToday;
    }

    public int getOpenInQueue() {
        return openInQueue;
    }
}
