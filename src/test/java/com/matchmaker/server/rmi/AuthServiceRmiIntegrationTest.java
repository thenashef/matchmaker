package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryUserDao;
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
        authServiceImpl = new AuthServiceImpl(new SessionManager(), new InMemoryUserDao());
        registry.rebind("AuthService", authServiceImpl);
    }

    @AfterEach
    void tearDownRegistry() throws Exception {
        registry.unbind("AuthService");
        UnicastRemoteObject.unexportObject(authServiceImpl, true);
        UnicastRemoteObject.unexportObject(registry, true);
    }

    @Test
    void registerThenLogin_throughRealRmiStub_returnsRealResult() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");

        stub.register("frank", "password123");
        LoginResultDTO result = stub.login("frank", "password123");

        assertEquals("frank", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withBadCredentials_throwsAuthenticationExceptionAcrossRmi() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");
        stub.register("grace", "password123");

        assertThrows(AuthenticationException.class, () -> stub.login("grace", "wrongpassword"));
    }

    @Test
    void register_takenUsername_throwsUsernameTakenExceptionAcrossRmi() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");
        stub.register("henry", "password123");

        assertThrows(UsernameTakenException.class, () -> stub.register("henry", "different-password"));
    }
}
