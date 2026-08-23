package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;

public class LobbyController {

    private static final String TILE_IDLE =
            "-fx-background-color: #f7f1e8; -fx-background-radius: 8; -fx-border-radius: 8; "
                    + "-fx-border-color: #c4b8a5; -fx-border-width: 1;";
    private static final String TILE_SELECTED =
            "-fx-background-color: #f7f1e8; -fx-background-radius: 8; -fx-border-radius: 8; "
                    + "-fx-border-color: gold; -fx-border-width: 3;";
    private static final int ICON_SIZE = 80;

    @FXML private Label welcomeLabel;
    @FXML private Label ratingLabel;
    @FXML private FlowPane gameTypeTiles;
    @FXML private Button joinButton;
    @FXML private Label statusLabel;
    @FXML private TableView<LeaderboardRow> leaderboardTable;
    @FXML private TableColumn<LeaderboardRow, Number> rankColumn;
    @FXML private TableColumn<LeaderboardRow, String> usernameColumn;
    @FXML private TableColumn<LeaderboardRow, Number> ratingColumn;
    @FXML private TableColumn<LeaderboardRow, String> recordColumn;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameTypeDTO selectedGameType;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;

        // A former opponent can rematch us at any time while we're logged in, not just while
        // we're on the Game Over screen -- every "idle" screen needs to be able to receive it.
        gameClientService.attachRematchListener(this::enterRematchedGame);

        UserDTO cached = gameClientService.getCurrentUser();
        welcomeLabel.setText("Welcome, " + cached.getUsername());
        ratingLabel.setText("");

        configureLeaderboardTable();

        gameClientService.getProfile(
                this::showHeaderFromProfile,
                error -> statusLabel.setText(error.getMessage()));
        gameClientService.listGameTypes(
                this::showGameTypes,
                error -> statusLabel.setText(error.getMessage()));
        gameClientService.listLeaderboard(
                this::showLeaderboard,
                error -> statusLabel.setText(error.getMessage()));
    }

    private void configureLeaderboardTable() {
        rankColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().rank()));
        usernameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().user().getUsername()));
        ratingColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().user().getRating()));
        recordColumn.setCellValueFactory(data -> new SimpleStringProperty(formatRecord(data.getValue().user())));

        rankColumn.setSortable(false);
        usernameColumn.setSortable(false);
        ratingColumn.setSortable(false);
        recordColumn.setSortable(false);
        leaderboardTable.setSortPolicy(table -> false);

        leaderboardTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(LeaderboardRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || gameClientService.getCurrentUser() == null
                        || item.user().getId() != gameClientService.getCurrentUser().getId()) {
                    setStyle("");
                } else {
                    setStyle("-fx-background-color: #fff3cd;");
                }
            }
        });
    }

    private void showHeaderFromProfile(UserDTO profile) {
        welcomeLabel.setText("Welcome, " + profile.getUsername());
        ratingLabel.setText("Rating " + profile.getRating());
    }

    private void showGameTypes(List<GameTypeDTO> gameTypes) {
        gameTypeTiles.getChildren().clear();
        selectedGameType = null;
        for (GameTypeDTO gameType : gameTypes) {
            gameTypeTiles.getChildren().add(buildGameTile(gameType));
        }
        if (!gameTypeTiles.getChildren().isEmpty()) {
            selectGameType(gameTypes.get(0), (VBox) gameTypeTiles.getChildren().get(0));
        }
    }

    private void showLeaderboard(List<UserDTO> users) {
        List<LeaderboardRow> rows = new ArrayList<>(users.size());
        for (int i = 0; i < users.size(); i++) {
            rows.add(new LeaderboardRow(i + 1, users.get(i)));
        }
        leaderboardTable.getItems().setAll(rows);

        UserDTO current = gameClientService.getCurrentUser();
        if (current != null && ratingLabel.getText().isEmpty()) {
            users.stream()
                    .filter(user -> user.getId() == current.getId())
                    .findFirst()
                    .ifPresent(self -> ratingLabel.setText("Rating " + self.getRating()));
        }
    }

    private VBox buildGameTile(GameTypeDTO gameType) {
        VBox tile = new VBox(8);
        tile.setAlignment(Pos.CENTER);
        tile.setPadding(new Insets(10));
        tile.setPrefSize(140, 180);
        tile.setMinSize(140, 180);
        tile.setMaxSize(140, 180);
        tile.setFillWidth(false);
        tile.setCursor(Cursor.HAND);
        tile.setStyle(TILE_IDLE);
        tile.getChildren().add(buildGameIcon(gameType));

        Label name = new Label(gameType.getName());
        name.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        Label size = new Label(gameType.getBoardRows() + "x" + gameType.getBoardCols());
        size.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        tile.getChildren().addAll(name, size);

        tile.setOnMouseClicked(event -> selectGameType(gameType, tile));
        return tile;
    }

    private Node buildGameIcon(GameTypeDTO gameType) {
        int rows = Math.max(gameType.getBoardRows(), 1);
        int cols = Math.max(gameType.getBoardCols(), 1);
        int cell = Math.max(6, ICON_SIZE / Math.max(rows, cols));
        int boardWidth = cell * cols;
        int boardHeight = cell * rows;
        boolean checkers = "Checkers".equalsIgnoreCase(gameType.getName());

        GridPane board = new GridPane();
        board.setMinSize(boardWidth, boardHeight);
        board.setPrefSize(boardWidth, boardHeight);
        board.setMaxSize(boardWidth, boardHeight);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                boolean dark = (row + col) % 2 == 1;
                StackPane square = new StackPane();
                square.setPrefSize(cell, cell);
                square.setMinSize(cell, cell);
                square.setMaxSize(cell, cell);
                square.setStyle("-fx-background-color: " + (dark ? "#5c4033" : "#e8d5b7") + ";");
                if (checkers && dark && (row < 3 || row >= rows - 3)) {
                    Circle disc = new Circle(Math.max(2, cell / 2.6));
                    disc.setFill(row < 3 ? Color.BLACK : Color.WHITE);
                    disc.setStroke(Color.GRAY);
                    square.getChildren().add(disc);
                }
                board.add(square, col, row);
            }
        }
        board.setStyle("-fx-border-color: #3e2723; -fx-border-width: 1;");
        return board;
    }

    private void selectGameType(GameTypeDTO gameType, VBox selectedTile) {
        selectedGameType = gameType;
        for (Node node : gameTypeTiles.getChildren()) {
            node.setStyle(node == selectedTile ? TILE_SELECTED : TILE_IDLE);
        }
    }

    @FXML
    private void onJoinQueue() {
        if (selectedGameType == null) {
            statusLabel.setText("Pick a game first.");
            return;
        }
        joinButton.setDisable(true);
        String username = gameClientService.getCurrentUser().getUsername();
        gameClientService.joinQueue(selectedGameType.getId(),
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

    private static String formatRecord(UserDTO user) {
        return user.getWins() + "/" + user.getLosses() + "/" + user.getDraws();
    }

    record LeaderboardRow(int rank, UserDTO user) {
    }
}
