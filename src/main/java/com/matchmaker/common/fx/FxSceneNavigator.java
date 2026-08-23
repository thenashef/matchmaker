package com.matchmaker.common.fx;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public final class FxSceneNavigator {

    private FxSceneNavigator() {
    }

    public static Stage configure(Stage stage, double width, double height) {
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setResizable(false);
        return stage;
    }

    public static <T> T show(Stage stage, Class<?> resourceOwner, String fxmlResource, String title,
                             double width, double height) {
        try {
            FXMLLoader loader = new FXMLLoader(resourceOwner.getResource(fxmlResource));
            Parent root = loader.load();
            stage.setScene(new Scene(root, width, height));
            stage.setTitle(title);
            stage.show();
            return loader.getController();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + fxmlResource, e);
        }
    }
}
