package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

public class MatchmakingWaitController {

    @FXML private Label statusLabel;
    @FXML private Button cancelButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        init(gameClientService, navigator, null);
    }

    public void init(GameClientService gameClientService, SceneNavigator navigator, String gameName) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        statusLabel.setText(gameName == null || gameName.isBlank()
                ? "Waiting for an opponent..."
                : "Waiting for a " + gameName + " opponent...");
        // A former opponent can rematch us while we're waiting on this screen too.
        gameClientService.attachRematchListener(this::enterRematchedGame);
    }

    private void enterRematchedGame(GameStateDTO newSession) {
        GameSceneRouter.showGame(navigator, gameClientService, newSession);
    }

    @FXML
    private void onCancel() {
        cancelButton.setDisable(true);
        gameClientService.cancelQueue(
                () -> {
                    LobbyController controller = navigator.show("LobbyView.fxml",
                            "MatchMaker - Lobby (" + gameClientService.getCurrentUser().getUsername() + ")");
                    controller.init(gameClientService, navigator);
                },
                error -> {
                    cancelButton.setDisable(false);
                    statusLabel.setText(error.getMessage());
                });
    }
}
