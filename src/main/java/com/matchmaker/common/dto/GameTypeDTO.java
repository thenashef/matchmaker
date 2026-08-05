package com.matchmaker.common.dto;

import java.io.Serializable;

public class GameTypeDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String name;
    private final String description;
    private final int minPlayers;
    private final int maxPlayers;
    private final int boardRows;
    private final int boardCols;

    public GameTypeDTO(int id, String name, String description,
                        int minPlayers, int maxPlayers, int boardRows, int boardCols) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.boardRows = boardRows;
        this.boardCols = boardCols;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getBoardRows() { return boardRows; }
    public int getBoardCols() { return boardCols; }
}
