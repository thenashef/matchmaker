package com.matchmaker.admin.presentation;

import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.common.dto.UserDTO;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;

public class UsersController {

    @FXML private TableView<UserDTO> usersTable;
    @FXML private TableColumn<UserDTO, Number> idColumn;
    @FXML private TableColumn<UserDTO, String> usernameColumn;
    @FXML private TableColumn<UserDTO, String> adminColumn;
    @FXML private TableColumn<UserDTO, Number> ratingColumn;
    @FXML private TableColumn<UserDTO, String> recordColumn;
    @FXML private TextField newUsernameField;
    @FXML private PasswordField newPasswordField;
    @FXML private CheckBox newIsAdminCheckBox;
    @FXML private Button createButton;
    @FXML private Button refreshButton;
    @FXML private Label statusLabel;

    private AdminClientService adminClientService;
    private SceneNavigator navigator;

    public void init(AdminClientService adminClientService, SceneNavigator navigator) {
        this.adminClientService = adminClientService;
        this.navigator = navigator;

        idColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getId()));
        usernameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getUsername()));
        adminColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isAdmin() ? "Yes" : ""));
        ratingColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().getRating()));
        recordColumn.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getWins() + "/" + data.getValue().getLosses() + "/" + data.getValue().getDraws()));

        reloadTable();
    }

    @FXML
    private void onRefresh() {
        statusLabel.setText("");
        reloadTable();
    }

    private void reloadTable() {
        adminClientService.listUsers(
                users -> usersTable.getItems().setAll(users),
                error -> showError(error.getMessage()));
    }

    @FXML
    private void onCreate() {
        String username = newUsernameField.getText();
        String password = newPasswordField.getText();
        boolean isAdmin = newIsAdminCheckBox.isSelected();

        if (isAdmin) {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "Create '" + username + "' as an admin? Admins can read every session's chat "
                            + "and force-end any game.", ButtonType.YES, ButtonType.NO);
            confirmation.showAndWait().ifPresent(response -> {
                if (response == ButtonType.YES) {
                    submitCreate(username, password, true);
                }
            });
            return;
        }
        submitCreate(username, password, false);
    }

    private void submitCreate(String username, String password, boolean isAdmin) {
        createButton.setDisable(true);
        adminClientService.createUser(username, password, isAdmin,
                created -> {
                    createButton.setDisable(false);
                    newUsernameField.clear();
                    newPasswordField.clear();
                    newIsAdminCheckBox.setSelected(false);
                    statusLabel.setTextFill(Color.web("#2e7d32"));
                    statusLabel.setText("Created '" + created.getUsername() + "'.");
                    reloadTable();
                },
                error -> {
                    createButton.setDisable(false);
                    showError(error.getMessage());
                });
    }

    private void showError(String message) {
        statusLabel.setTextFill(Color.web("#b00020"));
        statusLabel.setText(message);
    }

    @FXML
    private void onBackToDashboard() {
        DashboardController controller = navigator.show("DashboardView.fxml", "MatchMaker Admin - Dashboard");
        controller.init(adminClientService, navigator);
    }
}
