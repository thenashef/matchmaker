package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcGameSessionDao implements GameSessionDao {

    private final DataSource dataSource;

    public JdbcGameSessionDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
        String sql = "SELECT ID, GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, WinnerID, BoardState "
                + "FROM GameSession WHERE (Player1ID = ? OR Player2ID = ?) AND Status = 'FINISHED' "
                + "ORDER BY EndTime DESC";
        List<GameStateDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Integer currentTurnUserId = (Integer) rs.getObject("CurrentTurnUserID");
                    Integer winnerId = (Integer) rs.getObject("WinnerID");
                    result.add(new GameStateDTO(
                            rs.getInt("ID"),
                            rs.getInt("GameTypeID"),
                            rs.getInt("Player1ID"),
                            rs.getInt("Player2ID"),
                            GameStatus.valueOf(rs.getString("Status")),
                            currentTurnUserId,
                            winnerId,
                            rs.getString("BoardState")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to find finished sessions for user " + userId, e);
        }
    }

    @Override
    public Optional<GameStateDTO> findActiveById(int sessionId) {
        String sql = "SELECT ID, GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, WinnerID, BoardState "
                + "FROM GameSession WHERE ID = ? AND Status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find active game session " + sessionId, e);
        }
    }

    @Override
    public GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int nextMoveNumber = nextMoveNumber(conn, updatedSession.getSessionId());

                try (PreparedStatement insertMove = conn.prepareStatement(
                        "INSERT INTO Move (SessionID, UserID, MoveNumber, Payload) VALUES (?, ?, ?, ?)")) {
                    insertMove.setInt(1, updatedSession.getSessionId());
                    insertMove.setInt(2, movingUserId);
                    insertMove.setInt(3, nextMoveNumber);
                    insertMove.setString(4, movePayloadJson);
                    insertMove.executeUpdate();
                }

                boolean gameEnded = updatedSession.getStatus() == GameStatus.FINISHED;
                // The WHERE clause ties this write back to the exact pre-move state the caller
                // validated against (movingUserId can only be legal here if it still equals
                // CurrentTurnUserID, and the session must still be ACTIVE) -- this is what
                // makes concurrent calls for the same session safe: only the first to commit
                // wins, and a loser gets 0 rows affected instead of silently clobbering it.
                try (PreparedStatement updateSession = conn.prepareStatement(
                        "UPDATE GameSession SET BoardState = ?, CurrentTurnUserID = ?, TurnStartedAt = NOW(), "
                                + "Status = ?, WinnerID = ?, EndTime = ? "
                                + "WHERE ID = ? AND CurrentTurnUserID = ? AND Status = 'ACTIVE'")) {
                    updateSession.setString(1, updatedSession.getBoardState());
                    if (updatedSession.getCurrentTurnUserId() != null) {
                        updateSession.setInt(2, updatedSession.getCurrentTurnUserId());
                    } else {
                        updateSession.setNull(2, Types.INTEGER);
                    }
                    updateSession.setString(3, updatedSession.getStatus().name());
                    if (updatedSession.getWinnerId() != null) {
                        updateSession.setInt(4, updatedSession.getWinnerId());
                    } else {
                        updateSession.setNull(4, Types.INTEGER);
                    }
                    if (gameEnded) {
                        updateSession.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                    } else {
                        updateSession.setNull(5, Types.TIMESTAMP);
                    }
                    updateSession.setInt(6, updatedSession.getSessionId());
                    updateSession.setInt(7, movingUserId);
                    int rowsUpdated = updateSession.executeUpdate();
                    if (rowsUpdated == 0) {
                        conn.rollback();
                        throw new ConcurrentGameUpdateException(
                                "Session " + updatedSession.getSessionId() + " was already updated by another move "
                                        + "since it was read -- turn or status no longer matches");
                    }
                }

                if (gameEnded) {
                    int winnerId = updatedSession.getWinnerId();
                    int loserId = (updatedSession.getPlayer1Id() == winnerId)
                            ? updatedSession.getPlayer2Id() : updatedSession.getPlayer1Id();
                    applyEloAndRecordResult(conn, winnerId, loserId);
                }

                conn.commit();
                return updatedSession;
            } catch (SQLException e) {
                conn.rollback();
                throw new DaoException("Failed to record move for session " + updatedSession.getSessionId(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to record move for session " + updatedSession.getSessionId(), e);
        }
    }

    private int nextMoveNumber(Connection conn, int sessionId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT COALESCE(MAX(MoveNumber), 0) + 1 FROM Move WHERE SessionID = ?")) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void applyEloAndRecordResult(Connection conn, int winnerId, int loserId) throws SQLException {
        int winnerRating = ratingOf(conn, winnerId);
        int loserRating = ratingOf(conn, loserId);

        double expectedWinner = 1.0 / (1.0 + Math.pow(10, (loserRating - winnerRating) / 400.0));
        double expectedLoser = 1.0 / (1.0 + Math.pow(10, (winnerRating - loserRating) / 400.0));
        int newWinnerRating = winnerRating + (int) Math.round(32 * (1.0 - expectedWinner));
        int newLoserRating = loserRating + (int) Math.round(32 * (0.0 - expectedLoser));

        try (PreparedStatement updateWinner = conn.prepareStatement(
                "UPDATE User SET Wins = Wins + 1, Rating = ? WHERE ID = ?")) {
            updateWinner.setInt(1, newWinnerRating);
            updateWinner.setInt(2, winnerId);
            updateWinner.executeUpdate();
        }
        try (PreparedStatement updateLoser = conn.prepareStatement(
                "UPDATE User SET Losses = Losses + 1, Rating = ? WHERE ID = ?")) {
            updateLoser.setInt(1, newLoserRating);
            updateLoser.setInt(2, loserId);
            updateLoser.executeUpdate();
        }
    }

    private int ratingOf(Connection conn, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT Rating FROM User WHERE ID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private GameStateDTO mapRow(ResultSet rs) throws SQLException {
        Integer currentTurnUserId = (Integer) rs.getObject("CurrentTurnUserID");
        Integer winnerId = (Integer) rs.getObject("WinnerID");
        return new GameStateDTO(
                rs.getInt("ID"), rs.getInt("GameTypeID"), rs.getInt("Player1ID"), rs.getInt("Player2ID"),
                GameStatus.valueOf(rs.getString("Status")),
                currentTurnUserId,
                winnerId, rs.getString("BoardState"));
    }
}
