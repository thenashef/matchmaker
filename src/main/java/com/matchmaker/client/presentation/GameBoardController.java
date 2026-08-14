package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.media.AudioClip;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GameBoardController {

    private static final int BOARD_SIZE = 8;
    private static final int TURN_TIMEOUT_SECONDS = 60;
    // Loaded defensively, not as a plain `= new AudioClip(...)` field initializer -- a null
    // resource (repackaging, a missing javafx-media native for this platform) would otherwise
    // throw inside <clinit>, which is an ExceptionInInitializerError that permanently prevents
    // this class from ever loading again, bricking the whole game board over a missing sound.
    private static final AudioClip TURN_SOUND = loadTurnSound();

    private static AudioClip loadTurnSound() {
        try {
            var resource = GameBoardController.class.getResource("turn.wav");
            return resource == null ? null : new AudioClip(resource.toExternalForm());
        } catch (Exception e) {
            System.err.println("Failed to load turn-notification sound: " + e.getMessage());
            return null;
        }
    }

    @FXML private Circle colorIndicator;
    @FXML private Label colorLabel;
    @FXML private Label statusLabel;
    @FXML private Label turnTimerLabel;
    @FXML private GridPane boardGrid;
    @FXML private Button submitButton;
    @FXML private Button clearButton;
    @FXML private Button backToLobbyButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;
    private final List<String> selectedPath = new ArrayList<>();
    private Timeline turnCountdown;

    public void init(GameClientService gameClientService, SceneNavigator navigator, GameStateDTO initialState) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        GameStateDTO latest = gameClientService.attachGameUpdateListener(this::applyState);
        applyState(latest != null ? latest : initialState);
    }

    private void applyState(GameStateDTO state) {
        this.currentState = state;
        selectedPath.clear();
        updateColorIndicator();
        renderBoard(state);
        updateStatusLabel(state);

        // ABANDONED (auto-forfeit via disconnect/turn-timeout, or an admin force-end) ends the
        // game exactly like FINISHED does from the board's point of view -- only a status of
        // ACTIVE means the game is actually still being played.
        boolean ended = state.getStatus() != GameStatus.ACTIVE;
        submitButton.setDisable(ended);
        clearButton.setDisable(ended);
        backToLobbyButton.setVisible(ended);

        if (isMyTurn() && !ended) {
            if (TURN_SOUND != null) {
                TURN_SOUND.play();
            }
            startTurnCountdown();
        } else {
            stopTurnCountdown();
        }
    }

    /** Purely a local display -- the server is authoritative on turn timeout (SessionWatchdog),
     *  and it doesn't know the server's exact TurnStartedAt, so this approximates by counting
     *  down from the moment this client learns it's its turn rather than the server's real start
     *  time. Reaching 0 here does nothing on its own; the game only actually ends once a
     *  SESSION_ABANDONED push arrives and applyState() runs again. */
    private void startTurnCountdown() {
        stopTurnCountdown();
        int[] secondsLeft = {TURN_TIMEOUT_SECONDS};
        turnTimerLabel.setText("Time remaining: " + secondsLeft[0] + "s");
        turnCountdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            secondsLeft[0] = Math.max(0, secondsLeft[0] - 1);
            turnTimerLabel.setText("Time remaining: " + secondsLeft[0] + "s");
        }));
        turnCountdown.setCycleCount(TURN_TIMEOUT_SECONDS);
        turnCountdown.play();
    }

    private void stopTurnCountdown() {
        if (turnCountdown != null) {
            turnCountdown.stop();
            turnCountdown = null;
        }
        turnTimerLabel.setText("");
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
        } else if (state.getStatus() == GameStatus.ABANDONED) {
            Integer winnerId = state.getWinnerId();
            int myId = gameClientService.getCurrentUser().getId();
            if (winnerId == null) {
                statusLabel.setText("Game ended -- both players disconnected.");
            } else if (winnerId == myId) {
                statusLabel.setText("You won -- your opponent disconnected or ran out of time.");
            } else {
                statusLabel.setText("Game over -- you disconnected or ran out of time.");
            }
        } else {
            statusLabel.setText(isMyTurn() ? "Your turn" : "Waiting for opponent...");
        }
    }

    private void updateColorIndicator() {
        boolean player1 = isPlayer1();
        colorIndicator.setFill(player1 ? Color.BLACK : Color.WHITE);
        colorLabel.setText("You are playing " + (player1 ? "Black" : "White"));
    }

    private void renderBoard(GameStateDTO state) {
        boardGrid.getChildren().clear();
        JSONObject board = new JSONObject(state.getBoardState());
        JSONObject pieces = board.getJSONObject("pieces");

        // Each player sees their own color at the bottom, like sitting on opposite sides of the
        // same physical board -- Black (player1) gets the un-flipped orientation (rank 1 at the
        // bottom); White (player2) gets the whole board rotated 180 degrees (both rank and file
        // reversed), not just mirrored vertically, so it looks like the same board turned around
        // rather than a reflection of it.
        boolean flip = !isPlayer1();

        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                boolean dark = (row + col) % 2 == 1;
                String algebraic = toAlgebraic(row, col);
                StackPane cell = buildCell(dark, algebraic, pieces);
                int displayRow = flip ? row : (BOARD_SIZE - 1 - row);
                int displayCol = flip ? (BOARD_SIZE - 1 - col) : col;
                boardGrid.add(cell, displayCol, displayRow);
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
        stopTurnCountdown();
        gameClientService.leaveGame();
        LobbyController controller = navigator.show("LobbyView.fxml",
                "MatchMaker - Lobby (" + gameClientService.getCurrentUser().getUsername() + ")");
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
        boolean pieceIsPlayer1 = Character.toLowerCase(piece) == 'b';
        return isPlayer1() == pieceIsPlayer1;
    }

    private boolean isPlayer1() {
        return currentState.getPlayer1Id() == gameClientService.getCurrentUser().getId();
    }

    private static String toAlgebraic(int row, int col) {
        char file = (char) ('a' + col);
        char rank = (char) ('1' + row);
        return "" + file + rank;
    }
}
