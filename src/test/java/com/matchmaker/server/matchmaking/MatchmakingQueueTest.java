package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.server.TestDatabase;
import com.matchmaker.server.dao.DataSourceFactory;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.JdbcGameSessionDao;
import com.matchmaker.server.dao.JdbcMatchmakingDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingQueueTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private static final String INITIAL_BOARD = "{\"rows\":8,\"cols\":8,\"pieces\":{\"a1\":\"b\"}}";

    private final Object sessionLock = new Object();
    private final MatchmakingQueue matchmakingQueue = new JdbcMatchmakingDao(DATA_SOURCE, sessionLock);
    private final GameSessionDao gameSessionDao = new JdbcGameSessionDao(DATA_SOURCE, sessionLock);

    private int gameTypeId;

    @BeforeEach
    void cleanTablesAndInsertFixtures() throws Exception {
        TestDatabase.cleanAll(DATA_SOURCE);
        gameTypeId = insertGameType("Checkers");
    }

    @Test
    void join_noOneWaiting_returnsNullAndQueuesCaller() throws Exception {
        int aliceId = insertUser("alice");

        GameStateDTO result = matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);

        assertNull(result);
        assertEquals(1, countQueueRows());
    }

    @Test
    void countWaiting_reflectsRowsCurrentlyWaiting() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");

        assertEquals(0, matchmakingQueue.countWaiting());

        matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);
        assertEquals(1, matchmakingQueue.countWaiting());

        matchmakingQueue.join(bobId, gameTypeId, INITIAL_BOARD);
        assertEquals(0, matchmakingQueue.countWaiting(), "pairing should clear the queue");
    }

    @Test
    void join_earliestCandidateAlreadyHasAnActiveSession_skipsAndDeletesTheStaleRowThenEnqueuesCaller() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");
        int carolId = insertUser("carol");

        // A stale queue row: bob queued, but is now already playing (e.g. drafted into a rematch
        // by a different opponent after queueing) -- his row should never have been left behind,
        // but this proves join() is defensive about it regardless.
        matchmakingQueue.join(bobId, gameTypeId, INITIAL_BOARD);
        insertActiveSessionDirectly(bobId, carolId, gameTypeId);

        GameStateDTO result = matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);

        assertNull(result, "alice must not be paired with bob, who is already in a different active game");
        assertFalse(hasQueueRowFor(bobId, gameTypeId), "the stale row should be cleaned up");
        assertTrue(hasQueueRowFor(aliceId, gameTypeId), "alice should be enqueued since no valid opponent was found");
    }

    @Test
    void join_opponentWaiting_returnsMatchedSessionAndClearsQueue() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");
        matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);

        GameStateDTO result = matchmakingQueue.join(bobId, gameTypeId, INITIAL_BOARD);

        assertNotNull(result);
        assertEquals(gameTypeId, result.getGameTypeId());
        assertEquals(aliceId, result.getPlayer1Id());
        assertEquals(bobId, result.getPlayer2Id());
        assertEquals(GameStatus.ACTIVE, result.getStatus());
        assertEquals(aliceId, result.getCurrentTurnUserId());
        assertNull(result.getWinnerId());
        assertEquals(0, countQueueRows());

        Optional<Instant> turnStartedAt = gameSessionDao.currentTurnStartedAt(result.getSessionId());
        assertTrue(turnStartedAt.isPresent());
        assertTrue(Duration.between(turnStartedAt.get(), Instant.now()).abs().toSeconds() < 60,
                "expected the newly matched session's TurnStartedAt to read back within 60s of now, was "
                        + turnStartedAt.get());
    }

    @Test
    void join_opponentWaiting_persistsTheInitialBoardOnTheNewSession() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");
        matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);

        GameStateDTO result = matchmakingQueue.join(bobId, gameTypeId, INITIAL_BOARD);

        assertEquals(INITIAL_BOARD, result.getBoardState());
        assertEquals(INITIAL_BOARD, gameSessionDao.findActiveById(result.getSessionId())
                        .orElseThrow().getBoardState(),
                "BoardState must be persisted at creation, not left null until the first move");
    }

    @Test
    void join_calledTwiceByCaller_doesNotCreateDuplicateRow() throws Exception {
        int aliceId = insertUser("alice");

        GameStateDTO first = matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);
        GameStateDTO second = matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);

        assertNull(first);
        assertNull(second);
        assertEquals(1, countQueueRows());
    }

    @Test
    void join_calledTwiceForDifferentGameTypes_doesNotCreateSecondRow() throws Exception {
        int chessGameTypeId = insertGameType("Chess");
        int aliceId = insertUser("alice");

        GameStateDTO first = matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);
        GameStateDTO second = matchmakingQueue.join(aliceId, chessGameTypeId, INITIAL_BOARD);

        assertNull(first);
        assertNull(second);
        assertEquals(1, countQueueRows());
        assertTrue(hasQueueRowFor(aliceId, chessGameTypeId));
        assertFalse(hasQueueRowFor(aliceId, gameTypeId));
    }

    @Test
    void join_alreadyQueuedForDifferentGameType_leavesOldQueueAndPairsOnTheNewType() throws Exception {
        int chessGameTypeId = insertGameType("Chess");
        int bobId = insertUser("bob");
        int aliceId = insertUser("alice");

        GameStateDTO bobJoin = matchmakingQueue.join(bobId, chessGameTypeId, INITIAL_BOARD);
        GameStateDTO aliceJoinCheckers = matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);
        GameStateDTO aliceJoinChess = matchmakingQueue.join(aliceId, chessGameTypeId, INITIAL_BOARD);

        assertNull(bobJoin);
        assertNull(aliceJoinCheckers);
        assertNotNull(aliceJoinChess, "switching queues must pair Alice with Bob who is already waiting for chess");
        assertEquals(0, countQueueRows());
        assertEquals(1, countGameSessions());
        assertEquals(chessGameTypeId, aliceJoinChess.getGameTypeId());
    }

    @Test
    void join_callerAlreadyInAnActiveSession_throwsAlreadyInGameException() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");
        insertGameSession(gameTypeId, aliceId, bobId, "ACTIVE", null);
        int carolId = insertUser("carol");

        assertThrows(AlreadyInGameException.class,
                () -> matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD));
        assertEquals(0, countQueueRows(), "the rejected join must not leave a queue row behind");

        assertNull(matchmakingQueue.join(carolId, gameTypeId, INITIAL_BOARD));
        assertEquals(1, countQueueRows());
    }

    @Test
    void join_callerOnlyInAFinishedSession_isAllowedToQueueAgain() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");
        insertGameSession(gameTypeId, aliceId, bobId, "FINISHED", aliceId);

        assertNull(matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD));
        assertEquals(1, countQueueRows());
    }

    @Test
    void cancel_removesWaitingRow() throws Exception {
        int aliceId = insertUser("alice");
        matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);

        matchmakingQueue.cancel(aliceId);

        assertEquals(0, countQueueRows());
    }

    @Test
    void cancel_notQueued_doesNothing() throws Exception {
        int aliceId = insertUser("alice");

        assertDoesNotThrow(() -> matchmakingQueue.cancel(aliceId));
    }

    @Test
    void join_threeUsersConcurrently_exactlyOneMatchHappens() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");
        int carolId = insertUser("carol");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch startSignal = new CountDownLatch(1);

        try {
            Future<GameStateDTO> aliceResult = executor.submit(joinTask(aliceId, gameTypeId, startSignal));
            Future<GameStateDTO> bobResult = executor.submit(joinTask(bobId, gameTypeId, startSignal));
            Future<GameStateDTO> carolResult = executor.submit(joinTask(carolId, gameTypeId, startSignal));

            startSignal.countDown();

            long matchedCount = 0;
            for (Future<GameStateDTO> future : List.of(aliceResult, bobResult, carolResult)) {
                if (future.get(5, TimeUnit.SECONDS) != null) {
                    matchedCount++;
                }
            }

            assertEquals(1, matchedCount, "exactly one of the three joins should have found a match");
            assertEquals(1, countQueueRows(), "exactly one user should still be waiting");
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<GameStateDTO> joinTask(int userId, int gameTypeId, CountDownLatch startSignal) {
        return () -> {
            startSignal.await();
            return matchmakingQueue.join(userId, gameTypeId, INITIAL_BOARD);
        };
    }

    private boolean hasQueueRowFor(int userId, int gameTypeId) throws Exception {
        String sql = "SELECT 1 FROM MatchmakingQueue WHERE UserID = ? AND GameTypeID = ? AND Status = 'WAITING'";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, gameTypeId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertActiveSessionDirectly(int player1Id, int player2Id, int gameTypeId) throws Exception {
        String sql = "INSERT INTO GameSession (GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?)";
        try (Connection conn = DATA_SOURCE.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setInt(4, player1Id);
            stmt.executeUpdate();
        }
    }

    private int countQueueRows() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM MatchmakingQueue");
             ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String queueStatusFor(int userId, int gameTypeId) throws Exception {
        String sql = "SELECT Status FROM MatchmakingQueue WHERE UserID = ? AND GameTypeID = ?";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, gameTypeId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "expected a MatchmakingQueue row for userId=" + userId + " gameTypeId=" + gameTypeId);
                return rs.getString("Status");
            }
        }
    }

    private int countGameSessions() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM GameSession");
             ResultSet rs = stmt.executeQuery()) {
            rs.next();
            return rs.getInt(1);
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

    private int insertGameSession(int gameTypeId, int player1Id, int player2Id, String status, Integer winnerId)
            throws Exception {
        String sql = "INSERT INTO GameSession "
                + "(GameTypeID, Player1ID, Player2ID, Status, WinnerID, BoardState, TurnStartedAt, StartTime) "
                + "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setString(4, status);
            if (winnerId == null) {
                stmt.setNull(5, java.sql.Types.INTEGER);
            } else {
                stmt.setInt(5, winnerId);
            }
            stmt.setString(6, INITIAL_BOARD);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }
}
