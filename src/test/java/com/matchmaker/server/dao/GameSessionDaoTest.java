package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final GameSessionDao gameSessionDao = new JdbcGameSessionDao(DATA_SOURCE);

    private int gameTypeId;
    private int player1Id;
    private int player2Id;

    @BeforeEach
    void cleanTablesAndInsertFixtures() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM MatchmakingQueue");
            stmt.execute("DELETE FROM GameSession");
            stmt.execute("DELETE FROM User");
            stmt.execute("DELETE FROM GameType");
        }
        gameTypeId = insertGameType("Checkers");
        player1Id = insertUser("player1");
        player2Id = insertUser("player2");
    }

    @Test
    void findFinishedSessionsForUser_returnsOnlyFinishedSessionsInvolvingUser() throws Exception {
        int finishedSessionId = insertGameSession(gameTypeId, player1Id, player2Id, "FINISHED", player1Id);
        insertGameSession(gameTypeId, player1Id, player2Id, "ACTIVE", null);

        List<GameStateDTO> history = gameSessionDao.findFinishedSessionsForUser(player1Id);

        assertEquals(1, history.size());
        assertEquals(finishedSessionId, history.get(0).getSessionId());
        assertEquals(GameStatus.FINISHED, history.get(0).getStatus());
        assertEquals(player1Id, history.get(0).getWinnerId());
    }

    @Test
    void findFinishedSessionsForUser_userNotInAnySession_returnsEmptyList() {
        List<GameStateDTO> history = gameSessionDao.findFinishedSessionsForUser(player1Id);

        assertTrue(history.isEmpty());
    }

    private int insertGameType(String name) throws Exception {
        String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
                + "VALUES (?, 'desc', 2, 2, 8, 8)";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int insertUser(String username) throws Exception {
        String sql = "INSERT INTO User (Username, Password) VALUES (?, 'hash')";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int insertGameSession(int gameTypeId, int player1Id, int player2Id, String status,
                                   Integer winnerId) throws Exception {
        String sql = "INSERT INTO GameSession (GameTypeID, Player1ID, Player2ID, Status, WinnerID, EndTime) "
                + "VALUES (?, ?, ?, ?, ?, NOW())";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setString(4, status);
            if (winnerId != null) {
                stmt.setInt(5, winnerId);
            } else {
                stmt.setNull(5, Types.INTEGER);
            }
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }
}
