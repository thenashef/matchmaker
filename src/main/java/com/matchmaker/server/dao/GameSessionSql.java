package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class GameSessionSql {

    static final String SELECT_COLUMNS =
            "ID, GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, WinnerID, BoardState";

    private GameSessionSql() {
    }

    static GameStateDTO mapRow(ResultSet rs) throws SQLException {
        Integer currentTurnUserId = (Integer) rs.getObject("CurrentTurnUserID");
        Integer winnerId = (Integer) rs.getObject("WinnerID");
        return new GameStateDTO(
                rs.getInt("ID"),
                rs.getInt("GameTypeID"),
                rs.getInt("Player1ID"),
                rs.getInt("Player2ID"),
                GameStatus.valueOf(rs.getString("Status")),
                currentTurnUserId,
                winnerId,
                rs.getString("BoardState"));
    }

    static boolean hasActiveSession(Connection conn, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT ID FROM GameSession WHERE (Player1ID = ? OR Player2ID = ?) AND Status = 'ACTIVE' LIMIT 1")) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    static int insertActiveSession(Connection conn, int gameTypeId, int player1Id, int player2Id,
                                   String boardState) throws SQLException {
        String sql = "INSERT INTO GameSession "
                + "(GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, BoardState, "
                + "TurnStartedAt, StartTime) VALUES (?, ?, ?, 'ACTIVE', ?, ?, NOW(), NOW())";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setInt(4, player1Id);
            stmt.setString(5, boardState);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    static void deleteWaitingQueueRows(Connection conn, int userId1, int userId2) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM MatchmakingQueue WHERE UserID IN (?, ?) AND Status = 'WAITING'")) {
            stmt.setInt(1, userId1);
            stmt.setInt(2, userId2);
            stmt.executeUpdate();
        }
    }

    static void restoreAutoCommit(Connection conn, boolean previous) {
        try {
            conn.setAutoCommit(previous);
        } catch (SQLException ignored) {
        }
    }
}
