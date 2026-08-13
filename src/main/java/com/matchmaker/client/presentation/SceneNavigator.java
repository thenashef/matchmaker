package com.matchmaker.client.presentation;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class SceneNavigator {

    // One fixed window size for every screen, set once here rather than letting each screen's
    // own preferred size resize the Stage on every navigation -- sized to comfortably fit the
    // largest screen (Game Board: a 480x480 board plus its header/footer controls) so nothing
    // in any screen ever gets clipped, and switching screens never changes the window's size.
    private static final double WINDOW_WIDTH = 560;
    private static final double WINDOW_HEIGHT = 700;

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
