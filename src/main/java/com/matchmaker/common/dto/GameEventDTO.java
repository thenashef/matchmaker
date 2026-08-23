package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameEventType;

import java.io.Serializable;

public class GameEventDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final GameEventType type;
    private final int sessionId;
    private final GameStateDTO gameState;
    private final Integer chatSenderUserId;
    private final String chatContent;

    public GameEventDTO(GameEventType type, int sessionId, GameStateDTO gameState) {
        this(type, sessionId, gameState, null, null);
    }

    /**
     * For CHAT_MESSAGE events. Chat content travels as plain String/Integer fields here rather
     * than a nested ChatMessageDTO because ChatMessageDTO carries a java.time.LocalDateTime, and
     * the JMS connections in this app narrow ActiveMQ's trusted-package allow-list to
     * com.matchmaker.common.dto/enums plus a small hardcoded set of JDK types that does not
     * include java.time -- a LocalDateTime field would fail to deserialize on the wire.
     */
    public GameEventDTO(GameEventType type, int sessionId, int chatSenderUserId, String chatContent) {
        this(type, sessionId, null, chatSenderUserId, chatContent);
    }

    private GameEventDTO(GameEventType type, int sessionId, GameStateDTO gameState,
                          Integer chatSenderUserId, String chatContent) {
        this.type = type;
        this.sessionId = sessionId;
        this.gameState = gameState;
        this.chatSenderUserId = chatSenderUserId;
        this.chatContent = chatContent;
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

    public Integer getChatSenderUserId() {
        return chatSenderUserId;
    }

    public String getChatContent() {
        return chatContent;
    }
}
