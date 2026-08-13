package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameEventPublisher implements GameEventPublisher {

    public record PublishedEvent(int userId, GameEventDTO event) {
    }

    public record PublishedSessionEvent(int sessionId, GameEventDTO event) {
    }

    private final List<PublishedEvent> published = new ArrayList<>();
    private final List<PublishedSessionEvent> publishedToSessions = new ArrayList<>();

    @Override
    public void publishToPlayer(int userId, GameEventDTO event) {
        published.add(new PublishedEvent(userId, event));
    }

    @Override
    public void publishToSession(int sessionId, GameEventDTO event) {
        publishedToSessions.add(new PublishedSessionEvent(sessionId, event));
    }

    public List<PublishedEvent> published() {
        return published;
    }

    public List<PublishedSessionEvent> publishedToSessions() {
        return publishedToSessions;
    }
}
