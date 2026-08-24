package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameStatus;

import java.io.Serializable;
import java.time.LocalDateTime;

public class GameHistoryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int sessionId;
    private final String gameTypeName;
    private final String opponentUsername;
    private final GameStatus status;
    private final Integer winnerId;
    private final LocalDateTime endTime;

    public GameHistoryDTO(int sessionId, String gameTypeName, String opponentUsername,
                          GameStatus status, Integer winnerId, LocalDateTime endTime) {
        this.sessionId = sessionId;
        this.gameTypeName = gameTypeName;
        this.opponentUsername = opponentUsername;
        this.status = status;
        this.winnerId = winnerId;
        this.endTime = endTime;
    }

    public int getSessionId() {
        return sessionId;
    }

    public String getGameTypeName() {
        return gameTypeName;
    }

    public String getOpponentUsername() {
        return opponentUsername;
    }

    public GameStatus getStatus() {
        return status;
    }

    public Integer getWinnerId() {
        return winnerId;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }
}
