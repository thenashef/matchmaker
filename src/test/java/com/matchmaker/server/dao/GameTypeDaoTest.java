package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTypeDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final GameTypeDao gameTypeDao = new JdbcGameTypeDao(DATA_SOURCE);

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM MatchmakingQueue");
            stmt.execute("DELETE FROM GameSession");
            stmt.execute("DELETE FROM User");
            stmt.execute("DELETE FROM GameType");
        }
    }

    @Test
    void findAll_noGameTypes_returnsEmptyList() {
        assertTrue(gameTypeDao.findAll().isEmpty());
    }

    @Test
    void findAll_withGameTypes_returnsThemInInsertOrder() throws Exception {
        insertGameType("Checkers", "Classic checkers", 2, 2, 8, 8);
        insertGameType("Chess", "Classic chess", 2, 2, 8, 8);

        List<GameTypeDTO> result = gameTypeDao.findAll();

        assertEquals(2, result.size());
        assertEquals("Checkers", result.get(0).getName());
        assertEquals("Chess", result.get(1).getName());
    }

    private void insertGameType(String name, String description, int minPlayers, int maxPlayers,
                                 int rows, int cols) throws Exception {
        String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DATA_SOURCE.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, description);
            stmt.setInt(3, minPlayers);
            stmt.setInt(4, maxPlayers);
            stmt.setInt(5, rows);
            stmt.setInt(6, cols);
            stmt.executeUpdate();
        }
    }
}
