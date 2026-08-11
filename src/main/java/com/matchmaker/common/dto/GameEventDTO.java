package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameEventType;

import java.io.Serializable;

public class GameEventDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final GameEventType type;
    private final int sessionId;
    private final GameStateDTO gameState;

    public GameEventDTO(GameEventType type, int sessionId, GameStateDTO gameState) {
        this.type = type;
        this.sessionId = sessionId;
        this.gameState = gameState;
    }

    public GameEventType getType() {
        return type;
    }

    public int getSessionId() {
        return sessionId;
    }

    public GameStateDTO getGameState() {
        return gameState;
    }
}
