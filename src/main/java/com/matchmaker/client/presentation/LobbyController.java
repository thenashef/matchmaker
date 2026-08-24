package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameHistoryDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameStatus;
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
import javafx.scene.shape.Rectangle;

import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private Label welcomeLabel;
    @FXML private Label ratingLabel;
    @FXML private Button logoutButton;
    @FXML private FlowPane gameTypeTiles;
    @FXML private Label statusLabel;
    @FXML private TableView<LeaderboardRow> leaderboardTable;
    @FXML private TableColumn<LeaderboardRow, Number> rankColumn;
    @FXML private TableColumn<LeaderboardRow, String> usernameColumn;
    @FXML private TableColumn<LeaderboardRow, Number> ratingColumn;
    @FXML private TableColumn<LeaderboardRow, String> recordColumn;
    @FXML private TableView<GameHistoryDTO> historyTable;
    @FXML private TableColumn<GameHistoryDTO, String> historyGameColumn;
    @FXML private TableColumn<GameHistoryDTO, String> historyOpponentColumn;
    @FXML private TableColumn<GameHistoryDTO, String> historyResultColumn;
    @FXML private TableColumn<GameHistoryDTO, String> historyWhenColumn;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private boolean joining;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;

        gameClientService.attachRematchListener(this::enterRematchedGame);

        UserDTO cached = gameClientService.getCurrentUser();
        welcomeLabel.setText("Welcome, " + cached.getUsername());
        ratingLabel.setText("");

        configureLeaderboardTable();
        configureHistoryTable();

        gameClientService.getProfile(
                this::showHeaderFromProfile,
                error -> statusLabel.setText(error.getMessage()));
        gameClientService.listGameTypes(
                this::showGameTypes,
                error -> statusLabel.setText(error.getMessage()));
        gameClientService.listLeaderboard(
                this::showLeaderboard,
                error -> statusLabel.setText(error.getMessage()));
        gameClientService.getHistory(
                rows -> historyTable.getItems().setAll(rows),
                error -> statusLabel.setText(error.getMessage()));
    }

    @FXML
    private void onLogout() {
        logoutButton.setDisable(true);
        gameClientService.logout();
        LoginController controller = navigator.show("LoginView.fxml", "MatchMaker - Login");
        controller.init(gameClientService, navigator);
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

    private void configureHistoryTable() {
        historyGameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getGameTypeName()));
        historyOpponentColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getOpponentUsername()));
        historyResultColumn.setCellValueFactory(data -> new SimpleStringProperty(formatHistoryResult(data.getValue())));
        historyWhenColumn.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getEndTime() == null ? "-" : HISTORY_TIME.format(data.getValue().getEndTime())));
    }

    private void showHeaderFromProfile(UserDTO profile) {
        welcomeLabel.setText("Welcome, " + profile.getUsername());
        ratingLabel.setText(formatStats(profile));
    }

    private void showGameTypes(List<GameTypeDTO> gameTypes) {
        gameTypeTiles.getChildren().clear();
        joining = false;
        for (GameTypeDTO gameType : gameTypes) {
            gameTypeTiles.getChildren().add(buildGameTile(gameType));
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
                    .ifPresent(self -> ratingLabel.setText(formatStats(self)));
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
        String subtitle = "Crazy Eights".equalsIgnoreCase(gameType.getName())
                ? "52 cards"
                : gameType.getBoardRows() + "x" + gameType.getBoardCols();
        Label size = new Label(subtitle);
        size.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        tile.getChildren().addAll(name, size);

        tile.setOnMouseClicked(event -> joinGame(gameType, tile));
        return tile;
    }

    private Node buildGameIcon(GameTypeDTO gameType) {
        if ("Crazy Eights".equalsIgnoreCase(gameType.getName())) {
            return buildCardGameIcon();
        }
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

    private static Node buildCardGameIcon() {
        StackPane icon = new StackPane();
        icon.setMinSize(ICON_SIZE, ICON_SIZE);
        icon.setPrefSize(ICON_SIZE, ICON_SIZE);
        icon.setMaxSize(ICON_SIZE, ICON_SIZE);
        Rectangle back = new Rectangle(36, 50);
        back.setArcWidth(8);
        back.setArcHeight(8);
        back.setFill(Color.web("#1b4f72"));
        back.setStroke(Color.web("#154360"));
        back.setRotate(-14);
        back.setTranslateX(-8);
        Rectangle front = new Rectangle(36, 50);
        front.setArcWidth(8);
        front.setArcHeight(8);
        front.setFill(Color.web("#f7f1e8"));
        front.setStroke(Color.web("#922b21"));
        front.setRotate(10);
        front.setTranslateX(8);
        icon.getChildren().addAll(back, front);
        return icon;
    }

    private void joinGame(GameTypeDTO gameType, VBox selectedTile) {
        if (joining) {
            return;
        }
        joining = true;
        statusLabel.setText("");
        for (Node node : gameTypeTiles.getChildren()) {
            node.setStyle(node == selectedTile ? TILE_SELECTED : TILE_IDLE);
            node.setDisable(true);
        }
        String username = gameClientService.getCurrentUser().getUsername();
        gameClientService.joinQueue(gameType.getId(),
                matchedState -> GameSceneRouter.showGame(navigator, gameClientService, matchedState),
                () -> {
                    MatchmakingWaitController controller = navigator.show("MatchmakingWaitView.fxml",
                            "MatchMaker - Waiting (" + username + ")");
                    controller.init(gameClientService, navigator, gameType.getName());
                },
                error -> {
                    joining = false;
                    for (Node node : gameTypeTiles.getChildren()) {
                        node.setDisable(false);
                        node.setStyle(TILE_IDLE);
                    }
                    statusLabel.setText(error instanceof AlreadyInGameException
                            ? "You're already in a game."
                            : error.getMessage());
                });
    }

    private void enterRematchedGame(GameStateDTO newSession) {
        GameSceneRouter.showGame(navigator, gameClientService, newSession);
    }

    private static String formatRecord(UserDTO user) {
        return user.getWins() + "/" + user.getLosses() + "/" + user.getDraws();
    }

    private static String formatStats(UserDTO user) {
        int games = user.getWins() + user.getLosses() + user.getDraws();
        if (games == 0) {
            return "Starting rating " + user.getRating() + "   No games yet";
        }
        return "Rating " + user.getRating() + "   W/L/D " + formatRecord(user) + "   " + games + " games";
    }

    private String formatHistoryResult(GameHistoryDTO row) {
        if (row.getStatus() == GameStatus.ABANDONED) {
            return "Abandoned";
        }
        UserDTO current = gameClientService.getCurrentUser();
        if (row.getWinnerId() == null) {
            return "Draw";
        }
        if (current != null && row.getWinnerId() == current.getId()) {
            return "Win";
        }
        return "Loss";
    }

    record LeaderboardRow(int rank, UserDTO user) {
    }
}
