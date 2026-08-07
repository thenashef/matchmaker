package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameSessionDao implements GameSessionDao {

    private final List<GameStateDTO> sessions = new ArrayList<>();

    public void addFinishedSession(GameStateDTO session) {
        sessions.add(session);
    }

    @Override
    public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
        List<GameStateDTO> result = new ArrayList<>();
        for (GameStateDTO session : sessions) {
            if (session.getPlayer1Id() == userId || session.getPlayer2Id() == userId) {
                result.add(session);
            }
        }
        return result;
    }
}
