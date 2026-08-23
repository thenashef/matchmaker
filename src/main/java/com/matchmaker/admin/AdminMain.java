package com.matchmaker.admin;

import com.matchmaker.admin.communication.RmiJmsAdminConnection;
import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.admin.presentation.AdminLoginController;
import com.matchmaker.admin.presentation.SceneNavigator;
import com.matchmaker.common.net.LoopbackHosts;
import com.matchmaker.common.net.ServicePorts;
import javafx.application.Application;
import javafx.stage.Stage;

public class AdminMain extends Application {

    private static final String SERVER_HOST = ServicePorts.LOCAL_HOST;
    private static final int RMI_PORT = ServicePorts.RMI;
    private static final int JMS_PORT = ServicePorts.JMS;

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
        LoopbackHosts.pinToLoopback();
        launch(args);
    }
}
