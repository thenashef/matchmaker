package com.matchmaker.admin.presentation;

import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.common.dto.GameTypeDTO;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

public class AddGameTypeController {

    @FXML private TextField nameField;
    @FXML private TextArea descriptionField;
    @FXML private TextField minPlayersField;
    @FXML private TextField maxPlayersField;
    @FXML private TextField boardRowsField;
    @FXML private TextField boardColsField;
    @FXML private Button submitButton;
    @FXML private Button cancelButton;
    @FXML private Label statusLabel;

    private AdminClientService adminClientService;
    private SceneNavigator navigator;

    public void init(AdminClientService adminClientService, SceneNavigator navigator) {
        this.adminClientService = adminClientService;
        this.navigator = navigator;
    }

    @FXML
    private void onSubmit() {
        GameTypeDTO newGameType;
        try {
            newGameType = new GameTypeDTO(0, nameField.getText(), descriptionField.getText(),
                    Integer.parseInt(minPlayersField.getText()), Integer.parseInt(maxPlayersField.getText()),
                    Integer.parseInt(boardRowsField.getText()), Integer.parseInt(boardColsField.getText()));
        } catch (NumberFormatException e) {
            statusLabel.setText("Min/Max Players and Board Rows/Cols must be numbers.");
            return;
        }

        submitButton.setDisable(true);
        adminClientService.addGameType(newGameType,
                created -> goToDashboard(),
                error -> {
                    submitButton.setDisable(false);
                    statusLabel.setText(error.getMessage());
                });
    }

    @FXML
    private void onCancel() {
        goToDashboard();
    }

    private void goToDashboard() {
        DashboardController controller = navigator.show("DashboardView.fxml", "MatchMaker Admin - Dashboard");
        controller.init(adminClientService, navigator);
    }
}
