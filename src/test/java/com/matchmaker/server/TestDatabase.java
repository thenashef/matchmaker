package com.matchmaker.server;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

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
