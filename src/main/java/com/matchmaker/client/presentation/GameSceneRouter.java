package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import org.json.JSONObject;

final class GameSceneRouter {

    private GameSceneRouter() {
    }

    static void showGame(SceneNavigator navigator, GameClientService gameClientService, GameStateDTO state) {
        String title = "MatchMaker - Game (" + gameClientService.getCurrentUser().getUsername() + ")";
        try {
            if (isEights(state)) {
                EightsController controller = navigator.show("EightsView.fxml", title);
                controller.init(gameClientService, navigator, state);
            } else {
                GameBoardController controller = navigator.show("GameBoardView.fxml", title);
                controller.init(gameClientService, navigator, state);
            }
        } catch (RuntimeException e) {
            // If the game screen fails to open, the server still has an ACTIVE session.
            // Resign so neither player is stuck "already in a game".
            gameClientService.resign(state.getSessionId(), ignored -> { }, err -> { });
            gameClientService.leaveGame();
            throw e;
        }
    }

    static boolean isEights(GameStateDTO state) {
        if (state == null || state.getBoardState() == null || state.getBoardState().isBlank()) {
            return false;
        }
        return "eights".equalsIgnoreCase(new JSONObject(state.getBoardState()).optString("game"));
    }
}
