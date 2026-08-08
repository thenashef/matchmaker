package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.server.dao.DataSourceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingQueueTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(DATA_SOURCE);

    private int gameTypeId;

    @BeforeEach
    void cleanTablesAndInsertFixtures() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM GameSession");
            stmt.execute("DELETE FROM MatchmakingQueue");
            stmt.execute("DELETE FROM User");
            stmt.execute("DELETE FROM GameType");
        }
        gameTypeId = insertGameType("Checkers");
    }

    @Test
    void join_noOneWaiting_returnsNullAndQueuesCaller() throws Exception {
        int aliceId = insertUser("alice");

        GameStateDTO result = matchmakingQueue.join(aliceId, gameTypeId);

        assertNull(result);
        assertEquals(1, countQueueRows());
    }

    @Test
    void join_opponentWaiting_returnsMatchedSessionAndClearsQueue() throws Exception {
        int aliceId = insertUser("alice");
        int bobId = insertUser("bob");
        matchmakingQueue.join(aliceId, gameTypeId);

        GameStateDTO result = matchmakingQueue.join(bobId, gameTypeId);

        assertNotNull(result);
        assertEquals(gameTypeId, result.getGameTypeId());
        assertEquals(aliceId, result.getPlayer1Id());
        assertEquals(bobId, result.getPlayer2Id());
        assertEquals(GameStatus.ACTIVE, result.getStatus());
        assertEquals(aliceId, result.getCurrentTurnUserId());
        assertNull(result.getWinnerId());
        assertEquals(0, countQueueRows());
    }

    @Test
    void cancel_removesWaitingRow() throws Exception {
        int aliceId = insertUser("alice");
        matchmakingQueue.join(aliceId, gameTypeId);

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
            return matchmakingQueue.join(userId, gameTypeId);
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
