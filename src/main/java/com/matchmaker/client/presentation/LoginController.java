package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label statusLabel;
    @FXML private Button loginButton;
    @FXML private Button signUpButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        init(gameClientService, navigator, null, false);
    }

    public void init(GameClientService gameClientService, SceneNavigator navigator,
                     String statusMessage, boolean success) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        if (statusMessage != null && !statusMessage.isBlank()) {
            statusLabel.setTextFill(Color.web(success ? "#2e7d32" : "#b00020"));
            statusLabel.setText(statusMessage);
        }
    }

    @FXML
    private void onLogin() {
        setControlsDisabled(true);
        gameClientService.login(usernameField.getText(), passwordField.getText(),
                user -> {
                    LobbyController controller = navigator.show("LobbyView.fxml",
                            "MatchMaker - Lobby (" + user.getUsername() + ")");
                    controller.init(gameClientService, navigator);
                },
                error -> {
                    setControlsDisabled(false);
                    statusLabel.setTextFill(Color.web("#b00020"));
                    statusLabel.setText(error.getMessage());
                });
    }

    @FXML
    private void onSignUp() {
        SignUpController controller = navigator.show("SignUpView.fxml", "MatchMaker - Sign up");
        controller.init(gameClientService, navigator, usernameField.getText());
    }

    private void setControlsDisabled(boolean disabled) {
        loginButton.setDisable(disabled);
        signUpButton.setDisable(disabled);
    }
}
