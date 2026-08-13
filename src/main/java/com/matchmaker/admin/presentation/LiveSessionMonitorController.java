package com.matchmaker.admin.presentation;

import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.json.JSONObject;

public class LiveSessionMonitorController {

    private static final int BOARD_SIZE = 8;
    private static final int CELL_SIZE = 50;

    @FXML private Label titleLabel;
    @FXML private Label detailLabel;
    @FXML private GridPane boardGrid;
    @FXML private Button forceEndButton;
    @FXML private Button backButton;

    private AdminClientService adminClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;

    public void init(AdminClientService adminClientService, SceneNavigator navigator, GameStateDTO initialState) {
        this.adminClientService = adminClientService;
        this.navigator = navigator;

        // Subscribe before anything else -- a JMS Topic gives no retention to a late subscriber,
        // same rule the player client's GameClientService.enterGame() follows.
        adminClientService.monitorSession(initialState.getSessionId(), this::onEvent);
        applyState(initialState);
    }

    private void onEvent(GameEventDTO event) {
        if (event.getType() != GameEventType.MOVE_MADE && event.getType() != GameEventType.SESSION_FORCE_ENDED) {
            return;
        }
        Platform.runLater(() -> applyState(event.getGameState()));
    }

    private void applyState(GameStateDTO state) {
        this.currentState = state;
        titleLabel.setText("Live Game Monitor (Session #" + state.getSessionId() + ")");
        detailLabel.setText("Game type " + state.getGameTypeId() + " -- Player 1: " + state.getPlayer1Id()
                + ", Player 2: " + state.getPlayer2Id() + " -- Status: " + state.getStatus()
                + (state.getCurrentTurnUserId() != null ? " -- Turn: " + state.getCurrentTurnUserId() : ""));
        renderBoard(state);
        forceEndButton.setDisable(state.getStatus() != GameStatus.ACTIVE);
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
                boardGrid.add(cell, col, BOARD_SIZE - 1 - row);
            }
        }
    }

    private StackPane buildCell(boolean dark, String algebraic, JSONObject pieces) {
        StackPane cell = new StackPane();
        cell.setPrefSize(CELL_SIZE, CELL_SIZE);
        cell.setStyle("-fx-background-color: " + (dark ? "#5c4033" : "#e8d5b7") + ";");

        if (dark && pieces.has(algebraic)) {
            char piece = pieces.getString(algebraic).charAt(0);
            Circle disc = new Circle(CELL_SIZE / 2.0 - 8);
            disc.setFill(Character.toLowerCase(piece) == 'b' ? Color.BLACK : Color.WHITE);
            boolean king = Character.isUpperCase(piece);
            disc.setStroke(king ? Color.GOLD : Color.GRAY);
            disc.setStrokeWidth(king ? 3 : 1);
            cell.getChildren().add(disc);
        }
        // Read-only -- no click handler on any cell, admin never sends a move.
        return cell;
    }

    @FXML
    private void onForceEndSession() {
        forceEndButton.setDisable(true);
        adminClientService.forceEndSession(currentState.getSessionId(),
                () -> { },
                error -> {
                    forceEndButton.setDisable(false);
                    detailLabel.setText("Failed to force-end: " + error.getMessage());
                });
    }

    @FXML
    private void onBackToDashboard() {
        adminClientService.stopMonitoring();
        DashboardController controller = navigator.show("DashboardView.fxml", "MatchMaker Admin - Dashboard");
        controller.init(adminClientService, navigator);
    }

    private static String toAlgebraic(int row, int col) {
        char file = (char) ('a' + col);
        char rank = (char) ('1' + row);
        return "" + file + rank;
    }
}
