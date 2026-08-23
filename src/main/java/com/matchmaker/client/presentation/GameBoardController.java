package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.GameTiming;
import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.media.AudioClip;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameBoardController {

    private static final Logger LOG = Logger.getLogger(GameBoardController.class.getName());

    private static final int BOARD_SIZE = 8;
    private static final int TURN_TIMEOUT_SECONDS = GameTiming.TURN_SECONDS;
    private static final String EMPTY_BOARD_JSON = "{\"rows\":8,\"cols\":8,\"pieces\":{}}";
    private static final AudioClip TURN_SOUND = loadTurnSound();

    private static AudioClip loadTurnSound() {
        try {
            var resource = GameBoardController.class.getResource("turn.wav");
            return resource == null ? null : new AudioClip(resource.toExternalForm());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load turn-notification sound", e);
            return null;
        }
    }

    @FXML private Circle colorIndicator;
    @FXML private Label colorLabel;
    @FXML private Label statusLabel;
    @FXML private Label turnTimerLabel;
    @FXML private GridPane boardGrid;
    @FXML private Button resignButton;
    @FXML private ListView<String> chatListView;
    @FXML private TextField chatInputField;
    @FXML private Button chatSendButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;
    private final List<String> selectedPath = new ArrayList<>();
    private Set<String> highlightedSquares = Set.of();
    private boolean highlightsLoading = false;
    private int highlightRequestId;
    private Timeline turnCountdown;
    private boolean navigatedAway = false;
    private int ratingBeforeGame;

    public void init(GameClientService gameClientService, SceneNavigator navigator, GameStateDTO initialState) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        resignButton.managedProperty().bind(resignButton.visibleProperty());

        ratingBeforeGame = gameClientService.getCurrentUser().getRating();
        gameClientService.getProfile(
                freshUser -> ratingBeforeGame = freshUser.getRating(),
                error -> LOG.log(Level.WARNING, "Failed to refresh profile at game start", error));

        GameStateDTO latest = gameClientService.attachGameUpdateListener(this::applyState);
        applyState(latest != null ? latest : initialState);
        if (navigatedAway) {
            return;
        }

        gameClientService.getChatHistory(currentState.getSessionId(),
                history -> {
                    history.forEach(this::appendChatMessage);
                    gameClientService.attachChatListener(this::appendChatMessage);
                },
                error -> {
                    LOG.log(Level.WARNING, "Failed to load chat history", error);
                    gameClientService.attachChatListener(this::appendChatMessage);
                });
    }

    private void appendChatMessage(ChatMessageDTO message) {
        boolean mine = message.getUserId() == gameClientService.getCurrentUser().getId();
        chatListView.getItems().add((mine ? "You: " : "Opponent: ") + message.getContent());
        chatListView.scrollTo(chatListView.getItems().size() - 1);
    }

    @FXML
    private void onSendChat() {
        String content = chatInputField.getText();
        if (content == null || content.isBlank()) {
            return;
        }
        chatInputField.clear();
        gameClientService.sendChatMessage(currentState.getSessionId(), content,
                () -> { },
                error -> {
                    LOG.log(Level.WARNING, "Failed to send chat message", error);
                    chatListView.getItems().add("(message not sent -- please try again)");
                });
    }

    private void applyState(GameStateDTO state) {
        this.currentState = state;
        selectedPath.clear();
        highlightRequestId++;
        highlightsLoading = false;
        updateColorIndicator();
        renderBoard(state);
        updateStatusLabel(state);

        boolean ended = state.getStatus() != GameStatus.ACTIVE;
        resignButton.setVisible(!ended);
        chatInputField.setDisable(ended);
        chatSendButton.setDisable(ended);

        if (ended) {
            stopTurnCountdown();
            highlightedSquares = Set.of();
            goToGameOverIfNotAlready(state);
        } else if (isMyTurn()) {
            if (TURN_SOUND != null) {
                TURN_SOUND.play();
            }
            startTurnCountdown();
            refreshHighlights();
        } else {
            stopTurnCountdown();
            highlightedSquares = Set.of();
        }
    }

    private void goToGameOverIfNotAlready(GameStateDTO finalState) {
        if (navigatedAway) {
            return;
        }
        navigatedAway = true;
        GameOverController controller = navigator.show("GameOverView.fxml", "MatchMaker - Game Over");
        controller.init(gameClientService, navigator, finalState, ratingBeforeGame);
        gameClientService.leaveGame();
    }

    private void refreshHighlights() {
        int requestId = ++highlightRequestId;
        highlightsLoading = true;
        JSONObject payload = new JSONObject();
        payload.put("path", new JSONArray(selectedPath));
        gameClientService.legalContinuations(currentState.getSessionId(), payload.toString(),
                continuations -> {
                    if (requestId != highlightRequestId) {
                        return;
                    }
                    if (continuations.isEmpty() && selectedPath.size() >= 2) {
                        submitCurrentSelection();
                        return;
                    }
                    highlightsLoading = false;
                    Set<String> next = new HashSet<>();
                    for (String continuationJson : continuations) {
                        JSONArray path = new JSONObject(continuationJson).getJSONArray("path");
                        if (path.length() > 0) {
                            next.add(path.getString(path.length() - 1));
                        }
                    }
                    highlightedSquares = next;
                    renderBoard(currentState);
                },
                error -> {
                    if (requestId != highlightRequestId) {
                        return;
                    }
                    highlightsLoading = false;
                    LOG.log(Level.WARNING, "Failed to refresh legal-move highlights", error);
                });
    }

    private void submitCurrentSelection() {
        JSONObject payload = new JSONObject();
        payload.put("path", new JSONArray(selectedPath));
        gameClientService.makeMove(currentState.getSessionId(), payload.toString(),
                this::applyState,
                error -> {
                    selectedPath.clear();
                    statusLabel.setText(friendlyMoveErrorMessage(error));
                    refreshHighlights();
                });
    }

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
                statusLabel.setText("Game ended -- no winner.");
            } else if (winnerId == myId) {
                statusLabel.setText("Game ended -- you won.");
            } else {
                statusLabel.setText("Game ended -- you lost.");
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
        JSONObject board = new JSONObject(state.getBoardState() == null ? EMPTY_BOARD_JSON : state.getBoardState());
        JSONObject pieces = board.getJSONObject("pieces");

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
        String borderStyle;
        if (selectedPath.contains(algebraic)) {
            borderStyle = "-fx-border-color: gold; -fx-border-width: 3;";
        } else if (highlightedSquares.contains(algebraic)) {
            borderStyle = "-fx-border-color: #4caf50; -fx-border-width: 3;";
        } else {
            borderStyle = "";
        }
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
        if (highlightsLoading) {
            return;
        }
        if (!highlightedSquares.contains(algebraic)) {
            if (!selectedPath.isEmpty()) {
                selectedPath.clear();
                renderBoard(currentState);
                refreshHighlights();
            }
            return;
        }
        selectedPath.add(algebraic);
        renderBoard(currentState);
        refreshHighlights();
    }

    private static String friendlyMoveErrorMessage(Throwable error) {
        if (error instanceof IllegalMoveException) {
            return "That move isn't legal -- try a different one.";
        }
        if (error instanceof NotYourTurnException) {
            return "It's not your turn.";
        }
        if (error instanceof NotParticipantException) {
            return "You're not a participant in this game.";
        }
        return "Move failed -- please try again.";
    }

    @FXML
    private void onResign() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Forfeit this game? Your opponent will be credited the win.", ButtonType.YES, ButtonType.NO);
        confirmation.showAndWait().ifPresent(response -> {
            if (response != ButtonType.YES || currentState.getStatus() != GameStatus.ACTIVE) {
                return;
            }
            resignButton.setDisable(true);
            gameClientService.resign(currentState.getSessionId(),
                    this::applyState,
                    error -> {
                        if (currentState.getStatus() == GameStatus.ACTIVE) {
                            resignButton.setDisable(false);
                            statusLabel.setText("Failed to forfeit -- please try again.");
                        }
                    });
        });
    }

    private boolean isMyTurn() {
        Integer turnUserId = currentState.getCurrentTurnUserId();
        return turnUserId != null && turnUserId == gameClientService.getCurrentUser().getId();
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
