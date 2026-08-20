package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
    void resolve_tokenIdleLongerThanTheTtl_throwsAndEvictsIt() throws Exception {
        SessionManager sessionManager = new SessionManager(Duration.ofMillis(20));
        String token = sessionManager.createSession(42);

        Thread.sleep(40);

        assertThrows(AuthenticationException.class, () -> sessionManager.resolve(token));
        // ...and it is gone, not merely rejected -- so a token nobody uses can't accumulate.
        assertEquals(0, sessionManager.evictExpired(), "resolve() should have evicted it already");
    }

    @Test
    void resolve_keepsRefreshingTheTtlWhileTheClientIsActive() throws Exception {
        // The TTL is sliding, which is what makes a 30-minute default safe to pair with a 15s
        // keep-alive: a client that keeps pinging never expires, however long it stays connected.
        SessionManager sessionManager = new SessionManager(Duration.ofMillis(60));
        String token = sessionManager.createSession(42);

        for (int i = 0; i < 4; i++) {
            Thread.sleep(25);
            assertEquals(42, sessionManager.resolve(token), "an actively used token must not expire");
        }
    }

    @Test
    void invalidate_makesTheTokenUnusableImmediately() throws Exception {
        SessionManager sessionManager = new SessionManager();
        String token = sessionManager.createSession(42);
        assertEquals(42, sessionManager.resolve(token));

        sessionManager.invalidate(token);

        assertThrows(AuthenticationException.class, () -> sessionManager.resolve(token));
    }

    @Test
    void invalidate_unknownToken_isANoOp() {
        SessionManager sessionManager = new SessionManager();

        assertDoesNotThrow(() -> sessionManager.invalidate("never-issued"));
        assertDoesNotThrow(() -> sessionManager.invalidate(null));
    }

    @Test
    void evictExpired_removesOnlyTheExpiredTokens() throws Exception {
        SessionManager sessionManager = new SessionManager(Duration.ofMillis(50));
        String stale = sessionManager.createSession(1);

        Thread.sleep(70);
        String fresh = sessionManager.createSession(2);

        assertEquals(1, sessionManager.evictExpired());
        assertThrows(AuthenticationException.class, () -> sessionManager.resolve(stale));
        assertEquals(2, sessionManager.resolve(fresh));
    }

    @Test
    void resolve_nullToken_throwsRatherThanNullPointer() {
        SessionManager sessionManager = new SessionManager();

        assertThrows(AuthenticationException.class, () -> sessionManager.resolve(null));
    }

    @Test
    void createSession_generatesDifferentTokensAcrossCalls() {
        SessionManager sessionManager = new SessionManager();

        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);

        assertNotEquals(token1, token2);
    }
}
