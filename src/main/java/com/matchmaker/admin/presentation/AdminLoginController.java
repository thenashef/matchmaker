package com.matchmaker.admin.presentation;

import com.matchmaker.admin.logic.AdminClientService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class AdminLoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label statusLabel;
    @FXML private Button loginButton;

    private AdminClientService adminClientService;
    private SceneNavigator navigator;

    public void init(AdminClientService adminClientService, SceneNavigator navigator) {
        this.adminClientService = adminClientService;
        this.navigator = navigator;
    }

    @FXML
    private void onLogin() {
        loginButton.setDisable(true);
        adminClientService.login(usernameField.getText(), passwordField.getText(),
                user -> {
                    DashboardController controller = navigator.show("DashboardView.fxml", "MatchMaker Admin - Dashboard");
                    controller.init(adminClientService, navigator);
                },
                () -> {
                    loginButton.setDisable(false);
                    statusLabel.setText("This account is not an admin account.");
                },
                error -> {
                    loginButton.setDisable(false);
                    statusLabel.setText(error.getMessage());
                });
    }
}
