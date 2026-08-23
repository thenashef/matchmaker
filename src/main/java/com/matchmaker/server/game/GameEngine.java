package com.matchmaker.server.game;

import java.util.List;

public interface GameEngine {

    String initialState();

    boolean isLegalMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    List<String> legalContinuations(String stateJson, boolean isPlayer1Turn, String partialMovePayloadJson);

    String applyMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    GameResult checkWinner(String stateJson, boolean isPlayer1ToMoveNext);

    /** True when the player who just moved still has to act (e.g. play a just-drawn legal card). */
    default boolean retainsTurn(String stateJson) {
        return false;
    }
}
