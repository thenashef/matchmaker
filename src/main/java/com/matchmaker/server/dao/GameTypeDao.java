package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import java.util.List;

public interface GameTypeDao {
    List<GameTypeDTO> findAll();
}
