package com.matchmaker.admin.presentation;

import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.MoveDTO;
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
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LiveSessionMonitorController {

    private static final Logger LOG = Logger.getLogger(LiveSessionMonitorController.class.getName());

    private static final int BOARD_SIZE = 8;
    private static final int CELL_SIZE = 50;
    private static final String EMPTY_BOARD_JSON = "{\"rows\":8,\"cols\":8,\"pieces\":{}}";

    @FXML private Label titleLabel;
    @FXML private Label detailLabel;
    @FXML private GridPane boardGrid;
    @FXML private Button forceEndButton;
    @FXML private Button exportLogButton;
    @FXML private Button backButton;

    private AdminClientService adminClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;

    public void init(AdminClientService adminClientService, SceneNavigator navigator, GameStateDTO initialState) {
        this.adminClientService = adminClientService;
        this.navigator = navigator;

        adminClientService.monitorSession(initialState.getSessionId(), this::onEvent);
        applyState(initialState);
    }

    private void onEvent(GameEventDTO event) {
        if (event.getType() != GameEventType.MOVE_MADE && event.getType() != GameEventType.SESSION_FORCE_ENDED
                && event.getType() != GameEventType.SESSION_ABANDONED) {
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

    private void renderEightsSummary(JSONObject board) {
        JSONArray hand1 = board.optJSONArray("hand1");
        JSONArray hand2 = board.optJSONArray("hand2");
        int size1 = hand1 == null ? 0 : hand1.length();
        int size2 = hand2 == null ? 0 : hand2.length();
        JSONArray discard = board.optJSONArray("discard");
        String top = (discard == null || discard.isEmpty()) ? "-" : discard.getString(discard.length() - 1);
        String namedSuit = "-";
        if (board.has("namedSuit") && !board.isNull("namedSuit")) {
            String suit = board.getString("namedSuit");
            if (!suit.isBlank()) {
                namedSuit = suit;
            }
        }
        detailLabel.setText(detailLabel.getText()
                + " -- Eights  P1 " + size1 + " cards vs P2 " + size2 + " cards -- discard " + top
                + " -- named suit " + namedSuit);
    }

    private void renderBoard(GameStateDTO state) {
        boardGrid.getChildren().clear();
        JSONObject board = new JSONObject(state.getBoardState() == null ? EMPTY_BOARD_JSON : state.getBoardState());
        if ("eights".equalsIgnoreCase(board.optString("game"))) {
            renderEightsSummary(board);
            return;
        }
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
    private void onExportLog() {
        int sessionId = currentState.getSessionId();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Move Log");
        fileChooser.setInitialFileName("session-" + sessionId + "-moves.txt");
        Window owner = exportLogButton.getScene().getWindow();
        // showSaveDialog() runs a nested event loop, during which a pushed applyState() could
        // reassign currentState -- capture the session id above rather than re-reading it after.
        File file = fileChooser.showSaveDialog(owner);
        if (file == null) {
            return;
        }

        exportLogButton.setDisable(true);
        adminClientService.listMoves(sessionId,
                moves -> {
                    exportLogButton.setDisable(false);
                    writeMovesToFile(file, moves);
                },
                error -> {
                    exportLogButton.setDisable(false);
                    detailLabel.setText("Failed to export log: " + error.getMessage());
                });
    }

    private void writeMovesToFile(File file, List<MoveDTO> moves) {
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8)) {
            for (MoveDTO move : moves) {
                writer.println("#" + move.getMoveNumber() + " user=" + move.getUserId() + " " + move.getPayload());
            }
            if (writer.checkError()) {
                throw new IOException("PrintWriter reported a write error");
            }
            detailLabel.setText("Exported " + moves.size() + " moves to " + file.getName());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write move log to " + file, e);
            detailLabel.setText("Failed to write log file: " + e.getMessage());
        }
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
