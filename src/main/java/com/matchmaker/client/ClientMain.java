package com.matchmaker.client;

import com.matchmaker.client.communication.RmiJmsServerConnection;
import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.client.presentation.LoginController;
import com.matchmaker.client.presentation.SceneNavigator;
import com.matchmaker.common.net.LoopbackHosts;
import com.matchmaker.common.net.ServicePorts;
import javafx.application.Application;
import javafx.stage.Stage;

public class ClientMain extends Application {

    private static final String SERVER_HOST = ServicePorts.LOCAL_HOST;
    private static final int RMI_PORT = ServicePorts.RMI;
    private static final int JMS_PORT = ServicePorts.JMS;

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
        LoopbackHosts.pinToLoopback();
        launch(args);
    }
}
