package com.matchmaker.admin.presentation;

import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.common.dto.GameStateDTO;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.Callback;

public class DashboardController {

    @FXML private Label onlinePlayersLabel;
    @FXML private Label activeGamesLabel;
    @FXML private Label gamesTodayLabel;
    @FXML private Label openInQueueLabel;
    @FXML private TableView<GameStateDTO> sessionsTable;
    @FXML private TableColumn<GameStateDTO, Number> sessionIdColumn;
    @FXML private TableColumn<GameStateDTO, Number> gameTypeIdColumn;
    @FXML private TableColumn<GameStateDTO, Number> player1Column;
    @FXML private TableColumn<GameStateDTO, Number> player2Column;
    @FXML private TableColumn<GameStateDTO, String> turnColumn;
    @FXML private TableColumn<GameStateDTO, Void> monitorColumn;
    @FXML private Button refreshButton;
    @FXML private Button usersButton;
    @FXML private Button loggedInUsersButton;
    @FXML private Button logoutButton;
    @FXML private Label statusLabel;

    private AdminClientService adminClientService;
    private SceneNavigator navigator;

    public void init(AdminClientService adminClientService, SceneNavigator navigator) {
        this.adminClientService = adminClientService;
        this.navigator = navigator;

        sessionIdColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getSessionId()));
        gameTypeIdColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getGameTypeId()));
        player1Column.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getPlayer1Id()));
        player2Column.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getPlayer2Id()));
        turnColumn.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getCurrentTurnUserId() == null ? "-" : String.valueOf(data.getValue().getCurrentTurnUserId())));
        monitorColumn.setCellFactory(monitorButtonCellFactory());

        refresh();
    }

    @FXML
    private void onRefresh() {
        refresh();
    }

    private void refresh() {
        statusLabel.setText("");
        adminClientService.listActiveSessions(
                sessions -> sessionsTable.getItems().setAll(sessions),
                error -> statusLabel.setText(error.getMessage()));

        adminClientService.getDashboardStats(
                stats -> {
                    onlinePlayersLabel.setText("Online players: " + stats.getOnlinePlayers());
                    activeGamesLabel.setText("Active games: " + stats.getActiveGames());
                    gamesTodayLabel.setText("Games today: " + stats.getGamesToday());
                    openInQueueLabel.setText("Open in queue: " + stats.getOpenInQueue());
                },
                error -> statusLabel.setText(error.getMessage()));
    }

    @FXML
    private void onUsers() {
        UsersController controller = navigator.show("UsersView.fxml", "MatchMaker Admin - Users");
        controller.init(adminClientService, navigator);
    }

    @FXML
    private void onLoggedInUsers() {
        LoggedInUsersController controller = navigator.show("LoggedInUsersView.fxml",
                "MatchMaker Admin - Logged in users");
        controller.init(adminClientService, navigator);
    }

    @FXML
    private void onLogout() {
        logoutButton.setDisable(true);
        adminClientService.logout();
        AdminLoginController controller = navigator.show("AdminLoginView.fxml", "MatchMaker Admin - Login");
        controller.init(adminClientService, navigator);
    }

    private Callback<TableColumn<GameStateDTO, Void>, TableCell<GameStateDTO, Void>> monitorButtonCellFactory() {
        return column -> new TableCell<>() {
            private final Button monitorButton = new Button("Monitor");

            {
                monitorButton.setOnAction(event -> {
                    GameStateDTO session = getTableView().getItems().get(getIndex());
                    LiveSessionMonitorController controller = navigator.show(
                            "LiveSessionMonitorView.fxml", "MatchMaker Admin - Live Session Monitor");
                    controller.init(adminClientService, navigator, session);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : monitorButton);
            }
        };
    }
}
