package com.matchmaker.server;

import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.common.rmi.PlayerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ServerMainTest {

    private static final int TEST_PORT = 21100;

    private ServerMain.Started started;

    @BeforeEach
    void startServer() throws Exception {
        started = ServerMain.startWithImpls(TEST_PORT);
    }

    @AfterEach
    void tearDownRegistry() {
        Registry registry = started != null ? started.registry() : null;

        if (registry != null) {
            try { registry.unbind("AuthService"); } catch (Exception ignored) { }
            try { registry.unbind("PlayerService"); } catch (Exception ignored) { }
            try { registry.unbind("AdminService"); } catch (Exception ignored) { }
        }
        if (started != null && started.authService() != null) {
            try { UnicastRemoteObject.unexportObject(started.authService(), true); } catch (Exception ignored) { }
        }
        if (started != null && started.playerService() != null) {
            try { UnicastRemoteObject.unexportObject(started.playerService(), true); } catch (Exception ignored) { }
        }
        if (started != null && started.adminService() != null) {
            try { UnicastRemoteObject.unexportObject(started.adminService(), true); } catch (Exception ignored) { }
        }
        if (registry != null) {
            try { UnicastRemoteObject.unexportObject(registry, true); } catch (Exception ignored) { }
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

        // Proves these are real RMI stubs obtained through the registry, not the local impl objects.
        assertNotSame(started.authService(), authStub);
        assertNotSame(started.playerService(), playerStub);
        assertNotSame(started.adminService(), adminStub);
    }
}
