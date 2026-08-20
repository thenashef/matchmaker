package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.server.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final GameSessionDao gameSessionDao = new JdbcGameSessionDao(DATA_SOURCE);

    private int gameTypeId;
    private int player1Id;
    private int player2Id;

    @BeforeEach
    void cleanTablesAndInsertFixtures() throws Exception {
        TestDatabase.cleanAll(DATA_SOURCE);
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

    @Test
    void findActiveById_returnsTheSessionWhenActive() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);

        Optional<GameStateDTO> found = gameSessionDao.findActiveById(sessionId);

        assertTrue(found.isPresent());
        assertEquals(GameStatus.ACTIVE, found.get().getStatus());
        assertEquals(player1Id, found.get().getPlayer1Id());
    }

    @Test
    void findActiveById_absentForAnUnknownId() {
        assertTrue(gameSessionDao.findActiveById(999999).isEmpty());
    }

    @Test
    void recordMove_sessionNoLongerMatchesTheStateItWasReadIn_throwsConcurrentGameUpdateException() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);

        GameStateDTO firstUpdate = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.ACTIVE, player2Id, null, "{\"rows\":8,\"cols\":8,\"pieces\":{\"a3\":\"b\"}}");
        gameSessionDao.recordMove(firstUpdate, player1Id, "{\"path\":[\"b2\",\"a3\"]}");

        GameStateDTO staleUpdate = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.ACTIVE, player2Id, null, "{\"rows\":8,\"cols\":8,\"pieces\":{\"c3\":\"b\"}}");

        assertThrows(ConcurrentGameUpdateException.class,
                () -> gameSessionDao.recordMove(staleUpdate, player1Id, "{\"path\":[\"b2\",\"c3\"]}"));

        Optional<GameStateDTO> reloaded = gameSessionDao.findActiveById(sessionId);
        assertTrue(reloaded.isPresent());
        assertEquals("{\"rows\":8,\"cols\":8,\"pieces\":{\"a3\":\"b\"}}", reloaded.get().getBoardState());
    }

    @Test
    void recordMove_midGame_updatesBoardAndTurnAndInsertsMoveRow() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        GameStateDTO updated = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.ACTIVE, player2Id, null, "{\"rows\":8,\"cols\":8,\"pieces\":{\"a3\":\"b\"}}");

        GameStateDTO result = gameSessionDao.recordMove(updated, player1Id, "{\"path\":[\"b2\",\"a3\"]}");

        assertEquals(player2Id, result.getCurrentTurnUserId());
        assertEquals("{\"rows\":8,\"cols\":8,\"pieces\":{\"a3\":\"b\"}}", result.getBoardState());
        assertEquals(GameStatus.ACTIVE, result.getStatus());

        Optional<GameStateDTO> reloaded = gameSessionDao.findActiveById(sessionId);
        assertTrue(reloaded.isPresent());
        assertEquals(player2Id, reloaded.get().getCurrentTurnUserId());
    }

    @Test
    void recordMove_gameEnding_setsStatusAndWinnerAndUpdatesBothPlayersRatings() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        int player1RatingBefore = ratingOf(player1Id);
        int player2RatingBefore = ratingOf(player2Id);

        GameStateDTO updated = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.FINISHED, null, player1Id, "{\"rows\":8,\"cols\":8,\"pieces\":{}}");

        GameStateDTO result = gameSessionDao.recordMove(updated, player1Id, "{\"path\":[\"g7\",\"h8\"]}");

        assertEquals(GameStatus.FINISHED, result.getStatus());
        assertEquals(Integer.valueOf(player1Id), result.getWinnerId());

        assertTrue(ratingOf(player1Id) > player1RatingBefore);
        assertTrue(ratingOf(player2Id) < player2RatingBefore);
        assertEquals(1, winsOf(player1Id));
        assertEquals(1, lossesOf(player2Id));
    }

    @Test
    void recordMove_gameEndingInADraw_recordsDrawsForBothAndDoesNotThrow() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        int player1RatingBefore = ratingOf(player1Id);

        GameStateDTO drawn = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.FINISHED, null, null, "{\"rows\":8,\"cols\":8,\"pieces\":{}}");

        GameStateDTO result = gameSessionDao.recordMove(drawn, player1Id, "{\"path\":[\"g7\",\"h8\"]}");

        assertEquals(GameStatus.FINISHED, result.getStatus());
        assertNull(result.getWinnerId());
        assertEquals(1, drawsOf(player1Id));
        assertEquals(1, drawsOf(player2Id));
        assertEquals(0, winsOf(player1Id), "a draw is not a win for anyone");
        assertEquals(0, lossesOf(player2Id), "a draw is not a loss for anyone");
        assertEquals(player1RatingBefore, ratingOf(player1Id));
    }

    @Test
    void recordMove_concurrentUpdate_stillSurfacesAsConcurrentGameUpdateException() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        GameStateDTO staleTurn = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.ACTIVE, player1Id, null, "{\"rows\":8,\"cols\":8,\"pieces\":{}}");

        assertThrows(ConcurrentGameUpdateException.class,
                () -> gameSessionDao.recordMove(staleTurn, player2Id, "{\"path\":[\"g7\",\"h8\"]}"));
    }

    @Test
    void findAllActive_returnsOnlyActiveSessions() throws Exception {
        int activeSessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        insertGameSession(gameTypeId, player1Id, player2Id, "FINISHED", player1Id);

        List<GameStateDTO> active = gameSessionDao.findAllActive();

        assertEquals(1, active.size());
        assertEquals(activeSessionId, active.get(0).getSessionId());
        assertEquals(GameStatus.ACTIVE, active.get(0).getStatus());
    }

    @Test
    void forceEnd_activeSession_setsAbandonedNoWinner() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);

        Optional<GameStateDTO> result = gameSessionDao.forceEnd(sessionId);

        assertTrue(result.isPresent());
        assertEquals(GameStatus.ABANDONED, result.get().getStatus());
        assertEquals(null, result.get().getWinnerId());
        assertTrue(gameSessionDao.findActiveById(sessionId).isEmpty());
    }

    @Test
    void forceEnd_alreadyFinishedSession_returnsEmpty() throws Exception {
        int sessionId = insertGameSession(gameTypeId, player1Id, player2Id, "FINISHED", player1Id);

        assertTrue(gameSessionDao.forceEnd(sessionId).isEmpty());
    }

    @Test
    void abandon_withWinner_setsAbandonedAndUpdatesRatingsLikeAWin() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        int player1RatingBefore = ratingOf(player1Id);
        int player2RatingBefore = ratingOf(player2Id);

        Optional<GameStateDTO> result = gameSessionDao.abandon(sessionId, player1Id);

        assertTrue(result.isPresent());
        assertEquals(GameStatus.ABANDONED, result.get().getStatus());
        assertEquals(Integer.valueOf(player1Id), result.get().getWinnerId());
        assertNull(result.get().getCurrentTurnUserId());
        assertTrue(ratingOf(player1Id) > player1RatingBefore);
        assertTrue(ratingOf(player2Id) < player2RatingBefore);
        assertEquals(1, winsOf(player1Id));
        assertEquals(1, lossesOf(player2Id));
        assertEquals(0, winsOf(player2Id));
        assertEquals(0, lossesOf(player1Id));
        assertTrue(gameSessionDao.findActiveById(sessionId).isEmpty());
    }

    @Test
    void abandon_withoutWinner_setsAbandonedAndLeavesRatingsUntouched() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        int player1RatingBefore = ratingOf(player1Id);
        int player2RatingBefore = ratingOf(player2Id);

        Optional<GameStateDTO> result = gameSessionDao.abandon(sessionId, null);

        assertTrue(result.isPresent());
        assertEquals(GameStatus.ABANDONED, result.get().getStatus());
        assertNull(result.get().getWinnerId());
        assertNull(result.get().getCurrentTurnUserId());
        assertEquals(player1RatingBefore, ratingOf(player1Id));
        assertEquals(player2RatingBefore, ratingOf(player2Id));
        assertEquals(0, winsOf(player1Id));
        assertEquals(0, lossesOf(player2Id));
    }

    @Test
    void abandon_alreadyFinishedSession_returnsEmpty() throws Exception {
        int sessionId = insertGameSession(gameTypeId, player1Id, player2Id, "FINISHED", player1Id);

        assertTrue(gameSessionDao.abandon(sessionId, player1Id).isEmpty());
    }

    @Test
    void abandon_winnerNotAParticipant_throwsAndLeavesSessionActive() throws Exception {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        int strangerId = insertUser("stranger");

        assertThrows(IllegalArgumentException.class, () -> gameSessionDao.abandon(sessionId, strangerId));

        assertTrue(gameSessionDao.findActiveById(sessionId).isPresent());
    }

    @Test
    void currentTurnStartedAt_activeSessionWithTurnStartedAtSet_returnsARecentInstant() throws Exception {
        int sessionId = insertActiveSessionWithTurnStartedAt(player1Id, player2Id, gameTypeId);

        Optional<Instant> turnStartedAt = gameSessionDao.currentTurnStartedAt(sessionId);

        assertTrue(turnStartedAt.isPresent());
        assertTrue(Duration.between(turnStartedAt.get(), Instant.now()).abs().toSeconds() < 60,
                "expected TurnStartedAt to read back within 60s of now, was " + turnStartedAt.get());
    }

    @Test
    void currentTurnStartedAt_unknownSession_returnsEmpty() {
        assertTrue(gameSessionDao.currentTurnStartedAt(999999).isEmpty());
    }

    private int insertActiveSession(int player1Id, int player2Id, int gameTypeId) throws Exception {
        String sql = "INSERT INTO GameSession (GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?)";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setInt(4, player1Id);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int insertActiveSessionWithTurnStartedAt(int player1Id, int player2Id, int gameTypeId) throws Exception {
        String sql = "INSERT INTO GameSession (GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, "
                + "TurnStartedAt) VALUES (?, ?, ?, 'ACTIVE', ?, NOW())";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setInt(4, player1Id);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int ratingOf(int userId) throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT Rating FROM User WHERE ID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int winsOf(int userId) throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT Wins FROM User WHERE ID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int drawsOf(int userId) throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT Draws FROM User WHERE ID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int lossesOf(int userId) throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT Losses FROM User WHERE ID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
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
