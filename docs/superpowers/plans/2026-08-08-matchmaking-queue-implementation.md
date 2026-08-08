# Matchmaking Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement roadmap step 5 exactly as designed in `docs/superpowers/specs/2026-08-08-matchmaking-queue-design.md` — a synchronized `MatchmakingQueue` component doing atomic opponent pairing, wired into real `PlayerServiceImpl.joinQueue()`/`.cancelQueue()`.

**Architecture:** New package `com.matchmaker.server.matchmaking` holding `MatchmakingQueue` (interface) + `JdbcMatchmakingQueue` (real, transactional, `synchronized` implementation) + `InMemoryMatchmakingQueue` (test-only fake), following the identical interface/impl/fake pattern the DAOs used in step 4. `PlayerService.joinQueue()`'s RMI return type changes from `void` to a nullable `GameStateDTO`. `PlayerServiceImpl` and `ServerMain` get the new dependency wired in.

**Tech Stack:** Java 21, Maven, JUnit 5, existing JDBC/HikariCP stack from step 4 (no new dependencies).

## Global Constraints

- New package: `com.matchmaker.server.matchmaking` (interface, `Jdbc*` implementation). Test fake lives in the mirrored test package `src/test/java/com/matchmaker/server/matchmaking/`.
- `MatchmakingQueue.join(int userId, int gameTypeId)` returns `GameStateDTO` — `null` means "no opponent yet, caller is now queued"; non-null means "matched, here is the new session."
- `join()` and `cancel()` on `JdbcMatchmakingQueue` are both Java `synchronized` instance methods, on the same lock (the instance itself) — both must be synchronized, not just `join()`, or a `cancel()` racing a `join()` mid-pairing can match a player who thought they'd backed out.
- Only ONE `MatchmakingQueue` row ever exists per matched pair — the player who calls `join()` and immediately finds an opponent never inserts a row of their own. Only the opponent's existing row gets deleted.
- `GameSession` fields on a fresh match: `Player1`=the player who was already waiting, `Player2`=the caller, `Status=ACTIVE`, `CurrentTurnUserID`=Player1, `TurnStartedAt`=`StartTime`=now, `BoardState=null` (left for step 7's game engine).
- Unexpected DB failures wrap in the existing `com.matchmaker.server.dao.DaoException` (reused as-is — it's a general "unexpected DB failure" wrapper, not DAO-package-specific by construction).
- `PlayerService.joinQueue()`'s RMI interface signature changes — this is a deliberate breaking change to an established Milestone-1 contract, not an oversight.
- `MatchmakingQueueTest` requires `docker compose up -d` running (real MySQL) — same requirement tier as `UserDaoTest`/`GameTypeDaoTest`/`GameSessionDaoTest` from step 4. Every other test in this plan (`PlayerServiceImplTest`) stays Docker-free via `InMemoryMatchmakingQueue`.

---

## File Structure

**Created:**
- `src/main/java/com/matchmaker/server/matchmaking/MatchmakingQueue.java` (Task 1)
- `src/main/java/com/matchmaker/server/matchmaking/JdbcMatchmakingQueue.java` (Task 1)
- `src/test/java/com/matchmaker/server/matchmaking/MatchmakingQueueTest.java` (Task 1)
- `src/test/java/com/matchmaker/server/matchmaking/InMemoryMatchmakingQueue.java` (Task 2)

**Modified:**
- `src/main/java/com/matchmaker/common/rmi/PlayerService.java` (Task 2 — `joinQueue()` return type)
- `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java` (Task 2)
- `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java` (Task 2)
- `src/main/java/com/matchmaker/server/ServerMain.java` (Task 3)
- `docs/build-plan.md` (Task 4)

---

### Task 1: `MatchmakingQueue`

**Files:**
- Create: `src/main/java/com/matchmaker/server/matchmaking/MatchmakingQueue.java`
- Create: `src/main/java/com/matchmaker/server/matchmaking/JdbcMatchmakingQueue.java`
- Test: `src/test/java/com/matchmaker/server/matchmaking/MatchmakingQueueTest.java`

**Interfaces:**
- Consumes: `com.matchmaker.server.dao.DataSourceFactory.create()`, `com.matchmaker.server.dao.DaoException`, `com.matchmaker.common.dto.GameStateDTO`, `com.matchmaker.common.enums.GameStatus` (all already exist), the `MatchmakingQueue`/`GameSession`/`GameType`/`User` tables (already exist).
- Produces: `MatchmakingQueue` interface (`GameStateDTO join(int userId, int gameTypeId)`, `void cancel(int userId)`), `JdbcMatchmakingQueue(DataSource)` — Task 2's `PlayerServiceImpl` rewire and Task 3's `ServerMain` both consume the interface type; Task 3 constructs `JdbcMatchmakingQueue` directly.

**Requires `docker compose up -d` running** before running this task's test.

- [ ] **Step 1: Write `MatchmakingQueue`**

```java
package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;

public interface MatchmakingQueue {
    GameStateDTO join(int userId, int gameTypeId);
    void cancel(int userId);
}
```

- [ ] **Step 2: Write the failing test**

```java
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
```

The concurrency test (`join_threeUsersConcurrently_exactlyOneMatchHappens`) submits all three `join()` calls to a thread pool first (so each thread is already running and blocked on the latch), *then* releases them all at once via `countDown()` — this maximizes real overlap between the three calls, which is what actually exercises the `synchronized` guarantee rather than just calling `join()` three times in sequence.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=MatchmakingQueueTest`
Expected: compilation failure — `JdbcMatchmakingQueue` doesn't exist yet.

- [ ] **Step 4: Write `JdbcMatchmakingQueue`**

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=MatchmakingQueueTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0` (requires `docker compose up -d`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matchmaker/server/matchmaking/MatchmakingQueue.java src/main/java/com/matchmaker/server/matchmaking/JdbcMatchmakingQueue.java src/test/java/com/matchmaker/server/matchmaking/MatchmakingQueueTest.java
git commit -m "Add MatchmakingQueue with atomic, synchronized opponent pairing"
```

---

### Task 2: Rewire `PlayerServiceImpl.joinQueue()`/`.cancelQueue()`

**Files:**
- Create: `src/test/java/com/matchmaker/server/matchmaking/InMemoryMatchmakingQueue.java`
- Modify: `src/main/java/com/matchmaker/common/rmi/PlayerService.java`
- Modify: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`
- Modify: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`

**Interfaces:**
- Consumes: `MatchmakingQueue` (Task 1), `SessionManager`/`GameSessionDao`/`GameTypeDao` (existing).
- Produces: `PlayerService.joinQueue(String, int) -> GameStateDTO` (**return type changes** from `void`). `PlayerServiceImpl(SessionManager, GameSessionDao, GameTypeDao, MatchmakingQueue)` — **constructor signature changes** (adds a 4th parameter) — Task 3's `ServerMain` uses the new signature.

No Docker required — this task's test uses `InMemoryMatchmakingQueue`.

- [ ] **Step 1: Write `InMemoryMatchmakingQueue`**

```java
package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryMatchmakingQueue implements MatchmakingQueue {

    private final Map<Integer, Integer> waitingUserIdByGameTypeId = new HashMap<>();
    private final AtomicInteger nextSessionId = new AtomicInteger(1);

    @Override
    public synchronized GameStateDTO join(int userId, int gameTypeId) {
        Integer opponentUserId = waitingUserIdByGameTypeId.get(gameTypeId);
        if (opponentUserId == null) {
            waitingUserIdByGameTypeId.put(gameTypeId, userId);
            return null;
        }
        waitingUserIdByGameTypeId.remove(gameTypeId);
        return new GameStateDTO(nextSessionId.getAndIncrement(), gameTypeId, opponentUserId, userId,
                GameStatus.ACTIVE, opponentUserId, null, null);
    }

    @Override
    public synchronized void cancel(int userId) {
        waitingUserIdByGameTypeId.values().removeIf(waitingUserId -> waitingUserId.equals(userId));
    }
}
```

- [ ] **Step 2: Rewrite the failing test**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import com.matchmaker.server.matchmaking.InMemoryMatchmakingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerServiceImplTest {

    private SessionManager sessionManager;
    private InMemoryGameSessionDao gameSessionDao;
    private InMemoryGameTypeDao gameTypeDao;
    private InMemoryMatchmakingQueue matchmakingQueue;
    private PlayerServiceImpl playerService;
    private String sessionToken;

    @BeforeEach
    void createPlayerService() throws Exception {
        sessionManager = new SessionManager();
        gameSessionDao = new InMemoryGameSessionDao();
        gameTypeDao = new InMemoryGameTypeDao();
        matchmakingQueue = new InMemoryMatchmakingQueue();
        playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue);
        sessionToken = sessionManager.createSession(1);
    }

    @AfterEach
    void unexportPlayerService() {
        if (playerService != null) {
            try { UnicastRemoteObject.unexportObject(playerService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void listGameTypes_returnsWhatDaoReturns() throws Exception {
        gameTypeDao.add(new GameTypeDTO(1, "Checkers", "desc", 2, 2, 8, 8));

        List<GameTypeDTO> result = playerService.listGameTypes(sessionToken);

        assertEquals(1, result.size());
        assertEquals("Checkers", result.get(0).getName());
    }

    @Test
    void listGameTypes_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.listGameTypes("bogus-token"));
    }

    @Test
    void getHistory_returnsFinishedSessionsForCaller() throws Exception {
        GameStateDTO finished = new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board");
        gameSessionDao.addFinishedSession(finished);

        List<GameStateDTO> history = playerService.getHistory(sessionToken);

        assertEquals(1, history.size());
        assertEquals(1, history.get(0).getSessionId());
    }

    @Test
    void getHistory_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.getHistory("bogus-token"));
    }

    @Test
    void joinQueue_noOpponentWaiting_returnsNull() throws Exception {
        GameStateDTO result = playerService.joinQueue(sessionToken, 1);

        assertNull(result);
    }

    @Test
    void joinQueue_opponentWaiting_returnsMatchedSession() throws Exception {
        String otherToken = sessionManager.createSession(2);
        playerService.joinQueue(otherToken, 1);

        GameStateDTO result = playerService.joinQueue(sessionToken, 1);

        assertNotNull(result);
        assertEquals(GameStatus.ACTIVE, result.getStatus());
    }

    @Test
    void joinQueue_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.joinQueue("bogus-token", 1));
    }

    @Test
    void cancelQueue_validToken_doesNotThrow() throws Exception {
        playerService.joinQueue(sessionToken, 1);

        assertDoesNotThrow(() -> playerService.cancelQueue(sessionToken));
    }

    @Test
    void cancelQueue_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.cancelQueue("bogus-token"));
    }

    @Test
    void remainingMethods_stillThrowUnsupportedOperationException() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> playerService.makeMove(sessionToken, 1, "{}"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.sendChatMessage(sessionToken, 1, "hi"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.resign(sessionToken, 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.rematch(sessionToken, 1));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: compilation failure — `PlayerServiceImpl`'s 4-arg constructor and `joinQueue()`'s `GameStateDTO` return type don't exist yet.

- [ ] **Step 4: Update `PlayerService.java`**

Change only the `joinQueue` signature (everything else in the file stays the same):

```java
GameStateDTO joinQueue(String sessionToken, int gameTypeId)
    throws RemoteException, AuthenticationException;
```

(replaces the current `void joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException;`)

- [ ] **Step 5: Rewrite `PlayerServiceImpl`**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.rmi.PlayerService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.matchmaking.MatchmakingQueue;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class PlayerServiceImpl extends UnicastRemoteObject implements PlayerService {

    private final SessionManager sessionManager;
    private final GameSessionDao gameSessionDao;
    private final GameTypeDao gameTypeDao;
    private final MatchmakingQueue matchmakingQueue;

    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao,
                              MatchmakingQueue matchmakingQueue) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
        this.matchmakingQueue = matchmakingQueue;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        return matchmakingQueue.join(userId, gameTypeId);
    }

    @Override
    public void cancelQueue(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        matchmakingQueue.cancel(userId);
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException {
        throw new UnsupportedOperationException("makeMove not implemented yet -- see build-plan.md step 7");
    }

    @Override
    public void sendChatMessage(String sessionToken, int gameSessionId, String content)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("sendChatMessage not implemented yet -- see build-plan.md step 6");
    }

    @Override
    public void resign(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("resign not implemented yet -- see build-plan.md step 7");
    }

    @Override
    public GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("rematch not implemented yet -- see build-plan.md step 10");
    }

    @Override
    public List<GameStateDTO> getHistory(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        return gameSessionDao.findFinishedSessionsForUser(userId);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0` — no Docker required.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matchmaker/common/rmi/PlayerService.java src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java src/test/java/com/matchmaker/server/matchmaking/InMemoryMatchmakingQueue.java
git commit -m "Wire PlayerServiceImpl.joinQueue/cancelQueue to MatchmakingQueue"
```

---

### Task 3: Wire `ServerMain` to the real `MatchmakingQueue`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/ServerMain.java`

**Interfaces:**
- Consumes: `JdbcMatchmakingQueue` (Task 1), the new `PlayerServiceImpl` 4-arg constructor (Task 2).
- Produces: nothing later tasks depend on — this is the runnable entry point.

- [ ] **Step 1: Rewrite `ServerMain`**

```java
package com.matchmaker.server;

import com.matchmaker.server.dao.DataSourceFactory;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.JdbcGameSessionDao;
import com.matchmaker.server.dao.JdbcGameTypeDao;
import com.matchmaker.server.dao.JdbcUserDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.matchmaking.JdbcMatchmakingQueue;
import com.matchmaker.server.matchmaking.MatchmakingQueue;
import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;

import javax.sql.DataSource;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {

    public static final int PORT = 1099;

    public static void main(String[] args) throws Exception {
        start(PORT);

        System.out.println("MatchMaker RMI registry started on port " + PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");

        // RMI's exported objects are served by daemon-ish background threads that don't, on their
        // own, keep the launching JVM alive -- under `mvn exec:java` Maven exits as soon as main()
        // returns and port 1099 closes with it. Block the main thread forever so the server stays
        // up until the process is killed (Ctrl-C / SIGTERM).
        Thread.currentThread().join();
    }

    public static Registry start(int port) throws RemoteException {
        return startWithImpls(port).registry();
    }

    /**
     * Package-private variant of {@link #start(int)} that also hands back the constructed
     * service impls (not just the registry), so tests can unexport each one individually in
     * teardown -- {@code registry.lookup(...)} only ever returns a client-side stub, which
     * can't be passed to {@code UnicastRemoteObject.unexportObject}.
     */
    static Started startWithImpls(int port) throws RemoteException {
        SessionManager sessionManager = new SessionManager();

        DataSource dataSource = DataSourceFactory.create();
        UserDao userDao = new JdbcUserDao(dataSource);
        GameSessionDao gameSessionDao = new JdbcGameSessionDao(dataSource);
        GameTypeDao gameTypeDao = new JdbcGameTypeDao(dataSource);
        MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(dataSource);

        Registry registry = LocateRegistry.createRegistry(port);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);

        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        return new Started(registry, authService, playerService, adminService);
    }

    record Started(Registry registry, AuthServiceImpl authService, PlayerServiceImpl playerService,
                    AdminServiceImpl adminService) {
    }
}
```

- [ ] **Step 2: Run `ServerMainTest` to confirm it still passes**

Run: `mvn test -Dtest=ServerMainTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0` — no Docker required (same lazy-pool reasoning as step 4; this test never calls a `MatchmakingQueue` method, just checks registry binding).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/matchmaker/server/ServerMain.java
git commit -m "Wire ServerMain to real JdbcMatchmakingQueue"
```

---

### Task 4: Update `build-plan.md` and full verification

**Files:**
- Modify: `docs/build-plan.md`

**Interfaces:** N/A — documentation + verification only.

- [ ] **Step 1: Update `docs/build-plan.md`**

In "What's Implemented So Far", add a new milestone subsection (after the existing JDBC/DAO-layer milestone, matching its style) documenting: the `MatchmakingQueue` interface/`JdbcMatchmakingQueue`/`InMemoryMatchmakingQueue`, that pairing is atomic and `synchronized` (one JVM-level lock, proven by a real multi-threaded test, not just DB constraints), that `PlayerServiceImpl.joinQueue()`/`.cancelQueue()` are now real, and that `PlayerService.joinQueue()`'s RMI return type changed from `void` to `GameStateDTO`. Link `docs/superpowers/specs/2026-08-08-matchmaking-queue-design.md` and this plan file.

In "Next Steps", update the immediate focus to step 6 (JMS setup — ActiveMQ topics for async server→client push, starting with notifying the player who was already waiting when a match happens).

In "Verification", update the Docker-requirement line to include `MatchmakingQueueTest` alongside the existing three DAO test classes, and update the `mvn test` passing-count bullet to the actual final test count (determine this by running the suite in Step 2 below — don't guess a number here in the plan).

- [ ] **Step 2: Run the full test suite with Docker up**

Run: `docker compose up -d && mvn test`
Expected: all tests pass, `BUILD SUCCESS`. Record the exact test count for Step 1's doc update above.

- [ ] **Step 3: Confirm working tree is clean**

Run: `git status`
Expected: nothing to commit except the `build-plan.md` update from Step 1.

- [ ] **Step 4: Commit**

```bash
git add docs/build-plan.md
git commit -m "Update build-plan.md: matchmaking queue complete, next focus is JMS"
```

---

## What comes after this plan

Roadmap step 6 (`docs/build-plan.md`) is next: JMS setup via ActiveMQ — a topic per game session, a server-side producer, and a minimal standalone consumer to prove messages arrive. The first real use is notifying the player who was already queued the moment `MatchmakingQueue.join()` pairs them with someone else — closing the gap this plan deliberately left open (the already-waiting player currently has no way to learn about a match after their own `joinQueue()` call already returned `null`). That's a separate, later plan.
