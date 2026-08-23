package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.server.matchmaking.MatchmakingQueue;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class JdbcMatchmakingDao implements MatchmakingQueue {

    private final DataSource dataSource;
    private final Object sessionLock;

    public JdbcMatchmakingDao(DataSource dataSource) {
        this(dataSource, new Object());
    }

    public JdbcMatchmakingDao(DataSource dataSource, Object sessionLock) {
        this.dataSource = dataSource;
        this.sessionLock = sessionLock;
    }

    @Override
    public GameStateDTO join(int userId, int gameTypeId, String initialBoardState)
            throws AlreadyInGameException {
        synchronized (sessionLock) {
            try (Connection conn = dataSource.getConnection()) {
                boolean previousAutoCommit = conn.getAutoCommit();
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
                } finally {
                    GameSessionSql.restoreAutoCommit(conn, previousAutoCommit);
                }
            } catch (SQLException e) {
                throw new DaoException("Failed to join matchmaking queue for user " + userId, e);
            }
        }
    }

    @Override
    public void cancel(int userId) {
        synchronized (sessionLock) {
            String sql = "DELETE FROM MatchmakingQueue WHERE UserID = ? AND Status = 'WAITING'";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, userId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new DaoException("Failed to cancel matchmaking queue for user " + userId, e);
            }
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
        if (GameSessionSql.hasActiveSession(conn, userId)) {
            throw new AlreadyInGameException("User " + userId + " is already in an active game session");
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
            if (GameSessionSql.hasActiveSession(conn, candidateUserId)) {
                deleteQueueRow(conn, candidateQueueId);
                continue;
            }

            int sessionId = GameSessionSql.insertActiveSession(
                    conn, gameTypeId, candidateUserId, userId, initialBoardState);
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

    private void deleteQueueRow(Connection conn, int queueId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM MatchmakingQueue WHERE ID = ?")) {
            stmt.setInt(1, queueId);
            stmt.executeUpdate();
        }
    }
}
