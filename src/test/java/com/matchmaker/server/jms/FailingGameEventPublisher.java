package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

public class FailingGameEventPublisher implements GameEventPublisher {

    @Override
    public void publishToPlayer(int userId, GameEventDTO event) {
        throw new JmsPublishException("simulated JMS failure", new RuntimeException("broker unreachable"));
    }
}
