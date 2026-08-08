package com.matchmaker.server;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared test-only helper for clearing every table in the schema before a DB-integration
 * test runs.
 *
 * <p>Deletes are ordered children-before-parents to satisfy foreign-key constraints:
 * {@code Move} and {@code ChatMessage} reference {@code GameSession} and/or {@code User};
 * {@code MatchmakingQueue} and {@code GameSession} reference {@code User} (and
 * {@code GameSession}/{@code MatchmakingQueue} reference {@code GameType}); {@code User}
 * and {@code GameType} have no incoming references from tables not already listed, so they
 * go last.
 *
 * <p>All six tables are cleaned even though nothing writes to {@code Move} or
 * {@code ChatMessage} yet — the moment a future milestone adds a writer for either, this
 * helper is already correct for it, instead of every {@code @BeforeEach} silently going
 * stale again the way it did once already on this branch.
 */
public final class TestDatabase {

    private TestDatabase() {
    }

    public static void cleanAll(DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM Move");
            stmt.execute("DELETE FROM ChatMessage");
            stmt.execute("DELETE FROM MatchmakingQueue");
            stmt.execute("DELETE FROM GameSession");
            stmt.execute("DELETE FROM User");
            stmt.execute("DELETE FROM GameType");
        }
    }
}
