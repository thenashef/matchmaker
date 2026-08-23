package com.matchmaker.common.communication;

import com.matchmaker.common.dto.GameEventDTO;

@FunctionalInterface
public interface ServerEventListener {
    void onEvent(GameEventDTO event);
}
