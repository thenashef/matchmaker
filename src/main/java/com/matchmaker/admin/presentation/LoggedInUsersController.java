package com.matchmaker.admin.presentation;

import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.common.dto.UserDTO;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

public class LoggedInUsersController {

    @FXML private TableView<UserDTO> onlineUsersTable;
    @FXML private TableColumn<UserDTO, String> usernameColumn;
    @FXML private TableColumn<UserDTO, String> adminColumn;
    @FXML private TableColumn<UserDTO, Number> ratingColumn;
    @FXML private TableColumn<UserDTO, String> recordColumn;
    @FXML private Button refreshButton;
    @FXML private Label statusLabel;

    private AdminClientService adminClientService;
    private SceneNavigator navigator;

    public void init(AdminClientService adminClientService, SceneNavigator navigator) {
        this.adminClientService = adminClientService;
        this.navigator = navigator;

        usernameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getUsername()));
        adminColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isAdmin() ? "Yes" : ""));
        ratingColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getRating()));
        recordColumn.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getWins() + "/" + data.getValue().getLosses() + "/" + data.getValue().getDraws()));

        reload();
    }

    @FXML
    private void onRefresh() {
        reload();
    }

    private void reload() {
        statusLabel.setText("");
        adminClientService.listOnlineUsers(
                users -> onlineUsersTable.getItems().setAll(users),
                error -> statusLabel.setText(error.getMessage()));
    }

    @FXML
    private void onBackToDashboard() {
        DashboardController controller = navigator.show("DashboardView.fxml", "MatchMaker Admin - Dashboard");
        controller.init(adminClientService, navigator);
    }
}
