package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameEventPublisher implements GameEventPublisher {

    public record PublishedEvent(int userId, GameEventDTO event) {
    }

    private final List<PublishedEvent> published = new ArrayList<>();

    @Override
    public void publishToPlayer(int userId, GameEventDTO event) {
        published.add(new PublishedEvent(userId, event));
    }

    public List<PublishedEvent> published() {
        return published;
    }
}
