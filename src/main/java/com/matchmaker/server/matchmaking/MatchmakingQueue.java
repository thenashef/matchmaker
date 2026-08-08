package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;

public interface MatchmakingQueue {
    GameStateDTO join(int userId, int gameTypeId);
    void cancel(int userId);
}
