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

public class SignUpController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Label statusLabel;
    @FXML private Button signUpButton;
    @FXML private Button backButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator, String username) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        if (username != null && !username.isBlank()) {
            usernameField.setText(username);
        }
    }

    @FXML
    private void onSignUp() {
        String password = passwordField.getText();
        String confirmPassword = confirmPasswordField.getText();
        if (password == null || !password.equals(confirmPassword)) {
            showError("Passwords don't match.");
            return;
        }

        setControlsDisabled(true);
        gameClientService.register(usernameField.getText(), password,
                user -> {
                    LoginController controller = navigator.show("LoginView.fxml", "MatchMaker - Login");
                    controller.init(gameClientService, navigator,
                            "Account created -- now click Login.", true);
                },
                error -> {
                    setControlsDisabled(false);
                    showError(friendlyRegisterErrorMessage(error));
                });
    }

    @FXML
    private void onBackToLogin() {
        LoginController controller = navigator.show("LoginView.fxml", "MatchMaker - Login");
        controller.init(gameClientService, navigator);
    }

    private void showError(String message) {
        statusLabel.setTextFill(Color.web("#b00020"));
        statusLabel.setText(message);
    }

    private static String friendlyRegisterErrorMessage(Throwable error) {
        if (error instanceof InvalidRegistrationException || error instanceof UsernameTakenException) {
            return error.getMessage();
        }
        return "Sign up failed -- please try again.";
    }

    private void setControlsDisabled(boolean disabled) {
        signUpButton.setDisable(disabled);
        backButton.setDisable(disabled);
        usernameField.setDisable(disabled);
        passwordField.setDisable(disabled);
        confirmPasswordField.setDisable(disabled);
    }
}
