package com.matchmaker.server.dao;

import com.matchmaker.common.dto.ChatMessageDTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class InMemoryChatMessageDao implements ChatMessageDao {

    private final List<ChatMessageDTO> messages = new ArrayList<>();

    @Override
    public synchronized void insert(int sessionId, int userId, String content) {
        messages.add(new ChatMessageDTO(sessionId, userId, content, LocalDateTime.now()));
    }

    @Override
    public synchronized List<ChatMessageDTO> findBySession(int sessionId) {
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessageDTO message : messages) {
            if (message.getSessionId() == sessionId) {
                result.add(message);
            }
        }
        return result;
    }
}
