package com.matchmaker.client.presentation;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class SceneNavigator {

    private final Stage stage;

    public SceneNavigator(Stage stage) {
        this.stage = stage;
    }

    public <T> T show(String fxmlResource, String title) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlResource));
            Parent root = loader.load();
            stage.setScene(new Scene(root));
            stage.setTitle(title);
            stage.show();
            return loader.getController();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + fxmlResource, e);
        }
    }
}
