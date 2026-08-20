package com.matchmaker.server.game;

import java.util.List;

public interface GameEngine {

    String initialState();

    boolean isLegalMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    List<String> legalContinuations(String stateJson, boolean isPlayer1Turn, String partialMovePayloadJson);

    String applyMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson);

    GameResult checkWinner(String stateJson, boolean isPlayer1ToMoveNext);
}
