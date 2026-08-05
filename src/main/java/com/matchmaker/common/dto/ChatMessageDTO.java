package com.matchmaker.common.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

public class ChatMessageDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int sessionId;
    private final int userId;
    private final String content;
    private final LocalDateTime sentAt;

    public ChatMessageDTO(int sessionId, int userId, String content, LocalDateTime sentAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.content = content;
        this.sentAt = sentAt;
    }

    public int getSessionId() { return sessionId; }
    public int getUserId() { return userId; }
    public String getContent() { return content; }
    public LocalDateTime getSentAt() { return sentAt; }
}
