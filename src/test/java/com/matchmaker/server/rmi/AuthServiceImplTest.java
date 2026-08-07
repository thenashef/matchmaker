package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryUserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceImplTest {

    private AuthServiceImpl authService;

    @BeforeEach
    void createAuthService() throws Exception {
        authService = new AuthServiceImpl(new SessionManager(), new InMemoryUserDao());
    }

    @AfterEach
    void unexportAuthService() {
        if (authService != null) {
            try { UnicastRemoteObject.unexportObject(authService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void register_withNewUsername_returnsUserWithDefaults() throws Exception {
        UserDTO user = authService.register("alice", "password123");

        assertEquals("alice", user.getUsername());
        assertFalse(user.isAdmin());
        assertEquals(0, user.getWins());
        assertEquals(1200, user.getRating());
    }

    @Test
    void register_withTakenUsername_throwsUsernameTakenException() throws Exception {
        authService.register("bob", "password123");

        assertThrows(UsernameTakenException.class, () -> authService.register("bob", "different-password"));
    }

    @Test
    void login_withCorrectCredentials_returnsTokenAndUser() throws Exception {
        authService.register("carol", "password123");

        LoginResultDTO result = authService.login("carol", "password123");

        assertEquals("carol", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withWrongPassword_throwsAuthenticationException() throws Exception {
        authService.register("dave", "password123");

        assertThrows(AuthenticationException.class, () -> authService.login("dave", "wrongpassword"));
    }

    @Test
    void login_withUnknownUsername_throwsAuthenticationException() throws Exception {
        assertThrows(AuthenticationException.class, () -> authService.login("nobody", "whatever"));
    }

    @Test
    void keepAlive_withValidToken_doesNotThrow() throws Exception {
        authService.register("erin", "password123");
        LoginResultDTO result = authService.login("erin", "password123");

        assertDoesNotThrow(() -> authService.keepAlive(result.getSessionToken()));
    }

    @Test
    void keepAlive_withInvalidToken_throwsAuthenticationException() throws Exception {
        assertThrows(AuthenticationException.class, () -> authService.keepAlive("bogus-token"));
    }
}
