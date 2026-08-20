package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
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
    @FXML private Button registerButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
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
    private void onRegister() {
        setControlsDisabled(true);
        gameClientService.register(usernameField.getText(), passwordField.getText(),
                user -> {
                    setControlsDisabled(false);
                    statusLabel.setTextFill(Color.web("#2e7d32"));
                    statusLabel.setText("Registered -- now click Login.");
                },
                error -> {
                    setControlsDisabled(false);
                    statusLabel.setTextFill(Color.web("#b00020"));
                    statusLabel.setText(friendlyRegisterErrorMessage(error));
                });
    }

    private static String friendlyRegisterErrorMessage(Throwable error) {
        if (error instanceof InvalidRegistrationException || error instanceof UsernameTakenException) {
            return error.getMessage();
        }
        return "Registration failed -- please try again.";
    }

    private void setControlsDisabled(boolean disabled) {
        loginButton.setDisable(disabled);
        registerButton.setDisable(disabled);
    }
}
