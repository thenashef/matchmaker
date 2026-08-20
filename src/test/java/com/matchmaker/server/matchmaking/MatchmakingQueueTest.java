package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.server.TestDatabase;
import com.matchmaker.server.dao.DataSourceFactory;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.JdbcGameSessionDao;
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

    // Stands in for whatever GameEngine.initialState() the caller would pass. The queue is
    // game-agnostic and never parses this -- it only has to store it -- so a recognisable
    // sentinel makes it obvious in assertions that the value round-tripped unchanged.
    private static final String INITIAL_BOARD = "{\"rows\":8,\"cols\":8,\"pieces\":{\"a1\":\"b\"}}";

    private final MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(DATA_SOURCE);
    private final GameSessionDao gameSessionDao = new JdbcGameSessionDao(DATA_SOURCE);

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

        // Regression check: JdbcMatchmakingQueue and JdbcGameSessionDao must agree on how
        // TurnStartedAt is written/read, or SessionWatchdog's turn-timeout check can fire
        // immediately (or never) on a DB/JVM timezone mismatch -- see currentTurnStartedAt()'s
        // own test for the read-side half of this same guard.
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

        // Returned to the caller...
        assertEquals(INITIAL_BOARD, result.getBoardState(),
                "the matched session handed back must carry the opening position, not null");
        // ...and, more importantly, actually written to the row. Everything that reads the
        // session back independently -- the admin live monitor, SessionWatchdog's abandon push,
        // an admin force-end -- used to get a null board here and throw rendering it.
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
    }

    @Test
    void join_alreadyQueuedForDifferentGameType_returnsNullAndDoesNotDisturbEitherQueue() throws Exception {
        int chessGameTypeId = insertGameType("Chess");
        int bobId = insertUser("bob");
        int aliceId = insertUser("alice");

        // Bob joins chess: no opponent, Bob is now WAITING for chess.
        GameStateDTO bobJoin = matchmakingQueue.join(bobId, chessGameTypeId, INITIAL_BOARD);
        // Alice joins checkers: no opponent, Alice is now WAITING for checkers.
        GameStateDTO aliceJoinCheckers = matchmakingQueue.join(aliceId, gameTypeId, INITIAL_BOARD);

        // Alice then joins chess: the opponent lookup would find Bob waiting for chess,
        // but Alice already has a WAITING row (checkers), so this must be a no-op.
        GameStateDTO aliceJoinChess = matchmakingQueue.join(aliceId, chessGameTypeId, INITIAL_BOARD);

        assertNull(bobJoin);
        assertNull(aliceJoinCheckers);
        assertNull(aliceJoinChess, "Alice already has a WAITING row, so joining a second game type must no-op");
        assertEquals(2, countQueueRows(), "Bob's chess row and Alice's checkers row must both remain untouched");
        assertEquals("WAITING", queueStatusFor(bobId, chessGameTypeId),
                "Bob's chess row must still be WAITING, not matched into a session");
        assertEquals("WAITING", queueStatusFor(aliceId, gameTypeId),
                "Alice's checkers row must still be WAITING, not matched into a session");
        assertEquals(0, countGameSessions(), "no GameSession should have been created");
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
}
