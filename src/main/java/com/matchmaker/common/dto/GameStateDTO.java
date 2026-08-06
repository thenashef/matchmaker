package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameStatus;

import java.io.Serializable;

public class GameStateDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int sessionId;
    private final int gameTypeId;
    private final int player1Id;
    private final int player2Id;
    private final GameStatus status;
    private final Integer currentTurnUserId;
    private final Integer winnerId;
    private final String boardState;

    public GameStateDTO(int sessionId, int gameTypeId, int player1Id, int player2Id,
                         GameStatus status, Integer currentTurnUserId, Integer winnerId, String boardState) {
        this.sessionId = sessionId;
        this.gameTypeId = gameTypeId;
        this.player1Id = player1Id;
        this.player2Id = player2Id;
        this.status = status;
        this.currentTurnUserId = currentTurnUserId;
        this.winnerId = winnerId;
        this.boardState = boardState;
    }

    public int getSessionId() {
        return sessionId;
    }

    public int getGameTypeId() {
        return gameTypeId;
    }

    public int getPlayer1Id() {
        return player1Id;
    }

    public int getPlayer2Id() {
        return player2Id;
    }

    public GameStatus getStatus() {
        return status;
    }

    public Integer getCurrentTurnUserId() {
        return currentTurnUserId;
    }

    public Integer getWinnerId() {
        return winnerId;
    }

    public String getBoardState() {
        return boardState;
    }
}
