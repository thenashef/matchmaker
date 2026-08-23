package com.matchmaker.client.presentation;

import com.matchmaker.common.fx.FxSceneNavigator;
import javafx.stage.Stage;

public class SceneNavigator {

    private static final double WINDOW_WIDTH = 860;
    private static final double WINDOW_HEIGHT = 720;

    private final Stage stage;

    public SceneNavigator(Stage stage) {
        this.stage = FxSceneNavigator.configure(stage, WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    public <T> T show(String fxmlResource, String title) {
        return FxSceneNavigator.show(stage, getClass(), fxmlResource, title, WINDOW_WIDTH, WINDOW_HEIGHT);
    }
}
