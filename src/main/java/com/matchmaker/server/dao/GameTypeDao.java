package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import java.util.List;
import java.util.Optional;

public interface GameTypeDao {
    List<GameTypeDTO> findAll();
    Optional<GameTypeDTO> findById(int id);
    GameTypeDTO insert(GameTypeDTO newGameType);
}
