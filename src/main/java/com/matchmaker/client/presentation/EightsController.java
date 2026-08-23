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
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.AudioClip;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EightsController {

    private static final Logger LOG = Logger.getLogger(EightsController.class.getName());
    private static final int TURN_TIMEOUT_SECONDS = GameTiming.TURN_SECONDS;
    private static final AudioClip TURN_SOUND = loadTurnSound();

    private static AudioClip loadTurnSound() {
        try {
            var resource = EightsController.class.getResource("turn.wav");
            return resource == null ? null : new AudioClip(resource.toExternalForm());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load turn-notification sound", e);
            return null;
        }
    }

    @FXML private Label statusLabel;
    @FXML private Label turnTimerLabel;
    @FXML private Label opponentCountLabel;
    @FXML private HBox opponentHandBox;
    @FXML private StackPane discardSlot;
    @FXML private Label namedSuitLabel;
    @FXML private StackPane drawSlot;
    @FXML private Label drawCountLabel;
    @FXML private Button drawButton;
    @FXML private HBox suitChooserBox;
    @FXML private HBox yourHandBox;
    @FXML private Button resignButton;
    @FXML private ListView<String> chatListView;
    @FXML private TextField chatInputField;
    @FXML private Button chatSendButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;
    private Timeline turnCountdown;
    private boolean navigatedAway = false;
    private int ratingBeforeGame;
    private final Set<String> playableCards = new HashSet<>();
    private boolean drawLegal;
    private int highlightRequestId;
    private String pendingEightCode;

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
        pendingEightCode = null;
        hideSuitChooser();
        renderTable(state);
        updateStatusLabel(state);

        boolean ended = state.getStatus() != GameStatus.ACTIVE;
        resignButton.setVisible(!ended);
        chatInputField.setDisable(ended);
        chatSendButton.setDisable(ended);

        if (ended) {
            playableCards.clear();
            drawLegal = false;
            drawButton.setDisable(true);
            stopTurnCountdown();
            goToGameOverIfNotAlready(state);
        } else if (isMyTurn()) {
            if (TURN_SOUND != null) {
                TURN_SOUND.play();
            }
            startTurnCountdown();
            refreshHighlights();
        } else {
            playableCards.clear();
            drawLegal = false;
            drawButton.setDisable(true);
            stopTurnCountdown();
            renderTable(state);
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
        gameClientService.legalContinuations(currentState.getSessionId(), "{}",
                continuations -> {
                    if (requestId != highlightRequestId) {
                        return;
                    }
                    playableCards.clear();
                    drawLegal = false;
                    for (String continuationJson : continuations) {
                        JSONObject move = new JSONObject(continuationJson);
                        String action = move.optString("action");
                        if ("draw".equals(action)) {
                            drawLegal = true;
                        } else if ("play".equals(action)) {
                            playableCards.add(move.getString("card"));
                        }
                    }
                    drawButton.setDisable(!drawLegal);
                    renderTable(currentState);
                },
                error -> {
                    if (requestId != highlightRequestId) {
                        return;
                    }
                    LOG.log(Level.WARNING, "Failed to refresh legal-move highlights", error);
                    drawButton.setDisable(true);
                });
    }

    private void renderTable(GameStateDTO state) {
        JSONObject board = new JSONObject(state.getBoardState());
        boolean player1 = isPlayer1();
        JSONArray myHand = board.getJSONArray(player1 ? "hand1" : "hand2");
        JSONArray opponentHand = board.getJSONArray(player1 ? "hand2" : "hand1");
        JSONArray draw = board.optJSONArray("draw");
        JSONArray discard = board.optJSONArray("discard");
        int drawCount = draw == null ? 0 : draw.length();
        String topDiscard = (discard == null || discard.isEmpty()) ? null : discard.getString(discard.length() - 1);

        opponentCountLabel.setText("Opponent — " + opponentHand.length() + " card"
                + (opponentHand.length() == 1 ? "" : "s"));
        renderOpponentBacks(opponentHand.length());
        showFaceCard(discardSlot, topDiscard, false, 100, 140);
        namedSuitLabel.setText(namedSuitCaption(board));
        showCardBack(drawSlot, drawCount == 0, 100, 140);
        drawCountLabel.setText(drawCount + " remaining");

        yourHandBox.getChildren().clear();
        for (int i = 0; i < myHand.length(); i++) {
            String code = myHand.getString(i);
            boolean playable = isMyTurn() && playableCards.contains(code);
            StackPane card = showFaceCard(new StackPane(), code, !playable, 72, 104);
            if (playable) {
                card.setCursor(javafx.scene.Cursor.HAND);
                card.setOnMouseClicked(event -> onCardClicked(code));
            }
            yourHandBox.getChildren().add(card);
        }
    }

    private static String namedSuitCaption(JSONObject board) {
        if (board.has("namedSuit") && !board.isNull("namedSuit")) {
            String suit = board.getString("namedSuit");
            if (!suit.isBlank()) {
                return "Named suit: " + suitSymbol(suit);
            }
        }
        return "";
    }

    private void renderOpponentBacks(int count) {
        opponentHandBox.getChildren().clear();
        int shown = Math.min(count, 12);
        for (int i = 0; i < shown; i++) {
            StackPane back = new StackPane();
            showCardBack(back, false, 48, 68);
            opponentHandBox.getChildren().add(back);
        }
    }

    private void onCardClicked(String code) {
        if (currentState == null || currentState.getStatus() != GameStatus.ACTIVE || !isMyTurn()) {
            return;
        }
        if (!playableCards.contains(code)) {
            return;
        }
        if (code.startsWith("8") && !code.startsWith("10")) {
            pendingEightCode = code;
            suitChooserBox.setVisible(true);
            suitChooserBox.setManaged(true);
            statusLabel.setText("Choose the next suit.");
            return;
        }
        submitPlay(new JSONObject().put("action", "play").put("card", code).toString());
    }

    @FXML private void onSuitHearts() { submitNamedEight("H"); }
    @FXML private void onSuitDiamonds() { submitNamedEight("D"); }
    @FXML private void onSuitClubs() { submitNamedEight("C"); }
    @FXML private void onSuitSpades() { submitNamedEight("S"); }

    private void submitNamedEight(String suit) {
        if (pendingEightCode == null) {
            return;
        }
        String card = pendingEightCode;
        pendingEightCode = null;
        hideSuitChooser();
        submitPlay(new JSONObject().put("action", "play").put("card", card).put("suit", suit).toString());
    }

    @FXML
    private void onDraw() {
        if (currentState == null || currentState.getStatus() != GameStatus.ACTIVE || !isMyTurn() || !drawLegal) {
            return;
        }
        hideSuitChooser();
        pendingEightCode = null;
        drawButton.setDisable(true);
        submitPlay(new JSONObject().put("action", "draw").toString());
    }

    private void submitPlay(String payload) {
        drawButton.setDisable(true);
        gameClientService.makeMove(currentState.getSessionId(), payload,
                this::applyState,
                error -> {
                    if (currentState.getStatus() == GameStatus.ACTIVE) {
                        drawButton.setDisable(!drawLegal || !isMyTurn());
                    }
                    statusLabel.setText(friendlyMoveErrorMessage(error));
                });
    }

    private void hideSuitChooser() {
        suitChooserBox.setVisible(false);
        suitChooserBox.setManaged(false);
    }

    private static StackPane showFaceCard(StackPane slot, String code, boolean muted, double width, double height) {
        slot.getChildren().clear();
        Rectangle back = new Rectangle(width, height);
        back.setArcWidth(12);
        back.setArcHeight(12);
        if (code == null) {
            back.setFill(Color.TRANSPARENT);
            back.setStroke(Color.web("#c4b8a5"));
            back.getStrokeDashArray().addAll(6.0, 6.0);
            Label empty = new Label("—");
            empty.setStyle("-fx-font-size: 22px; -fx-text-fill: #c4b8a5;");
            slot.getChildren().addAll(back, empty);
            return slot;
        }
        boolean highlighted = !muted;
        back.setFill(Color.web(muted ? "#f4efe6" : "#fffdf8"));
        back.setStroke(Color.web(highlighted ? "#d4a017" : "#3e2723"));
        back.setStrokeWidth(highlighted ? 3 : 1);
        String suit = code.substring(code.length() - 1);
        String rank = code.substring(0, code.length() - 1);
        boolean red = "H".equals(suit) || "D".equals(suit);
        String color = red ? "#c0392b" : "#1c2833";
        VBox face = new VBox(2);
        face.setAlignment(Pos.CENTER);
        Label rankLabel = new Label(rank);
        rankLabel.setStyle("-fx-font-size: " + Math.max(16, width / 3.2) + "px; -fx-font-weight: bold; -fx-text-fill: "
                + color + ";");
        Label suitLabel = new Label(suitSymbol(suit));
        suitLabel.setStyle("-fx-font-size: " + Math.max(14, width / 3.6) + "px; -fx-text-fill: " + color + ";");
        face.getChildren().addAll(rankLabel, suitLabel);
        slot.getChildren().addAll(back, face);
        return slot;
    }

    private static void showCardBack(StackPane slot, boolean empty, double width, double height) {
        slot.getChildren().clear();
        Rectangle back = new Rectangle(width, height);
        back.setArcWidth(12);
        back.setArcHeight(12);
        if (empty) {
            back.setFill(Color.TRANSPARENT);
            back.setStroke(Color.web("#c4b8a5"));
            back.getStrokeDashArray().addAll(6.0, 6.0);
            Label emptyLabel = new Label("—");
            emptyLabel.setStyle("-fx-font-size: 22px; -fx-text-fill: #c4b8a5;");
            slot.getChildren().addAll(back, emptyLabel);
            return;
        }
        back.setFill(Color.web("#1b4f72"));
        back.setStroke(Color.web("#154360"));
        Rectangle inner = new Rectangle(width - 16, height - 16);
        inner.setArcWidth(8);
        inner.setArcHeight(8);
        inner.setFill(Color.TRANSPARENT);
        inner.setStroke(Color.web("#f7f1e8"));
        inner.setStrokeWidth(2);
        slot.getChildren().addAll(back, inner);
    }

    private static String suitSymbol(String suit) {
        return switch (suit) {
            case "H" -> "♥";
            case "D" -> "♦";
            case "C" -> "♣";
            case "S" -> "♠";
            default -> suit;
        };
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
            statusLabel.setText(isMyTurn() ? "Your turn -- play a matching card or draw." : "Waiting for opponent...");
        }
    }

    private static String friendlyMoveErrorMessage(Throwable error) {
        if (error instanceof IllegalMoveException) {
            return "That play isn't legal right now.";
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
}
