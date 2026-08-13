package com.matchmaker.admin.presentation;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class SceneNavigator {

    // One fixed window size for every screen -- same reasoning as client.presentation's
    // SceneNavigator: no per-screen resizing, sized to comfortably fit the Dashboard's table.
    private static final double WINDOW_WIDTH = 720;
    private static final double WINDOW_HEIGHT = 600;

    private final Stage stage;

    public SceneNavigator(Stage stage) {
        this.stage = stage;
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);
        stage.setResizable(false);
    }

    public <T> T show(String fxmlResource, String title) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlResource));
            Parent root = loader.load();
            stage.setScene(new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT));
            stage.setTitle(title);
            stage.show();
            return loader.getController();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + fxmlResource, e);
        }
    }
}
