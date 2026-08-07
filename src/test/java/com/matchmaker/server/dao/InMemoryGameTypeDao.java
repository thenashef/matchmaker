package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameTypeDao implements GameTypeDao {

    private final List<GameTypeDTO> gameTypes = new ArrayList<>();

    public void add(GameTypeDTO gameType) {
        gameTypes.add(gameType);
    }

    @Override
    public List<GameTypeDTO> findAll() {
        return new ArrayList<>(gameTypes);
    }
}
