package com.matchmaker.server;

import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.common.rmi.PlayerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ServerMainTest {

    private static final int TEST_PORT = 21100;
    private static final int TEST_JMS_PORT = 21106;

    private ServerMain.Started started;

    @BeforeEach
    void startServer() throws Exception {
        started = ServerMain.startWithImpls(TEST_PORT, TEST_JMS_PORT);
    }

    @AfterEach
    void tearDownServer() {
        if (started != null) {
            started.close();
        }
    }

    @Test
    void start_bindsAllThreeServicesInRealRmiRegistry() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);

        Object authStub = clientRegistry.lookup("AuthService");
        Object playerStub = clientRegistry.lookup("PlayerService");
        Object adminStub = clientRegistry.lookup("AdminService");

        assertInstanceOf(AuthService.class, authStub);
        assertInstanceOf(PlayerService.class, playerStub);
        assertInstanceOf(AdminService.class, adminStub);

        assertNotSame(started.authService(), authStub);
        assertNotSame(started.playerService(), playerStub);
        assertNotSame(started.adminService(), adminStub);
    }
}
