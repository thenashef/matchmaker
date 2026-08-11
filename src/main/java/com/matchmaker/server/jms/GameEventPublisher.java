package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

public interface GameEventPublisher {

    void publishToPlayer(int userId, GameEventDTO event);
}
