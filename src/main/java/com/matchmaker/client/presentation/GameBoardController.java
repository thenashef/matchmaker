package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    @FXML private Button backToLobbyButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;
    private final List<String> selectedPath = new ArrayList<>();
    private Set<String> highlightedSquares = Set.of();
    private boolean highlightsLoading = false;
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
        // A fresh, authoritative state has arrived -- any in-flight highlight/submit lock from
        // before this point is moot now, whatever it was guarding against is already resolved.
        highlightsLoading = false;
        updateColorIndicator();
        renderBoard(state);
        updateStatusLabel(state);

        // ABANDONED (auto-forfeit via disconnect/turn-timeout, or an admin force-end) ends the
        // game exactly like FINISHED does from the board's point of view -- only a status of
        // ACTIVE means the game is actually still being played.
        boolean ended = state.getStatus() != GameStatus.ACTIVE;
        backToLobbyButton.setVisible(ended);

        if (isMyTurn() && !ended) {
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

    /** Queries the server for every legal way to extend selectedPath by one more step, and
     *  highlights the resulting squares -- called at the start of a turn (selectedPath is empty
     *  at that point, so this highlights legal origins, already narrowed to capture-only pieces
     *  if a capture is mandatory) and again after every accepted click. Purely a UX aid: the
     *  server's makeMove() remains the real authority on what's actually legal.
     *
     *  <p>An empty result for a non-empty selectedPath means it can't be extended any further --
     *  since every square in selectedPath was itself only ever accepted because a prior call here
     *  said it was legal, that means selectedPath is now a complete, legal move, and it's
     *  auto-submitted immediately rather than waiting for a separate "Submit" action. */
    private void refreshHighlights() {
        highlightsLoading = true;
        JSONObject payload = new JSONObject();
        payload.put("path", new JSONArray(selectedPath));
        gameClientService.legalContinuations(currentState.getSessionId(), payload.toString(),
                continuations -> {
                    if (continuations.isEmpty() && selectedPath.size() >= 2) {
                        // Deliberately leaving highlightsLoading == true here rather than
                        // resetting it -- clicks need to stay locked out through the submit that's
                        // about to happen too, not just through this query. It's cleared once a
                        // fresh state actually arrives, in applyState().
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
                    highlightsLoading = false;
                    System.err.println("Failed to refresh legal-move highlights: " + error.getMessage());
                });
    }

    private void submitCurrentSelection() {
        JSONObject payload = new JSONObject();
        payload.put("path", new JSONArray(selectedPath));
        gameClientService.makeMove(currentState.getSessionId(), payload.toString(),
                this::applyState,
                error -> {
                    // Unlike a manual submit, an auto-submit rejection almost certainly means
                    // selectedPath was built against state that's since changed underneath it
                    // (a race with the opponent's move) -- clearing and re-querying resyncs with
                    // whatever's actually legal now, rather than leaving stale, no-longer-valid
                    // picks on screen.
                    selectedPath.clear();
                    statusLabel.setText(friendlyMoveErrorMessage(error));
                    refreshHighlights();
                });
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
        // Locked out while a refresh is in flight -- otherwise a click landing in the gap
        // between "selectedPath just grew" and "the new highlight set arrived" would still be
        // checked against the stale, pre-click highlight set (which, right after selecting an
        // origin, still legitimately contains that same origin square as a valid pick from
        // before it was selected), letting the same square be added to the path twice.
        if (highlightsLoading) {
            return;
        }
        // highlightedSquares is exactly "what the server says is legal to click right now" --
        // a click outside it isn't a legal continuation, so it resets the in-progress selection
        // back to the start of the turn instead of being silently ignored or requiring a
        // separate "Clear Selection" action.
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

    private boolean isPlayer1() {
        return currentState.getPlayer1Id() == gameClientService.getCurrentUser().getId();
    }

    private static String toAlgebraic(int row, int col) {
        char file = (char) ('a' + col);
        char rank = (char) ('1' + row);
        return "" + file + rank;
    }
}
