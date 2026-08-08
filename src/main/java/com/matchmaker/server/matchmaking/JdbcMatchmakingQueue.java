package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.server.dao.DaoException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

public class JdbcMatchmakingQueue implements MatchmakingQueue {

    private final DataSource dataSource;

    public JdbcMatchmakingQueue(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized GameStateDTO join(int userId, int gameTypeId) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                GameStateDTO result = pairOrEnqueue(conn, userId, gameTypeId);
                conn.commit();
                return result;
            } catch (SQLException e) {
                conn.rollback();
                throw new DaoException("Failed to join matchmaking queue for user " + userId, e);
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to join matchmaking queue for user " + userId, e);
        }
    }

    @Override
    public synchronized void cancel(int userId) {
        String sql = "DELETE FROM MatchmakingQueue WHERE UserID = ? AND Status = 'WAITING'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to cancel matchmaking queue for user " + userId, e);
        }
    }

    private GameStateDTO pairOrEnqueue(Connection conn, int userId, int gameTypeId) throws SQLException {
        Integer opponentQueueId = null;
        Integer opponentUserId = null;

        String findOpponentSql = "SELECT ID, UserID FROM MatchmakingQueue "
                + "WHERE GameTypeID = ? AND UserID != ? AND Status = 'WAITING' "
                + "ORDER BY JoinedAt ASC LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(findOpponentSql)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    opponentQueueId = rs.getInt("ID");
                    opponentUserId = rs.getInt("UserID");
                }
            }
        }

        if (opponentUserId == null) {
            String findOwnRowSql = "SELECT ID FROM MatchmakingQueue "
                    + "WHERE GameTypeID = ? AND UserID = ? AND Status = 'WAITING' LIMIT 1";
            boolean alreadyQueued;
            try (PreparedStatement stmt = conn.prepareStatement(findOwnRowSql)) {
                stmt.setInt(1, gameTypeId);
                stmt.setInt(2, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    alreadyQueued = rs.next();
                }
            }

            if (alreadyQueued) {
                return null;
            }

            String insertQueueRowSql = "INSERT INTO MatchmakingQueue (UserID, GameTypeID, Status, JoinedAt) "
                    + "VALUES (?, ?, 'WAITING', ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertQueueRowSql)) {
                stmt.setInt(1, userId);
                stmt.setInt(2, gameTypeId);
                stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                stmt.executeUpdate();
            }
            return null;
        }

        Timestamp now = new Timestamp(System.currentTimeMillis());
        String insertSessionSql = "INSERT INTO GameSession "
                + "(GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, TurnStartedAt, StartTime) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)";
        int sessionId;
        try (PreparedStatement stmt = conn.prepareStatement(insertSessionSql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, opponentUserId);
            stmt.setInt(3, userId);
            stmt.setInt(4, opponentUserId);
            stmt.setTimestamp(5, now);
            stmt.setTimestamp(6, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                sessionId = keys.getInt(1);
            }
        }

        String deleteQueueRowSql = "DELETE FROM MatchmakingQueue WHERE ID = ?";
        try (PreparedStatement stmt = conn.prepareStatement(deleteQueueRowSql)) {
            stmt.setInt(1, opponentQueueId);
            stmt.executeUpdate();
        }

        return new GameStateDTO(sessionId, gameTypeId, opponentUserId, userId,
                GameStatus.ACTIVE, opponentUserId, null, null);
    }
}
