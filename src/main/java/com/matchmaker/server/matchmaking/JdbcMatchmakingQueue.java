package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.server.dao.DaoException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class JdbcMatchmakingQueue implements MatchmakingQueue {

    private final DataSource dataSource;

    public JdbcMatchmakingQueue(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized GameStateDTO join(int userId, int gameTypeId, String initialBoardState)
            throws AlreadyInGameException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                GameStateDTO result = pairOrEnqueue(conn, userId, gameTypeId, initialBoardState);
                conn.commit();
                return result;
            } catch (AlreadyInGameException e) {
                conn.rollback();
                throw e;
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

    @Override
    public int countWaiting() {
        String sql = "SELECT COUNT(*) FROM MatchmakingQueue WHERE Status = 'WAITING'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new DaoException("Failed to count waiting matchmaking queue entries", e);
        }
    }

    private GameStateDTO pairOrEnqueue(Connection conn, int userId, int gameTypeId, String initialBoardState)
            throws SQLException, AlreadyInGameException {
        String findActiveSessionSql = "SELECT ID FROM GameSession "
                + "WHERE (Player1ID = ? OR Player2ID = ?) AND Status = 'ACTIVE' LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(findActiveSessionSql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    throw new AlreadyInGameException("User " + userId + " is already in active game session "
                            + rs.getInt("ID"));
                }
            }
        }

        String findOwnRowSql = "SELECT ID, GameTypeID FROM MatchmakingQueue "
                + "WHERE UserID = ? AND Status = 'WAITING' LIMIT 1";
        Integer ownQueueId = null;
        Integer ownGameTypeId = null;
        try (PreparedStatement stmt = conn.prepareStatement(findOwnRowSql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ownQueueId = rs.getInt("ID");
                    ownGameTypeId = rs.getInt("GameTypeID");
                }
            }
        }

        if (ownQueueId != null && ownGameTypeId == gameTypeId) {
            return null;
        }
        if (ownQueueId != null) {
            deleteQueueRow(conn, ownQueueId);
        }

        // Ordered candidates rather than a single LIMIT 1: a candidate can be stale (e.g. they were
        // drafted into a rematch by their other-game opponent, or force-ended, since they queued) --
        // skip and clean up any such row rather than pairing the caller with someone already playing.
        List<int[]> candidates = new ArrayList<>();
        String findCandidatesSql = "SELECT ID, UserID FROM MatchmakingQueue "
                + "WHERE GameTypeID = ? AND UserID != ? AND Status = 'WAITING' "
                + "ORDER BY JoinedAt ASC, ID ASC";
        try (PreparedStatement stmt = conn.prepareStatement(findCandidatesSql)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new int[] {rs.getInt("ID"), rs.getInt("UserID")});
                }
            }
        }

        for (int[] candidate : candidates) {
            int candidateQueueId = candidate[0];
            int candidateUserId = candidate[1];
            if (hasActiveSession(conn, candidateUserId)) {
                deleteQueueRow(conn, candidateQueueId);
                continue;
            }

            String insertSessionSql = "INSERT INTO GameSession "
                    + "(GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, BoardState, "
                    + "TurnStartedAt, StartTime) "
                    + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, NOW(), NOW())";
            int sessionId;
            try (PreparedStatement stmt = conn.prepareStatement(insertSessionSql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, gameTypeId);
                stmt.setInt(2, candidateUserId);
                stmt.setInt(3, userId);
                stmt.setInt(4, candidateUserId);
                stmt.setString(5, initialBoardState);
                stmt.executeUpdate();
                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    keys.next();
                    sessionId = keys.getInt(1);
                }
            }

            deleteQueueRow(conn, candidateQueueId);

            return new GameStateDTO(sessionId, gameTypeId, candidateUserId, userId,
                    GameStatus.ACTIVE, candidateUserId, null, initialBoardState);
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

    private boolean hasActiveSession(Connection conn, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT ID FROM GameSession WHERE (Player1ID = ? OR Player2ID = ?) AND Status = 'ACTIVE' LIMIT 1")) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void deleteQueueRow(Connection conn, int queueId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM MatchmakingQueue WHERE ID = ?")) {
            stmt.setInt(1, queueId);
            stmt.executeUpdate();
        }
    }
}
