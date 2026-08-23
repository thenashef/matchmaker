package com.matchmaker.client;

import com.matchmaker.client.communication.RmiJmsServerConnection;
import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.client.presentation.LoginController;
import com.matchmaker.client.presentation.SceneNavigator;
import javafx.application.Application;
import javafx.stage.Stage;

public class ClientMain extends Application {

    private static final String SERVER_HOST = "localhost";
    private static final int RMI_PORT = 1099;
    private static final int JMS_PORT = 61616;

    private RmiJmsServerConnection serverConnection;
    private GameClientService gameClientService;

    @Override
    public void start(Stage primaryStage) {
        serverConnection = new RmiJmsServerConnection(SERVER_HOST, RMI_PORT, JMS_PORT);
        gameClientService = new GameClientService(serverConnection);
        SceneNavigator navigator = new SceneNavigator(primaryStage);

        LoginController controller = navigator.show("LoginView.fxml", "MatchMaker - Login");
        controller.init(gameClientService, navigator);
    }

    @Override
    public void stop() {
        if (gameClientService != null) {
            gameClientService.shutdown();
        }
        if (serverConnection != null) {
            serverConnection.close();
        }
    }

    public static void main(String[] args) {
        // See ServerMain.main() -- avoids a ~5s mDNS hostname-resolution stall on the first RMI
        // call, which otherwise happens inside start() before the login window can even appear.
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        launch(args);
    }
}
