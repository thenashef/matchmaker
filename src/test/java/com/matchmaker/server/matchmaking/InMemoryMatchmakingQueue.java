package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryMatchmakingQueue implements MatchmakingQueue {

    private final Map<Integer, Integer> waitingUserIdByGameTypeId = new HashMap<>();
    private final AtomicInteger nextSessionId = new AtomicInteger(1);

    @Override
    public synchronized GameStateDTO join(int userId, int gameTypeId, String initialBoardState) {
        Integer opponentUserId = waitingUserIdByGameTypeId.get(gameTypeId);
        if (opponentUserId == null) {
            waitingUserIdByGameTypeId.put(gameTypeId, userId);
            return null;
        }
        waitingUserIdByGameTypeId.remove(gameTypeId);
        return new GameStateDTO(nextSessionId.getAndIncrement(), gameTypeId, opponentUserId, userId,
                GameStatus.ACTIVE, opponentUserId, null, initialBoardState);
    }

    @Override
    public synchronized void cancel(int userId) {
        waitingUserIdByGameTypeId.values().removeIf(waitingUserId -> waitingUserId.equals(userId));
    }

    @Override
    public synchronized int countWaiting() {
        return waitingUserIdByGameTypeId.size();
    }
}
