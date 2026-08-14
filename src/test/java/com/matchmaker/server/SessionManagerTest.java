package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void createSession_thenResolve_returnsSameUserId() throws Exception {
        SessionManager sessionManager = new SessionManager();

        String token = sessionManager.createSession(42);

        assertEquals(42, sessionManager.resolve(token));
    }

    @Test
    void lastSeen_beforeAnyResolve_isEmpty() {
        SessionManager sessionManager = new SessionManager();

        assertTrue(sessionManager.lastSeen(42).isEmpty());
    }

    @Test
    void resolve_stampsLastSeenForTheResolvedUser() throws Exception {
        SessionManager sessionManager = new SessionManager();
        String token = sessionManager.createSession(42);

        Instant before = Instant.now();
        sessionManager.resolve(token);
        Instant after = Instant.now();

        Instant lastSeen = sessionManager.lastSeen(42).orElseThrow();
        assertFalse(lastSeen.isBefore(before));
        assertFalse(lastSeen.isAfter(after));
    }

    @Test
    void resolve_calledAgainLater_updatesLastSeen() throws Exception {
        SessionManager sessionManager = new SessionManager();
        String token = sessionManager.createSession(42);

        sessionManager.resolve(token);
        Instant firstSeen = sessionManager.lastSeen(42).orElseThrow();

        Thread.sleep(5);
        sessionManager.resolve(token);
        Instant secondSeen = sessionManager.lastSeen(42).orElseThrow();

        assertTrue(secondSeen.isAfter(firstSeen));
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
