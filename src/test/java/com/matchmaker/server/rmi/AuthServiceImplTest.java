package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceImplTest {

    @Test
    void login_withCorrectCredentials_returnsTokenAndUser() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        LoginResultDTO result = authService.login("test", "test1234");

        assertEquals("test", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withWrongPassword_throwsAuthenticationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(AuthenticationException.class, () -> authService.login("test", "wrongpassword"));
    }

    @Test
    void login_withUnknownUsername_throwsAuthenticationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(AuthenticationException.class, () -> authService.login("nobody", "whatever"));
    }

    @Test
    void register_withTakenUsername_throwsUsernameTakenException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(UsernameTakenException.class, () -> authService.register("test", "whatever"));
    }

    @Test
    void register_withNewUsername_throwsUnsupportedOperationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(UnsupportedOperationException.class, () -> authService.register("newuser", "whatever"));
    }

    @Test
    void keepAlive_withValidToken_doesNotThrow() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());
        LoginResultDTO result = authService.login("test", "test1234");

        assertDoesNotThrow(() -> authService.keepAlive(result.getSessionToken()));
    }

    @Test
    void keepAlive_withInvalidToken_throwsAuthenticationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(AuthenticationException.class, () -> authService.keepAlive("bogus-token"));
    }
}
