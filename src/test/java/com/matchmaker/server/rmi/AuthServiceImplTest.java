package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
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
    void login_withNullPassword_throwsAuthenticationException() throws Exception {
        authService.register("frank", "password123");

        assertThrows(AuthenticationException.class, () -> authService.login("frank", null));
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
    
    @Test
    void register_blankOrMissingUsername_throwsInvalidRegistration() {
        assertThrows(InvalidRegistrationException.class, () -> authService.register("", "goodpassword"));
        assertThrows(InvalidRegistrationException.class, () -> authService.register("   ", "goodpassword"));
        assertThrows(InvalidRegistrationException.class, () -> authService.register(null, "goodpassword"));
    }

    @Test
    void register_usernameLongerThanTheColumn_throwsInvalidRegistrationNotASqlError() {
        String tooLong = "a".repeat(51);

        InvalidRegistrationException thrown = assertThrows(InvalidRegistrationException.class,
                () -> authService.register(tooLong, "goodpassword"));
        assertTrue(thrown.getMessage().contains("50"), "the message should say what the limit is");
    }

    @Test
    void register_usernameWithSurroundingSpace_throwsInvalidRegistration() {
        assertThrows(InvalidRegistrationException.class, () -> authService.register(" alice", "goodpassword"));
        assertThrows(InvalidRegistrationException.class, () -> authService.register("alice ", "goodpassword"));
    }

    @Test
    void register_shortPassword_throwsInvalidRegistration() {
        assertThrows(InvalidRegistrationException.class, () -> authService.register("alice", "12345"));
        assertThrows(InvalidRegistrationException.class, () -> authService.register("alice", null));
    }

    @Test
    void register_passwordBeyondWhatBcryptHashes_throwsInvalidRegistration() {
        assertThrows(InvalidRegistrationException.class,
                () -> authService.register("alice", "x".repeat(73)));
    }

    @Test
    void register_validCredentials_stillSucceeds() throws Exception {
        assertNotNull(authService.register("alice", "goodpassword"));
    }

    @Test
    void logout_makesTheTokenUnusableForSubsequentCalls() throws Exception {
        authService.register("alice", "goodpassword");
        String token = authService.login("alice", "goodpassword").getSessionToken();
        authService.keepAlive(token);

        authService.logout(token);

        assertThrows(AuthenticationException.class, () -> authService.keepAlive(token));
    }

    @Test
    void logout_unknownToken_doesNotThrow() {
        assertDoesNotThrow(() -> authService.logout("never-issued"));
    }
}
