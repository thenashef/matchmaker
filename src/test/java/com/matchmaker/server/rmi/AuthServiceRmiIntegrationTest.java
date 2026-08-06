package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceRmiIntegrationTest {

    private static final int TEST_PORT = 21099;

    private Registry registry;
    private AuthServiceImpl authServiceImpl;

    @BeforeEach
    void startRegistryAndBindService() throws Exception {
        registry = LocateRegistry.createRegistry(TEST_PORT);
        authServiceImpl = new AuthServiceImpl(new SessionManager());
        registry.rebind("AuthService", authServiceImpl);
    }

    @AfterEach
    void tearDownRegistry() {
        if (registry != null) {
            try { registry.unbind("AuthService"); } catch (Exception ignored) { }
        }
        if (authServiceImpl != null) {
            try { UnicastRemoteObject.unexportObject(authServiceImpl, true); } catch (Exception ignored) { }
        }
        if (registry != null) {
            try { UnicastRemoteObject.unexportObject(registry, true); } catch (Exception ignored) { }
        }
    }

    private AuthService lookupStub() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        return (AuthService) clientRegistry.lookup("AuthService");
    }

    @Test
    void login_throughRealRmiStub_returnsRealResult() throws Exception {
        AuthService stub = lookupStub();
        assertNotSame(authServiceImpl, stub);

        LoginResultDTO result = stub.login("test", "test1234");

        assertEquals("test", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());

        assertDoesNotThrow(() -> stub.keepAlive(result.getSessionToken()));
    }

    @Test
    void login_withBadCredentials_throwsAuthenticationExceptionAcrossRmi() throws Exception {
        AuthService stub = lookupStub();

        assertThrows(AuthenticationException.class, () -> stub.login("test", "wrongpassword"));
    }

    @Test
    void register_takenUsername_throwsUsernameTakenExceptionAcrossRmi() throws Exception {
        AuthService stub = lookupStub();

        assertThrows(UsernameTakenException.class, () -> stub.register("test", "whatever"));
    }
}
