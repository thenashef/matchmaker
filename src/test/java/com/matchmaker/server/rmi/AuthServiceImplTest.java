package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceImplTest {

    private AuthServiceImpl authService;

    @BeforeEach
    void createAuthService() throws Exception {
        authService = new AuthServiceImpl(new SessionManager());
    }

    @AfterEach
    void unexportAuthService() {
        if (authService != null) {
            try { UnicastRemoteObject.unexportObject(authService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void login_withCorrectCredentials_returnsTokenAndUser() throws Exception {
        LoginResultDTO result = authService.login("test", "test1234");

        assertEquals("test", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withWrongPassword_throwsAuthenticationException() throws Exception {
        assertThrows(AuthenticationException.class, () -> authService.login("test", "wrongpassword"));
    }

    @Test
    void login_withUnknownUsername_throwsAuthenticationException() throws Exception {
        assertThrows(AuthenticationException.class, () -> authService.login("nobody", "whatever"));
    }

    @Test
    void register_withTakenUsername_throwsUsernameTakenException() throws Exception {
        assertThrows(UsernameTakenException.class, () -> authService.register("test", "whatever"));
    }

    @Test
    void register_withNewUsername_throwsUnsupportedOperationException() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> authService.register("newuser", "whatever"));
    }

    @Test
    void keepAlive_withValidToken_doesNotThrow() throws Exception {
        LoginResultDTO result = authService.login("test", "test1234");

        assertDoesNotThrow(() -> authService.keepAlive(result.getSessionToken()));
    }

    @Test
    void keepAlive_withInvalidToken_throwsAuthenticationException() throws Exception {
        assertThrows(AuthenticationException.class, () -> authService.keepAlive("bogus-token"));
    }
}
