package com.matchmaker.server.game;

public class CheckersEngine implements GameEngine {

    @Override
    public String initialBoardState() {
        return CheckersBoard.initial().toJson();
    }

    @Override
    public boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        throw new UnsupportedOperationException("isLegalMove not implemented yet -- see game-engine-implementation.md Task 4");
    }

    @Override
    public String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        throw new UnsupportedOperationException("applyMove not implemented yet -- see game-engine-implementation.md Task 7");
    }

    @Override
    public GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext) {
        throw new UnsupportedOperationException("checkWinner not implemented yet -- see game-engine-implementation.md Task 8");
    }
}
