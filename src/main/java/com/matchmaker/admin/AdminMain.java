package com.matchmaker.admin;

import com.matchmaker.admin.communication.RmiJmsAdminConnection;
import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.admin.presentation.AdminLoginController;
import com.matchmaker.admin.presentation.SceneNavigator;
import javafx.application.Application;
import javafx.stage.Stage;

public class AdminMain extends Application {

    private static final String SERVER_HOST = "localhost";
    private static final int RMI_PORT = 1099;
    private static final int JMS_PORT = 61616;

    private RmiJmsAdminConnection adminConnection;
    private AdminClientService adminClientService;

    @Override
    public void start(Stage primaryStage) {
        adminConnection = new RmiJmsAdminConnection(SERVER_HOST, RMI_PORT, JMS_PORT);
        adminClientService = new AdminClientService(adminConnection);
        SceneNavigator navigator = new SceneNavigator(primaryStage);

        AdminLoginController controller = navigator.show("AdminLoginView.fxml", "MatchMaker Admin - Login");
        controller.init(adminClientService, navigator);
    }

    @Override
    public void stop() {
        if (adminClientService != null) {
            adminClientService.shutdown();
        }
        if (adminConnection != null) {
            adminConnection.close();
        }
    }

    public static void main(String[] args) {
        // See ServerMain.main() -- avoids a ~5s mDNS hostname-resolution stall on the first RMI call.
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        launch(args);
    }
}
