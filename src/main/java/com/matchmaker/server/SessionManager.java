package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and resolves the session tokens every RMI call and JMS connection authenticates with.
 *
 * <h2>Why tokens expire</h2>
 * Tokens used to live for the life of the process: nothing removed them, and there was no
 * logout, so the map grew by one entry per login forever and every token ever issued stayed
 * valid. That matters more here than in a typical request/response app, because
 * {@code JmsSecurityPlugin} authenticates broker connections against this same map — a leaked
 * token is credentials for both surfaces, over a plain {@code tcp://} transport.
 *
 * <p>The TTL is <em>sliding</em>: {@link #resolve} refreshes it, so an active client (which
 * pings every 15s via {@code keepAlive}) never expires, while an abandoned token ages out.
 * It is set well above both the keep-alive interval and the watchdog's disconnect timeout, so
 * a brief network stall costs a player their game, not their login.
 *
 * <h2>What expiry does not do</h2>
 * The broker authenticates a JMS connection once, at connect time, so expiring a token here
 * does not drop a connection that is already established — it only prevents new ones. Closing
 * live connections on expiry would mean reaching into the broker from here, which this
 * deliberately doesn't do.
 */
public class SessionManager {

    /**
     * Comfortably longer than {@code GameClientService}'s 15s keep-alive and
     * {@code ServerMain}'s 60s disconnect timeout, so expiry is about abandoned tokens rather
     * than transient silence.
     */
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(30);

    private final Map<String, Session> sessionByToken = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> lastSeenByUserId = new ConcurrentHashMap<>();
    private final Duration tokenTtl;

    public SessionManager() {
        this(DEFAULT_TOKEN_TTL);
    }

    /** Test seam: lets a test use a TTL short enough to actually observe expiry. */
    public SessionManager(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String createSession(int userId) {
        String token = UUID.randomUUID().toString();
        sessionByToken.put(token, new Session(userId, Instant.now()));
        return token;
    }

    public int resolve(String token) throws AuthenticationException {
        if (token == null) {
            throw new AuthenticationException("Invalid or expired session token");
        }
        Instant now = Instant.now();
        // computeIfPresent doing both jobs at once is what makes this safe under concurrent
        // calls: returning null from the remapping function removes the entry, so an expired
        // token is evicted in the same atomic step that would otherwise have refreshed it.
        Session session = sessionByToken.computeIfPresent(token,
                (key, existing) -> isExpired(existing, now) ? null : new Session(existing.userId(), now));
        if (session == null) {
            throw new AuthenticationException("Invalid or expired session token");
        }
        lastSeenByUserId.put(session.userId(), now);
        return session.userId();
    }

    /** Drops a token immediately, on explicit logout. Unknown tokens are a no-op. */
    public void invalidate(String token) {
        if (token != null) {
            sessionByToken.remove(token);
        }
    }

    /**
     * Sweeps out tokens nobody has used within the TTL, so a long-running server doesn't
     * accumulate an entry for every login it ever saw. Driven by {@link SessionWatchdog}'s
     * existing tick rather than a timer of its own.
     *
     * <p>Only tokens are swept. {@code lastSeenByUserId} holds at most one entry per distinct
     * user who has ever logged in — bounded by the size of the User table, not by login count
     * — and the watchdog reads it to decide whether a player has gone silent, so dropping
     * entries from it would change disconnect detection for no real memory saving.
     *
     * @return how many tokens were removed, for logging or tests
     */
    public int evictExpired() {
        Instant now = Instant.now();
        int before = sessionByToken.size();
        sessionByToken.values().removeIf(session -> isExpired(session, now));
        return before - sessionByToken.size();
    }

    public Optional<Instant> lastSeen(int userId) {
        return Optional.ofNullable(lastSeenByUserId.get(userId));
    }

    private boolean isExpired(Session session, Instant now) {
        return Duration.between(session.lastUsed(), now).compareTo(tokenTtl) > 0;
    }

    private record Session(int userId, Instant lastUsed) {
    }
}
