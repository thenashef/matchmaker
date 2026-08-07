package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;

import java.util.List;

public interface GameSessionDao {
    List<GameStateDTO> findFinishedSessionsForUser(int userId);
}
