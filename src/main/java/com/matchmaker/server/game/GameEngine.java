package com.matchmaker.server.game;

public interface GameEngine {

    String initialBoardState();

    boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move);

    /**
     * Applies a move to the board. The caller must have already confirmed the move is legal
     * via {@link #isLegalMove}; implementations are not required to re-validate it and may
     * throw an unrelated exception (or, for a sufficiently malformed move, behave
     * unpredictably) if given one that isn't.
     */
    String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move);

    GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext);
}
