package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.util.logging.Level;
import java.util.logging.Logger;

public class GameOverController {

    private static final Logger LOG = Logger.getLogger(GameOverController.class.getName());

    @FXML private Label resultLabel;
    @FXML private Label opponentLabel;
    @FXML private Label ratingLabel;
    @FXML private Button rematchButton;
    @FXML private Button backToLobbyButton;
    @FXML private Label statusLabel;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameStateDTO finalState;
    private boolean navigatedAway = false;

    public void init(GameClientService gameClientService, SceneNavigator navigator,
                      GameStateDTO finalState, int ratingBeforeGame) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        this.finalState = finalState;

        showResultText();

        ratingLabel.setText("Rating: " + ratingBeforeGame);
        gameClientService.getProfile(
                freshUser -> ratingLabel.setText(formatRatingDelta(ratingBeforeGame, freshUser.getRating())),
                error -> LOG.log(Level.WARNING, "Failed to refresh profile for the rating delta", error));

        opponentLabel.setText("");
        gameClientService.getOpponentProfile(finalState.getSessionId(),
                opponent -> opponentLabel.setText("Opponent: " + opponent.getUsername()),
                error -> LOG.log(Level.WARNING, "Failed to fetch opponent profile", error));

        gameClientService.attachRematchListener(this::onRematchArrivedFromOpponent);
    }

    private void showResultText() {
        Integer winnerId = finalState.getWinnerId();
        int myId = gameClientService.getCurrentUser().getId();
        if (winnerId == null) {
            resultLabel.setText("Draw / no winner.");
        } else if (winnerId == myId) {
            resultLabel.setText("You Win!");
        } else {
            resultLabel.setText("You Lost.");
        }
    }

    private static String formatRatingDelta(int before, int after) {
        int delta = after - before;
        String sign = delta >= 0 ? "+" : "";
        return "Rating: " + before + " -> " + after + " (" + sign + delta + ")";
    }

    @FXML
    private void onRematch() {
        rematchButton.setDisable(true);
        gameClientService.rematch(finalState.getSessionId(),
                this::goToGameBoard,
                error -> {
                    rematchButton.setDisable(false);
                    statusLabel.setText(friendlyRematchErrorMessage(error));
                });
    }

    private void onRematchArrivedFromOpponent(GameStateDTO newSession) {
        goToGameBoard(newSession);
    }

    private void goToGameBoard(GameStateDTO newSession) {
        if (navigatedAway) {
            return;
        }
        navigatedAway = true;
        gameClientService.attachRematchListener(null);
        GameBoardController controller = navigator.show("GameBoardView.fxml",
                "MatchMaker - Game (" + gameClientService.getCurrentUser().getUsername() + ")");
        controller.init(gameClientService, navigator, newSession);
    }

    @FXML
    private void onBackToLobby() {
        if (navigatedAway) {
            return;
        }
        navigatedAway = true;
        gameClientService.attachRematchListener(null);
        LobbyController controller = navigator.show("LobbyView.fxml",
                "MatchMaker - Lobby (" + gameClientService.getCurrentUser().getUsername() + ")");
        controller.init(gameClientService, navigator);
    }

    private static String friendlyRematchErrorMessage(Throwable error) {
        if (error instanceof AlreadyInGameException) {
            // Covers a few distinct server-side reasons (opponent not currently online, opponent
            // already in a different game, an earlier rematch of this session already ended) --
            // all point the player at the same recovery, so one generic message is accurate for all.
            return "Rematch isn't available right now -- try matchmaking a new game instead.";
        }
        return "Rematch failed -- please try again.";
    }
}
