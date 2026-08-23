package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

public class LobbyController {

    @FXML private ListView<GameTypeDTO> gameTypeList;
    @FXML private Button joinButton;
    @FXML private Label statusLabel;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;

        // A former opponent can rematch us at any time while we're logged in, not just while
        // we're on the Game Over screen -- every "idle" screen needs to be able to receive it.
        gameClientService.attachRematchListener(this::enterRematchedGame);

        gameTypeList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(GameTypeDTO item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null
                        ? null
                        : item.getName() + " (" + item.getBoardRows() + "x" + item.getBoardCols() + ")");
            }
        });

        gameClientService.listGameTypes(
                gameTypeList.getItems()::setAll,
                error -> statusLabel.setText(error.getMessage()));
    }

    @FXML
    private void onJoinQueue() {
        GameTypeDTO selected = gameTypeList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("Pick a game first.");
            return;
        }
        joinButton.setDisable(true);
        String username = gameClientService.getCurrentUser().getUsername();
        gameClientService.joinQueue(selected.getId(),
                matchedState -> {
                    GameBoardController controller = navigator.show("GameBoardView.fxml",
                            "MatchMaker - Game (" + username + ")");
                    controller.init(gameClientService, navigator, matchedState);
                },
                () -> {
                    MatchmakingWaitController controller = navigator.show("MatchmakingWaitView.fxml",
                            "MatchMaker - Waiting (" + username + ")");
                    controller.init(gameClientService, navigator);
                },
                error -> {
                    joinButton.setDisable(false);
                    statusLabel.setText(error instanceof AlreadyInGameException
                            ? "You're already in a game."
                            : error.getMessage());
                });
    }

    private void enterRematchedGame(GameStateDTO newSession) {
        GameBoardController controller = navigator.show("GameBoardView.fxml",
                "MatchMaker - Game (" + gameClientService.getCurrentUser().getUsername() + ")");
        controller.init(gameClientService, navigator, newSession);
    }
}
