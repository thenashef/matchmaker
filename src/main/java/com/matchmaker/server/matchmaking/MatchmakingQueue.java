package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.exceptions.AlreadyInGameException;

public interface MatchmakingQueue {

    /** @return the new session if paired immediately, or null if the caller was enqueued. */
    GameStateDTO join(int userId, int gameTypeId, String initialBoardState)
            throws AlreadyInGameException;

    void cancel(int userId);
}
