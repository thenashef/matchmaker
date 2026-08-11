package com.matchmaker.server.game;

public interface GameEngine {

    String initialBoardState();

    boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move);

    String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move);

    GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext);
}
