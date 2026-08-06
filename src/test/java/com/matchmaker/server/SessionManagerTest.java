package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void createSession_thenResolve_returnsSameUserId() throws Exception {
        SessionManager sessionManager = new SessionManager();

        String token = sessionManager.createSession(42);

        assertEquals(42, sessionManager.resolve(token));
    }

    @Test
    void resolve_unknownToken_throwsAuthenticationException() {
        SessionManager sessionManager = new SessionManager();

        assertThrows(AuthenticationException.class, () -> sessionManager.resolve("nonexistent-token"));
    }

    @Test
    void createSession_generatesDifferentTokensAcrossCalls() {
        SessionManager sessionManager = new SessionManager();

        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);

        assertNotEquals(token1, token2);
    }
}
