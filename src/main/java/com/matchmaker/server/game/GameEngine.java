package com.matchmaker.server.game;

import java.util.List;

public interface GameEngine {

    String initialState();

    boolean isLegalMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    /**
     * Given a partial move (possibly empty, e.g. no origin picked yet), returns every legal way
     * to extend it by exactly one more step, each as a full move-payload JSON string in the same
     * opaque shape {@link #isLegalMove}/{@link #applyMove} already use -- never a bare token like
     * a square name, so this interface stays exactly as game-agnostic as the rest of it. An empty
     * result means the given partial move is already complete (or illegal) and can't be extended
     * further.
     */
    List<String> legalContinuations(String stateJson, boolean isPlayer1Turn, String partialMovePayloadJson);

    /**
     * Applies a move to the game state. The caller must have already confirmed the move is
     * legal via {@link #isLegalMove}; implementations are not required to re-validate it and
     * may throw an unrelated exception (or, for a sufficiently malformed move, behave
     * unpredictably) if given one that isn't.
     */
    String applyMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    GameResult checkWinner(String stateJson, boolean isPlayer1ToMoveNext);
}
