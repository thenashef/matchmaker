package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcGameSessionDao implements GameSessionDao {

    private final DataSource dataSource;
    private final Object sessionLock;

    public JdbcGameSessionDao(DataSource dataSource) {
        this(dataSource, new Object());
    }

    public JdbcGameSessionDao(DataSource dataSource, Object sessionLock) {
        this.dataSource = dataSource;
        this.sessionLock = sessionLock;
    }

    @Override
    public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
        String sql = "SELECT " + GameSessionSql.SELECT_COLUMNS + " "
                + "FROM GameSession WHERE (Player1ID = ? OR Player2ID = ?) AND Status IN ('FINISHED', 'ABANDONED') "
                + "ORDER BY EndTime DESC";
        List<GameStateDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(GameSessionSql.mapRow(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to find finished sessions for user " + userId, e);
        }
    }

    @Override
    public Optional<GameStateDTO> findActiveById(int sessionId) {
        String sql = "SELECT " + GameSessionSql.SELECT_COLUMNS + " "
                + "FROM GameSession WHERE ID = ? AND Status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(GameSessionSql.mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find active game session " + sessionId, e);
        }
    }

    @Override
    public Optional<GameStateDTO> findById(int sessionId) {
        try (Connection conn = dataSource.getConnection()) {
            return findAnyById(conn, sessionId);
        } catch (SQLException e) {
            throw new DaoException("Failed to find game session " + sessionId, e);
        }
    }

    @Override
    public List<GameStateDTO> findAllActive() {
        String sql = "SELECT " + GameSessionSql.SELECT_COLUMNS + " "
                + "FROM GameSession WHERE Status = 'ACTIVE' ORDER BY StartTime";
        List<GameStateDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(GameSessionSql.mapRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to list active sessions", e);
        }
    }

    @Override
    public int countActive() {
        String sql = "SELECT COUNT(*) FROM GameSession WHERE Status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new DaoException("Failed to count active sessions", e);
        }
    }

    @Override
    public int countStartedToday() {
        String sql = "SELECT COUNT(*) FROM GameSession WHERE DATE(StartTime) = CURDATE()";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new DaoException("Failed to count sessions started today", e);
        }
    }

    @Override
    public List<MoveDTO> findMovesForSession(int sessionId) {
        String sql = "SELECT SessionID, UserID, MoveNumber, Payload FROM Move "
                + "WHERE SessionID = ? ORDER BY MoveNumber";
        List<MoveDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new MoveDTO(rs.getInt("SessionID"), rs.getInt("UserID"),
                            rs.getInt("MoveNumber"), rs.getString("Payload")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to find moves for session " + sessionId, e);
        }
    }

    @Override
    public Optional<GameStateDTO> forceEnd(int sessionId) {
        String sql = "UPDATE GameSession SET Status = 'ABANDONED', WinnerID = NULL, "
                + "CurrentTurnUserID = NULL, EndTime = NOW() WHERE ID = ? AND Status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated == 0) {
                return Optional.empty();
            }
            return findAnyById(conn, sessionId);
        } catch (SQLException e) {
            throw new DaoException("Failed to force-end session " + sessionId, e);
        }
    }

    @Override
    public Optional<GameStateDTO> abandon(int sessionId, Integer winnerUserId) {
        String sql = "UPDATE GameSession SET Status = 'ABANDONED', WinnerID = ?, EndTime = NOW(), "
                + "CurrentTurnUserID = NULL WHERE ID = ? AND Status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Optional<GameStateDTO> before = findAnyById(conn, sessionId);
                if (before.isEmpty()) {
                    conn.rollback();
                    return Optional.empty();
                }
                if (winnerUserId != null && before.get().getPlayer1Id() != winnerUserId
                        && before.get().getPlayer2Id() != winnerUserId) {
                    throw new IllegalArgumentException(
                            "winnerUserId " + winnerUserId + " is not a participant in session " + sessionId);
                }

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    if (winnerUserId != null) {
                        stmt.setInt(1, winnerUserId);
                    } else {
                        stmt.setNull(1, Types.INTEGER);
                    }
                    stmt.setInt(2, sessionId);
                    int rowsUpdated = stmt.executeUpdate();
                    if (rowsUpdated == 0) {
                        conn.rollback();
                        return Optional.empty();
                    }
                }

                if (winnerUserId != null) {
                    GameStateDTO session = before.get();
                    int loserUserId = session.getPlayer1Id() == winnerUserId
                            ? session.getPlayer2Id() : session.getPlayer1Id();
                    applyEloAndRecordResult(conn, winnerUserId, loserUserId);
                }

                Optional<GameStateDTO> after = findAnyById(conn, sessionId);
                conn.commit();
                return after;
            } catch (IllegalArgumentException e) {
                conn.rollback();
                throw e;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw new DaoException("Failed to abandon session " + sessionId, e);
            } finally {
                GameSessionSql.restoreAutoCommit(conn, previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to abandon session " + sessionId, e);
        }
    }

    @Override
    public Optional<Instant> currentTurnStartedAt(int sessionId) {
        String sql = "SELECT TurnStartedAt FROM GameSession WHERE ID = ? AND Status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp turnStartedAt = rs.getTimestamp("TurnStartedAt");
                return turnStartedAt == null ? Optional.empty() : Optional.of(turnStartedAt.toInstant());
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to read TurnStartedAt for session " + sessionId, e);
        }
    }

    private Optional<GameStateDTO> findAnyById(Connection conn, int sessionId) throws SQLException {
        String sql = "SELECT " + GameSessionSql.SELECT_COLUMNS + " "
                + "FROM GameSession WHERE ID = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(GameSessionSql.mapRow(rs));
            }
        }
    }

    @Override
    public GameStateDTO createRematch(int finishedSessionId, String initialBoardState)
            throws AlreadyInGameException {
        synchronized (sessionLock) {
            try (Connection conn = dataSource.getConnection()) {
                boolean previousAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    Integer existingRematchId = findRematchSessionId(conn, finishedSessionId);
                    if (existingRematchId != null) {
                        GameStateDTO existing = findAnyById(conn, existingRematchId)
                                .orElseThrow(() -> new DaoException("RematchSessionID " + existingRematchId
                                        + " on session " + finishedSessionId + " does not point to a real session", null));
                        if (existing.getStatus() != GameStatus.ACTIVE) {
                            throw new AlreadyInGameException("The rematch of session " + finishedSessionId
                                    + " already ended (session " + existingRematchId + ")");
                        }
                        conn.commit();
                        return existing;
                    }

                    GameStateDTO finished = findAnyById(conn, finishedSessionId)
                            .orElseThrow(() -> new DaoException("No such game session " + finishedSessionId, null));

                    int newPlayer1Id = finished.getPlayer2Id();
                    int newPlayer2Id = finished.getPlayer1Id();
                    requireNeitherPlayerHasAnActiveSession(conn, newPlayer1Id, newPlayer2Id);

                    int newSessionId = GameSessionSql.insertActiveSession(
                            conn, finished.getGameTypeId(), newPlayer1Id, newPlayer2Id, initialBoardState);

                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE GameSession SET RematchSessionID = ? WHERE ID = ?")) {
                        stmt.setInt(1, newSessionId);
                        stmt.setInt(2, finishedSessionId);
                        stmt.executeUpdate();
                    }

                    GameSessionSql.deleteWaitingQueueRows(conn, newPlayer1Id, newPlayer2Id);

                    GameStateDTO created = new GameStateDTO(newSessionId, finished.getGameTypeId(),
                            newPlayer1Id, newPlayer2Id, GameStatus.ACTIVE, newPlayer1Id, null, initialBoardState);
                    conn.commit();
                    return created;
                } catch (AlreadyInGameException e) {
                    conn.rollback();
                    throw e;
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    throw new DaoException("Failed to create rematch for session " + finishedSessionId, e);
                } finally {
                    GameSessionSql.restoreAutoCommit(conn, previousAutoCommit);
                }
            } catch (SQLException e) {
                throw new DaoException("Failed to create rematch for session " + finishedSessionId, e);
            }
        }
    }

    private Integer findRematchSessionId(Connection conn, int sessionId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT RematchSessionID FROM GameSession WHERE ID = ?")) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int value = rs.getInt("RematchSessionID");
                return rs.wasNull() ? null : value;
            }
        }
    }

    private void requireNeitherPlayerHasAnActiveSession(Connection conn, int player1Id, int player2Id)
            throws SQLException, AlreadyInGameException {
        if (GameSessionSql.hasActiveSession(conn, player1Id) || GameSessionSql.hasActiveSession(conn, player2Id)) {
            throw new AlreadyInGameException(
                    "A participant of session is already in a different active game");
        }
    }

    @Override
    public GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson) {
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
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
                    Integer winnerId = updatedSession.getWinnerId();
                    if (winnerId == null) {
                        applyDrawResult(conn, updatedSession.getPlayer1Id(), updatedSession.getPlayer2Id());
                    } else {
                        int loserId = (updatedSession.getPlayer1Id() == winnerId)
                                ? updatedSession.getPlayer2Id() : updatedSession.getPlayer1Id();
                        applyEloAndRecordResult(conn, winnerId, loserId);
                    }
                }

                conn.commit();
                return updatedSession;
            } catch (ConcurrentGameUpdateException e) {
                throw e;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw new DaoException("Failed to record move for session " + updatedSession.getSessionId(), e);
            } finally {
                GameSessionSql.restoreAutoCommit(conn, previousAutoCommit);
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

    private void applyDrawResult(Connection conn, int player1Id, int player2Id) throws SQLException {
        int player1Rating = ratingOf(conn, player1Id);
        int player2Rating = ratingOf(conn, player2Id);

        double expectedPlayer1 = 1.0 / (1.0 + Math.pow(10, (player2Rating - player1Rating) / 400.0));
        double expectedPlayer2 = 1.0 / (1.0 + Math.pow(10, (player1Rating - player2Rating) / 400.0));
        int newPlayer1Rating = player1Rating + (int) Math.round(32 * (0.5 - expectedPlayer1));
        int newPlayer2Rating = player2Rating + (int) Math.round(32 * (0.5 - expectedPlayer2));

        try (PreparedStatement updateDraw = conn.prepareStatement(
                "UPDATE User SET Draws = Draws + 1, Rating = ? WHERE ID = ?")) {
            updateDraw.setInt(1, newPlayer1Rating);
            updateDraw.setInt(2, player1Id);
            updateDraw.executeUpdate();

            updateDraw.setInt(1, newPlayer2Rating);
            updateDraw.setInt(2, player2Id);
            updateDraw.executeUpdate();
        }
    }

    private int ratingOf(Connection conn, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT Rating FROM User WHERE ID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No user with ID " + userId);
                }
                return rs.getInt(1);
            }
        }
    }

}
