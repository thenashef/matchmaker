package com.matchmaker.server.game;

public interface GameEngine {

    String initialState();

    boolean isLegalMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    /**
     * Applies a move to the game state. The caller must have already confirmed the move is
     * legal via {@link #isLegalMove}; implementations are not required to re-validate it and
     * may throw an unrelated exception (or, for a sufficiently malformed move, behave
     * unpredictably) if given one that isn't.
     */
    String applyMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    GameResult checkWinner(String stateJson, boolean isPlayer1ToMoveNext);
}
