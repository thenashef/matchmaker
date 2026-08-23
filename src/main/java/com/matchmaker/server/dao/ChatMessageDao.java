package com.matchmaker.server.dao;

import com.matchmaker.common.dto.ChatMessageDTO;

import java.util.List;

public interface ChatMessageDao {
    void insert(int sessionId, int userId, String content);

    /** Ordered chronologically (oldest first). */
    List<ChatMessageDTO> findBySession(int sessionId);
}
