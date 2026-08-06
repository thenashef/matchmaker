package com.matchmaker.common.dto;

import java.io.Serializable;

public class MoveDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int sessionId;
    private final int userId;
    private final int moveNumber;
    private final String payload;

    public MoveDTO(int sessionId, int userId, int moveNumber, String payload) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.moveNumber = moveNumber;
        this.payload = payload;
    }

    public int getSessionId() {
        return sessionId;
    }

    public int getUserId() {
        return userId;
    }

    public int getMoveNumber() {
        return moveNumber;
    }

    public String getPayload() {
        return payload;
    }
}
