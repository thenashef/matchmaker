package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GameBoardController {

    private static final int BOARD_SIZE = 8;

    @FXML private Label statusLabel;
    @FXML private GridPane boardGrid;
    @FXML private Button submitButton;
    @FXML private Button clearButton;
    @FXML private Button backToLobbyButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;
    private final List<String> selectedPath = new ArrayList<>();

    public void init(GameClientService gameClientService, SceneNavigator navigator, GameStateDTO initialState) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        GameStateDTO latest = gameClientService.attachGameUpdateListener(this::applyState);
        applyState(latest != null ? latest : initialState);
    }

    private void applyState(GameStateDTO state) {
        this.currentState = state;
        selectedPath.clear();
        renderBoard(state);
        updateStatusLabel(state);

        boolean finished = state.getStatus() == GameStatus.FINISHED;
        submitButton.setDisable(finished);
        clearButton.setDisable(finished);
        backToLobbyButton.setVisible(finished);
    }

    private void updateStatusLabel(GameStateDTO state) {
        if (state.getStatus() == GameStatus.FINISHED) {
            Integer winnerId = state.getWinnerId();
            int myId = gameClientService.getCurrentUser().getId();
            if (winnerId == null) {
                statusLabel.setText("Game over -- draw.");
            } else if (winnerId == myId) {
                statusLabel.setText("You won!");
            } else {
                statusLabel.setText("You lost.");
            }
        } else {
            statusLabel.setText(isMyTurn() ? "Your turn" : "Waiting for opponent...");
        }
    }

    private void renderBoard(GameStateDTO state) {
        boardGrid.getChildren().clear();
        JSONObject board = new JSONObject(state.getBoardState());
        JSONObject pieces = board.getJSONObject("pieces");

        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                boolean dark = (row + col) % 2 == 1;
                String algebraic = toAlgebraic(row, col);
                StackPane cell = buildCell(dark, algebraic, pieces);
                // Rank 1 (row 0) renders at the bottom of the screen, rank 8 at the top --
                // standard board orientation, fixed regardless of which player is viewing
                // (see design doc's Out of scope: no per-player board flip).
                boardGrid.add(cell, col, BOARD_SIZE - 1 - row);
            }
        }
    }

    private StackPane buildCell(boolean dark, String algebraic, JSONObject pieces) {
        StackPane cell = new StackPane();
        cell.setPrefSize(60, 60);

        String backgroundColor = dark ? "#5c4033" : "#e8d5b7";
        String borderStyle = selectedPath.contains(algebraic) ? "-fx-border-color: gold; -fx-border-width: 3;" : "";
        cell.setStyle("-fx-background-color: " + backgroundColor + "; " + borderStyle);

        if (dark && pieces.has(algebraic)) {
            char piece = pieces.getString(algebraic).charAt(0);
            Circle disc = new Circle(20);
            disc.setFill(Character.toLowerCase(piece) == 'b' ? Color.BLACK : Color.WHITE);
            boolean king = Character.isUpperCase(piece);
            disc.setStroke(king ? Color.GOLD : Color.GRAY);
            disc.setStrokeWidth(king ? 3 : 1);
            cell.getChildren().add(disc);
        }

        if (dark) {
            cell.setOnMouseClicked(event -> onSquareClicked(algebraic));
        }
        return cell;
    }

    private void onSquareClicked(String algebraic) {
        if (currentState == null || currentState.getStatus() != GameStatus.ACTIVE || !isMyTurn()) {
            return;
        }
        if (selectedPath.isEmpty() && !isOwnPiece(algebraic)) {
            return;
        }
        selectedPath.add(algebraic);
        renderBoard(currentState);
    }

    @FXML
    private void onSubmitMove() {
        if (selectedPath.size() < 2) {
            statusLabel.setText("Select an origin and at least one destination square first.");
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("path", new JSONArray(selectedPath));
        submitButton.setDisable(true);

        gameClientService.makeMove(currentState.getSessionId(), payload.toString(),
                this::applyState,
                error -> {
                    submitButton.setDisable(false);
                    selectedPath.clear();
                    renderBoard(currentState);
                    statusLabel.setText(error.getMessage());
                });
    }

    @FXML
    private void onClearSelection() {
        selectedPath.clear();
        renderBoard(currentState);
    }

    @FXML
    private void onBackToLobby() {
        gameClientService.leaveGame();
        LobbyController controller = navigator.show("LobbyView.fxml", "MatchMaker - Lobby");
        controller.init(gameClientService, navigator);
    }

    private boolean isMyTurn() {
        Integer turnUserId = currentState.getCurrentTurnUserId();
        return turnUserId != null && turnUserId == gameClientService.getCurrentUser().getId();
    }

    private boolean isOwnPiece(String algebraic) {
        JSONObject board = new JSONObject(currentState.getBoardState());
        JSONObject pieces = board.getJSONObject("pieces");
        if (!pieces.has(algebraic)) {
            return false;
        }
        char piece = pieces.getString(algebraic).charAt(0);
        boolean isPlayer1 = currentState.getPlayer1Id() == gameClientService.getCurrentUser().getId();
        boolean pieceIsPlayer1 = Character.toLowerCase(piece) == 'b';
        return isPlayer1 == pieceIsPlayer1;
    }

    private static String toAlgebraic(int row, int col) {
        char file = (char) ('a' + col);
        char rank = (char) ('1' + row);
        return "" + file + rank;
    }
}
