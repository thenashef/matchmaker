package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryGameTypeDao implements GameTypeDao {

    private final List<GameTypeDTO> gameTypes = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public void add(GameTypeDTO gameType) {
        gameTypes.add(gameType);
    }

    @Override
    public List<GameTypeDTO> findAll() {
        return new ArrayList<>(gameTypes);
    }

    @Override
    public Optional<GameTypeDTO> findById(int id) {
        return gameTypes.stream().filter(gameType -> gameType.getId() == id).findFirst();
    }

    @Override
    public GameTypeDTO insert(GameTypeDTO newGameType) {
        GameTypeDTO created = new GameTypeDTO(nextId.getAndIncrement(), newGameType.getName(),
                newGameType.getDescription(), newGameType.getMinPlayers(), newGameType.getMaxPlayers(),
                newGameType.getBoardRows(), newGameType.getBoardCols());
        gameTypes.add(created);
        return created;
    }
}
