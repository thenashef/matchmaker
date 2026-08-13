package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameEventDTO;

@FunctionalInterface
public interface ServerEventListener {
    void onEvent(GameEventDTO event);
}
