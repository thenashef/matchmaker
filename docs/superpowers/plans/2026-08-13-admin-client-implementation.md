# Admin Client (Roadmap Step 9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real, DAO-backed `AdminService` (currently five `UnsupportedOperationException` stubs) plus a JavaFX admin client — Dashboard, Add Game Type, Live Session Monitor — wired to it, per `docs/specs/2026-08-13-admin-client-design.md`. That doc has the full rationale for every decision; this plan is the "how." TDD discipline and the commit-per-task cadence follow the exact pattern established across every prior milestone (see `docs/superpowers/plans/2026-08-13-player-client-implementation.md` for the fullest worked example).

## Global Constraints

- `admin.communication`, `admin.logic`, `admin.presentation` do not import from `client.*` or `server.*` — same independence rule the player client follows, for the same reason (spec draws Admin Client as its own box).
- Every DAO addition follows the exact query/exception style already in its file (`DaoException` wrapping `SQLException`, `Optional` for "not found," no ORM).
- `AdminServiceImpl`'s five methods all start with the same `requireAdmin(sessionToken)` preamble — write it once, in Task 3, before any method needs it a second time.
- Every task ends in its own commit.

---

### Task 1: DAO extensions

**Files:**
- Modify: `UserDao.java`, `JdbcUserDao.java`, `GameTypeDao.java`, `JdbcGameTypeDao.java`, `GameSessionDao.java`, `JdbcGameSessionDao.java`
- Modify: `InMemoryUserDao.java`, `InMemoryGameTypeDao.java`, `InMemoryGameSessionDao.java` (test fakes)
- Modify: `UserDaoTest.java`, `GameTypeDaoTest.java`, `GameSessionDaoTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `UserDaoTest.java`:
```java
@Test
void findById_existingUser_returnsRecord() {
    Optional<UserRecord> inserted = userDao.insert("carol", "hash");

    Optional<UserRecord> found = userDao.findById(inserted.get().id());

    assertEquals("carol", found.get().username());
}

@Test
void findById_unknownId_returnsEmpty() {
    assertTrue(userDao.findById(999999).isEmpty());
}

@Test
void findAll_returnsEveryUser() {
    userDao.insert("carol", "hash");
    userDao.insert("dave", "hash");

    List<UserRecord> all = userDao.findAll();

    assertEquals(2, all.size());
}
```
(Add `import java.util.List;` if not already present.)

Add to `GameTypeDaoTest.java`:
```java
@Test
void insert_returnsTheCreatedGameTypeWithARealId() {
    GameTypeDTO created = gameTypeDao.insert(new GameTypeDTO(0, "Battleship", "Naval combat", 2, 2, 10, 10));

    assertTrue(created.getId() > 0);
    assertEquals("Battleship", created.getName());
    assertEquals(1, gameTypeDao.findAll().size());
}
```

Add to `GameSessionDaoTest.java` (mirror however that file already sets up an active session fixture -- likely a helper or direct SQL insert; follow whatever `findActiveById`'s existing test already uses):
```java
@Test
void findAllActive_returnsOnlyActiveSessions() {
    // insert one ACTIVE and one FINISHED session via the existing fixture helper in this file
    // ... (match the file's existing fixture-insertion style exactly)

    List<GameStateDTO> active = gameSessionDao.findAllActive();

    assertEquals(1, active.size());
    assertEquals(GameStatus.ACTIVE, active.get(0).getStatus());
}

@Test
void forceEnd_activeSession_setsAbandonedNoWinner() {
    // insert one ACTIVE session, capture its id

    Optional<GameStateDTO> result = gameSessionDao.forceEnd(sessionId);

    assertEquals(GameStatus.ABANDONED, result.get().getStatus());
    assertNull(result.get().getWinnerId());
}

@Test
void forceEnd_alreadyFinishedSession_returnsEmpty() {
    // insert one FINISHED session, capture its id

    assertTrue(gameSessionDao.forceEnd(sessionId).isEmpty());
}
```

- [ ] **Step 2: Run to verify they fail** — `docker compose up -d && mvn test -Dtest=UserDaoTest,GameTypeDaoTest,GameSessionDaoTest` — compile errors, the new DAO methods don't exist yet.

- [ ] **Step 3: Extend the interfaces and real implementations**

`UserDao.java`:
```java
public interface UserDao {
    Optional<UserRecord> insert(String username, String passwordHash);
    Optional<UserRecord> findByUsername(String username);
    Optional<UserRecord> findById(int id);
    List<UserRecord> findAll();
}
```

`JdbcUserDao.java` additions:
```java
@Override
public Optional<UserRecord> findById(int id) {
    String sql = "SELECT ID, Username, Password, IsAdmin, Wins, Losses, Draws, Rating, CreatedAt "
            + "FROM User WHERE ID = ?";
    try (Connection conn = dataSource.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setInt(1, id);
        try (ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(mapRow(rs));
        }
    } catch (SQLException e) {
        throw new DaoException("Failed to find user " + id, e);
    }
}

@Override
public List<UserRecord> findAll() {
    String sql = "SELECT ID, Username, Password, IsAdmin, Wins, Losses, Draws, Rating, CreatedAt "
            + "FROM User ORDER BY ID";
    List<UserRecord> result = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql);
         ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
            result.add(mapRow(rs));
        }
        return result;
    } catch (SQLException e) {
        throw new DaoException("Failed to list users", e);
    }
}

private static UserRecord mapRow(ResultSet rs) throws SQLException {
    return new UserRecord(
            rs.getInt("ID"), rs.getString("Username"), rs.getString("Password"), rs.getBoolean("IsAdmin"),
            rs.getInt("Wins"), rs.getInt("Losses"), rs.getInt("Draws"), rs.getInt("Rating"),
            rs.getTimestamp("CreatedAt").toLocalDateTime());
}
```
Refactor `findByUsername` to also call `mapRow(rs)` instead of its inline construction, so there's one row-mapping path. Add `import java.util.ArrayList; import java.util.List;`.

`GameTypeDao.java`:
```java
public interface GameTypeDao {
    List<GameTypeDTO> findAll();
    GameTypeDTO insert(GameTypeDTO newGameType);
}
```

`JdbcGameTypeDao.java` addition:
```java
@Override
public GameTypeDTO insert(GameTypeDTO newGameType) {
    String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
            + "VALUES (?, ?, ?, ?, ?, ?)";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
        stmt.setString(1, newGameType.getName());
        stmt.setString(2, newGameType.getDescription());
        stmt.setInt(3, newGameType.getMinPlayers());
        stmt.setInt(4, newGameType.getMaxPlayers());
        stmt.setInt(5, newGameType.getBoardRows());
        stmt.setInt(6, newGameType.getBoardCols());
        stmt.executeUpdate();
        try (ResultSet keys = stmt.getGeneratedKeys()) {
            keys.next();
            int newId = keys.getInt(1);
            return new GameTypeDTO(newId, newGameType.getName(), newGameType.getDescription(),
                    newGameType.getMinPlayers(), newGameType.getMaxPlayers(),
                    newGameType.getBoardRows(), newGameType.getBoardCols());
        }
    } catch (SQLException e) {
        throw new DaoException("Failed to insert game type '" + newGameType.getName() + "'", e);
    }
}
```
Add `import java.sql.Statement;`.

`GameSessionDao.java`:
```java
public interface GameSessionDao {
    List<GameStateDTO> findFinishedSessionsForUser(int userId);
    Optional<GameStateDTO> findActiveById(int sessionId);
    List<GameStateDTO> findAllActive();
    GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson);
    Optional<GameStateDTO> forceEnd(int sessionId);
}
```

`JdbcGameSessionDao.java` additions:
```java
@Override
public List<GameStateDTO> findAllActive() {
    String sql = "SELECT ID, GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, WinnerID, BoardState "
            + "FROM GameSession WHERE Status = 'ACTIVE' ORDER BY StartTime";
    List<GameStateDTO> result = new ArrayList<>();
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql);
         ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
            result.add(mapRow(rs));
        }
        return result;
    } catch (SQLException e) {
        throw new DaoException("Failed to list active sessions", e);
    }
}

@Override
public Optional<GameStateDTO> forceEnd(int sessionId) {
    // Mirrors recordMove()'s guarded-UPDATE pattern: WHERE ... AND Status = 'ACTIVE' means a
    // session that already ended naturally (a player won) in the gap before this call commits
    // just yields 0 rows updated -- treated as "nothing to force-end," not an error.
    String sql = "UPDATE GameSession SET Status = 'ABANDONED', WinnerID = NULL, EndTime = NOW() "
            + "WHERE ID = ? AND Status = 'ACTIVE'";
    try (Connection conn = dataSource.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setInt(1, sessionId);
        int rowsUpdated = stmt.executeUpdate();
        if (rowsUpdated == 0) {
            return Optional.empty();
        }
        return findAbandonedById(conn, sessionId);
    } catch (SQLException e) {
        throw new DaoException("Failed to force-end session " + sessionId, e);
    }
}

private Optional<GameStateDTO> findAbandonedById(Connection conn, int sessionId) throws SQLException {
    String sql = "SELECT ID, GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, WinnerID, BoardState "
            + "FROM GameSession WHERE ID = ?";
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setInt(1, sessionId);
        try (ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(mapRow(rs));
        }
    }
}
```

- [ ] **Step 4: Extend the `InMemory*Dao` test fakes**

`InMemoryUserDao.java` additions:
```java
@Override
public synchronized Optional<UserRecord> findById(int id) {
    return usersByUsername.values().stream().filter(u -> u.id() == id).findFirst();
}

@Override
public synchronized List<UserRecord> findAll() {
    return new ArrayList<>(usersByUsername.values());
}
```
Add `import java.util.ArrayList; import java.util.List;`.

`InMemoryGameTypeDao.java` addition:
```java
private final AtomicInteger nextId = new AtomicInteger(1);

@Override
public GameTypeDTO insert(GameTypeDTO newGameType) {
    GameTypeDTO created = new GameTypeDTO(nextId.getAndIncrement(), newGameType.getName(),
            newGameType.getDescription(), newGameType.getMinPlayers(), newGameType.getMaxPlayers(),
            newGameType.getBoardRows(), newGameType.getBoardCols());
    gameTypes.add(created);
    return created;
}
```
Add `import java.util.concurrent.atomic.AtomicInteger;`.

`InMemoryGameSessionDao.java` additions:
```java
@Override
public List<GameStateDTO> findAllActive() {
    List<GameStateDTO> result = new ArrayList<>();
    for (GameStateDTO session : sessions) {
        if (session.getStatus() == GameStatus.ACTIVE) {
            result.add(session);
        }
    }
    return result;
}

@Override
public Optional<GameStateDTO> forceEnd(int sessionId) {
    for (GameStateDTO session : sessions) {
        if (session.getSessionId() == sessionId && session.getStatus() == GameStatus.ACTIVE) {
            GameStateDTO ended = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                    session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.ABANDONED, null, null,
                    session.getBoardState());
            sessions.removeIf(s -> s.getSessionId() == sessionId);
            sessions.add(ended);
            return Optional.of(ended);
        }
    }
    return Optional.empty();
}
```

- [ ] **Step 5: Run to verify all pass** — `mvn test -Dtest=UserDaoTest,GameTypeDaoTest,GameSessionDaoTest` — PASS.

- [ ] **Step 6: Run the full suite** — `mvn test` — everything still green (this touches shared DAO interfaces, so confirm nothing else broke).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matchmaker/server/dao/ src/test/java/com/matchmaker/server/dao/
git commit -m "Add DAO methods needed for the admin client: findById/findAll, insert, findAllActive/forceEnd"
```

---

### Task 2: `SESSION_FORCE_ENDED` event type + `GameClientService` update

**Files:** Modify `GameEventType.java`, `GameClientService.java`, `GameClientServiceTest.java`.

- [ ] **Step 1: Write the failing test** — add to `GameClientServiceTest.java`:
```java
@Test
void enterGame_pushedSessionForceEndedEvent_alsoReachesTheAttachedGameUpdateListener() throws Exception {
    loginAsUser(1);
    GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
    serverConnection.setJoinQueueResult(matched);
    await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(String.valueOf(err))));

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<GameStateDTO> captured = new AtomicReference<>();
    service.attachGameUpdateListener(state -> { captured.set(state); latch.countDown(); });

    GameStateDTO abandoned = new GameStateDTO(5, 1, 1, 2, GameStatus.ABANDONED, null, null, "{\"pieces\":{}}");
    serverConnection.fireSessionTopicEvent(5, new GameEventDTO(GameEventType.SESSION_FORCE_ENDED, 5, abandoned));

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertEquals(GameStatus.ABANDONED, captured.get().getStatus());
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn test -Dtest=GameClientServiceTest` — compile error, `SESSION_FORCE_ENDED` doesn't exist yet.

- [ ] **Step 3: Add the enum value**
```java
public enum GameEventType {
    MATCH_FOUND,
    MOVE_MADE,
    SESSION_FORCE_ENDED
}
```

- [ ] **Step 4: Widen `GameClientService.onSessionTopicEvent()`'s filter**
```java
private void onSessionTopicEvent(GameEventDTO event) {
    if (event.getType() != GameEventType.MOVE_MADE && event.getType() != GameEventType.SESSION_FORCE_ENDED) {
        return;
    }
    Platform.runLater(() -> {
        currentGameState = event.getGameState();
        if (gameUpdateListener != null) {
            gameUpdateListener.accept(currentGameState);
        }
    });
}
```

- [ ] **Step 5: Run to verify it passes, then the full suite** — `mvn test -Dtest=GameClientServiceTest` then `mvn test`.

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/matchmaker/common/enums/GameEventType.java \
        src/main/java/com/matchmaker/client/logic/GameClientService.java \
        src/test/java/com/matchmaker/client/logic/GameClientServiceTest.java
git commit -m "Add SESSION_FORCE_ENDED event type; GameClientService now reacts to it like MOVE_MADE"
```

---

### Task 3: Real `AdminServiceImpl` + `ServerMain` wiring

**Files:** Modify `AdminServiceImpl.java`, `AdminServiceImplTest.java`, `ServerMain.java`.

- [ ] **Step 1: Write the failing tests** — replace `AdminServiceImplTest.java`'s single "everything throws" test with real coverage, using the `InMemory*Dao` fakes:
```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import com.matchmaker.server.dao.InMemoryUserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdminServiceImplTest {

    private SessionManager sessionManager;
    private InMemoryUserDao userDao;
    private InMemoryGameTypeDao gameTypeDao;
    private InMemoryGameSessionDao gameSessionDao;
    private AdminServiceImpl adminService;
    private String adminToken;
    private String playerToken;

    @BeforeEach
    void createAdminService() throws Exception {
        sessionManager = new SessionManager();
        userDao = new InMemoryUserDao();
        gameTypeDao = new InMemoryGameTypeDao();
        gameSessionDao = new InMemoryGameSessionDao();
        adminService = new AdminServiceImpl(sessionManager, userDao, gameTypeDao, gameSessionDao);

        Optional<com.matchmaker.server.dao.UserRecord> admin = userDao.insert("admin", "hash");
        userDao.markAdmin(admin.get().id()); // see Step 3 note below
        adminToken = sessionManager.createSession(admin.get().id());

        Optional<com.matchmaker.server.dao.UserRecord> player = userDao.insert("player", "hash");
        playerToken = sessionManager.createSession(player.get().id());
    }

    @AfterEach
    void unexportAdminService() {
        if (adminService != null) {
            try { UnicastRemoteObject.unexportObject(adminService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void listGameTypes_asAdmin_returnsWhatDaoReturns() throws Exception {
        gameTypeDao.add(new GameTypeDTO(1, "Checkers", "desc", 2, 2, 8, 8));

        List<GameTypeDTO> result = adminService.listGameTypes(adminToken);

        assertEquals(1, result.size());
    }

    @Test
    void listGameTypes_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.listGameTypes(playerToken));
    }

    @Test
    void addGameType_asAdmin_insertsAndReturnsCreated() throws Exception {
        GameTypeDTO created = adminService.addGameType(adminToken,
                new GameTypeDTO(0, "Battleship", "Naval combat", 2, 2, 10, 10));

        assertTrue(created.getId() > 0);
        assertEquals(1, gameTypeDao.findAll().size());
    }

    @Test
    void listUsers_asAdmin_returnsEveryUser() throws Exception {
        List<UserDTO> result = adminService.listUsers(adminToken);

        assertEquals(2, result.size()); // admin + player, both inserted in setUp
    }

    @Test
    void listActiveSessions_asAdmin_returnsOnlyActiveOnes() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));
        gameSessionDao.addFinishedSession(new GameStateDTO(2, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

        List<GameStateDTO> result = adminService.listActiveSessions(adminToken);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getSessionId());
    }

    @Test
    void forceEndSession_asAdmin_endsIt() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));

        adminService.forceEndSession(adminToken, 1);

        assertTrue(gameSessionDao.findActiveById(1).isEmpty());
    }

    @Test
    void forceEndSession_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.forceEndSession(playerToken, 1));
    }
}
```

**Note on `userDao.markAdmin(...)`** in Step 1 above: `InMemoryUserDao.insert()` always creates a non-admin record (mirrors `JdbcUserDao`'s real behavior — nothing about registration ever sets `IsAdmin`). Add a tiny test-only helper to `InMemoryUserDao` for exactly this:
```java
public synchronized void markAdmin(int userId) {
    usersByUsername.replaceAll((username, record) -> record.id() == userId
            ? new UserRecord(record.id(), record.username(), record.passwordHash(), true,
                    record.wins(), record.losses(), record.draws(), record.rating(), record.createdAt())
            : record);
}
```

- [ ] **Step 2: Run to verify they fail** — `mvn test -Dtest=AdminServiceImplTest` — compile errors (constructor signature, `markAdmin` missing).

- [ ] **Step 3: Implement `AdminServiceImpl`**
```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.dao.UserRecord;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class AdminServiceImpl extends UnicastRemoteObject implements AdminService {

    private final SessionManager sessionManager;
    private final UserDao userDao;
    private final GameTypeDao gameTypeDao;
    private final GameSessionDao gameSessionDao;

    public AdminServiceImpl(SessionManager sessionManager, UserDao userDao, GameTypeDao gameTypeDao,
                             GameSessionDao gameSessionDao) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.userDao = userDao;
        this.gameTypeDao = gameTypeDao;
        this.gameSessionDao = gameSessionDao;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return gameTypeDao.insert(newGameType);
    }

    @Override
    public List<UserDTO> listUsers(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return userDao.findAll().stream()
                .map(record -> new UserDTO(record.id(), record.username(), record.admin(),
                        record.wins(), record.losses(), record.draws(), record.rating()))
                .toList();
    }

    @Override
    public List<GameStateDTO> listActiveSessions(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return gameSessionDao.findAllActive();
    }

    @Override
    public void forceEndSession(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        gameSessionDao.forceEnd(gameSessionId);
        // Notifying the two players via the session's JMS topic is wired in Task 4 alongside
        // the rest of the admin communication layer, once GameEventPublisher is available here.
    }

    private UserRecord requireAdmin(String sessionToken) throws AuthenticationException, NotAdminException {
        int userId = sessionManager.resolve(sessionToken);
        UserRecord record = userDao.findById(userId)
                .orElseThrow(() -> new NotAdminException("User " + userId + " no longer exists"));
        if (!record.admin()) {
            throw new NotAdminException("User " + userId + " is not an admin");
        }
        return record;
    }
}
```

**Note:** this step deliberately does *not* yet publish `SESSION_FORCE_ENDED` — `AdminServiceImpl` doesn't have a `GameEventPublisher` dependency yet. That's added in Step 4 below, once the constructor signature is settled here, to keep this step's diff focused on the DAO wiring and the `requireAdmin` guard.

- [ ] **Step 4: Add the `GameEventPublisher` dependency and publish on force-end**

Widen the constructor once more:
```java
private final GameEventPublisher gameEventPublisher;

public AdminServiceImpl(SessionManager sessionManager, UserDao userDao, GameTypeDao gameTypeDao,
                         GameSessionDao gameSessionDao, GameEventPublisher gameEventPublisher) throws RemoteException {
    super();
    this.sessionManager = sessionManager;
    this.userDao = userDao;
    this.gameTypeDao = gameTypeDao;
    this.gameSessionDao = gameSessionDao;
    this.gameEventPublisher = gameEventPublisher;
}
```

Replace the comment in `forceEndSession`:
```java
@Override
public void forceEndSession(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotAdminException {
    requireAdmin(sessionToken);
    gameSessionDao.forceEnd(gameSessionId).ifPresent(ended -> {
        try {
            gameEventPublisher.publishToSession(gameSessionId,
                    new GameEventDTO(GameEventType.SESSION_FORCE_ENDED, gameSessionId, ended));
        } catch (JmsPublishException e) {
            // The DB update already committed -- a failed notification shouldn't fail this
            // admin call. Mirrors PlayerServiceImpl.makeMove()'s identical handling.
            System.err.println("Failed to notify session " + gameSessionId + " of force-end: " + e.getMessage());
        }
    });
}
```
Add imports: `com.matchmaker.common.dto.GameEventDTO`, `com.matchmaker.common.enums.GameEventType`, `com.matchmaker.server.jms.GameEventPublisher`, `com.matchmaker.server.jms.JmsPublishException`.

Update `AdminServiceImplTest`'s `createAdminService()` to pass an `InMemoryGameEventPublisher` as the fifth constructor argument, and add one more test:
```java
@Test
void forceEndSession_asAdmin_publishesSessionForceEndedEvent() throws Exception {
    gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));

    adminService.forceEndSession(adminToken, 1);

    assertEquals(1, gameEventPublisher.publishedToSessions().size());
    assertEquals(GameEventType.SESSION_FORCE_ENDED,
            gameEventPublisher.publishedToSessions().get(0).event().getType());
}
```

- [ ] **Step 5: Run to verify all pass** — `mvn test -Dtest=AdminServiceImplTest` — PASS.

- [ ] **Step 6: Wire `ServerMain`**
```java
AdminServiceImpl adminService = new AdminServiceImpl(sessionManager, userDao, gameTypeDao,
        gameSessionDao, gameEventPublisher);
```
(`userDao`, `gameTypeDao`, `gameSessionDao`, `gameEventPublisher` all already exist as local variables in `startWithImpls()` — this just passes them one call further than before.)

- [ ] **Step 7: Run the full suite, then manually confirm the server still starts** — `mvn test`, then `mvn exec:java` (banner unchanged, no exceptions).

- [ ] **Step 8: Commit**
```bash
git add src/main/java/com/matchmaker/server/rmi/AdminServiceImpl.java \
        src/main/java/com/matchmaker/server/ServerMain.java \
        src/test/java/com/matchmaker/server/rmi/AdminServiceImplTest.java \
        src/test/java/com/matchmaker/server/dao/InMemoryUserDao.java
git commit -m "Implement AdminServiceImpl for real: admin authorization, all 5 methods, force-end notifies players"
```

---

### Task 4: `admin.communication` layer

**Files:** Create `admin/communication/{ServerEventListener,Subscription,AdminCommunicationException,AdminConnection,RmiJmsAdminConnection}.java`, `src/test/.../admin/communication/InMemoryAdminConnection.java`.

Directly mirrors `client.communication` (Task 3 and Task 5 of the player-client plan) with a narrower method set (no `makeMove`/`joinQueue`/`cancelQueue`, one subscribe method instead of two, no publish). No new patterns to work out here -- copy the shape.

- [ ] **Step 1: `ServerEventListener`, `Subscription`, `AdminCommunicationException`** — byte-for-byte the same shape as their `client.communication` counterparts, package changed to `com.matchmaker.admin.communication`.

- [ ] **Step 2: `AdminConnection`**
```java
package com.matchmaker.admin.communication;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;

import java.util.List;

public interface AdminConnection {

    LoginResultDTO login(String username, String password) throws AuthenticationException;

    List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException, NotAdminException;

    GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
            throws AuthenticationException, NotAdminException;

    List<UserDTO> listUsers(String sessionToken) throws AuthenticationException, NotAdminException;

    List<GameStateDTO> listActiveSessions(String sessionToken) throws AuthenticationException, NotAdminException;

    void forceEndSession(String sessionToken, int gameSessionId) throws AuthenticationException, NotAdminException;

    Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener);
}
```

- [ ] **Step 3: `RmiJmsAdminConnection`** — same constructor shape as `RmiJmsServerConnection` (host, rmiPort, jmsPort), looking up `"AdminService"` and `"AuthService"` instead of `"PlayerService"`/`"AuthService"`, with only a subscribe method (no publish) for JMS. Reuse the exact `subscribe()` private-helper pattern from `RmiJmsServerConnection` (create topic, create consumer, `setMessageListener`, return a closing `Subscription`).

- [ ] **Step 4: `InMemoryAdminConnection`** (test fake) — same configurable-result-and-recorded-calls shape as `InMemoryServerConnection`, scoped to this narrower method set, plus `fireSessionTopicEvent(sessionId, event)` / `isSubscribedToSessionTopic(sessionId)` for tests.

- [ ] **Step 5: Compile** — `mvn compile` (nothing depends on these yet) and `mvn test-compile`.

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/matchmaker/admin/communication/ src/test/java/com/matchmaker/admin/communication/
git commit -m "Add admin communication-layer contracts, real RMI+JMS impl, and test fake"
```

---

### Task 5: `admin.logic.AdminClientService`

**Files:** Create `admin/logic/AdminClientService.java`, `src/test/.../admin/logic/AdminClientServiceTest.java`.

Simpler than `GameClientService` -- no matchmaking-style deferred-callback dance. Every method: run on a background thread, `Platform.runLater` the result, same `runAsync` helper shape as `GameClientService`.

- [ ] **Step 1: Write the failing tests** — `AdminClientServiceTest.java`, mirroring `GameClientServiceTest`'s `await()`/`@BeforeAll` JavaFX-warmup pattern exactly (copy those two verbatim -- same one-time-cold-start issue applies here). Cover: `login` success rejects a non-admin account client-side (`user.isAdmin() == false` -> a dedicated `onNotAdmin` path, not a generic error) and accepts an admin account; `listGameTypes`/`addGameType`/`listUsers`/`listActiveSessions`/`forceEndSession` each reach `onSuccess`/`onError` appropriately; `monitorSession(sessionId, listener)` subscribes to the session topic and the listener receives a fired event; `stopMonitoring()` closes the subscription.

- [ ] **Step 2: Run to verify it fails** — compile error, `AdminClientService` doesn't exist.

- [ ] **Step 3: Implement**
```java
package com.matchmaker.admin.logic;

import com.matchmaker.admin.communication.AdminConnection;
import com.matchmaker.admin.communication.Subscription;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class AdminClientService {

    private final AdminConnection adminConnection;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "admin-communication");
        thread.setDaemon(true);
        return thread;
    });

    private UserDTO currentUser;
    private String sessionToken;
    private Subscription sessionSubscription;

    public AdminClientService(AdminConnection adminConnection) {
        this.adminConnection = adminConnection;
    }

    public UserDTO getCurrentUser() {
        return currentUser;
    }

    public void login(String username, String password, Consumer<UserDTO> onSuccess, Runnable onNotAdmin,
                       Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.login(username, password),
                result -> {
                    if (!result.getUser().isAdmin()) {
                        onNotAdmin.run();
                        return;
                    }
                    currentUser = result.getUser();
                    sessionToken = result.getSessionToken();
                    onSuccess.accept(result.getUser());
                },
                onError);
    }

    public void listGameTypes(Consumer<List<GameTypeDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listGameTypes(sessionToken), onSuccess, onError);
    }

    public void addGameType(GameTypeDTO newGameType, Consumer<GameTypeDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.addGameType(sessionToken, newGameType), onSuccess, onError);
    }

    public void listUsers(Consumer<List<UserDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listUsers(sessionToken), onSuccess, onError);
    }

    public void listActiveSessions(Consumer<List<GameStateDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listActiveSessions(sessionToken), onSuccess, onError);
    }

    public void forceEndSession(int gameSessionId, Runnable onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> { adminConnection.forceEndSession(sessionToken, gameSessionId); return null; },
                ignored -> onSuccess.run(), onError);
    }

    public void monitorSession(int sessionId, Consumer<com.matchmaker.common.dto.GameEventDTO> onEvent) {
        sessionSubscription = adminConnection.subscribeToSessionTopic(sessionId, onEvent::accept);
    }

    public void stopMonitoring() {
        if (sessionSubscription != null) {
            sessionSubscription.close();
            sessionSubscription = null;
        }
    }

    public void shutdown() {
        backgroundExecutor.shutdownNow();
    }

    private <T> void runAsync(ThrowingSupplier<T> action, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        backgroundExecutor.submit(() -> {
            try {
                T result = action.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e));
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
```

- [ ] **Step 4: Run to verify all pass, then the full suite** — `mvn test -Dtest=AdminClientServiceTest`, then `mvn test`.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/matchmaker/admin/logic/ src/test/java/com/matchmaker/admin/logic/
git commit -m "Add AdminClientService, the admin client's Logic layer, with full unit coverage"
```

---

### Task 6: `pom.xml` admin profile, `AdminMain`, `SceneNavigator`, Login + Dashboard screens

**Files:**
- Modify: `pom.xml` (new `admin` profile)
- Create: `admin/presentation/SceneNavigator.java` (duplicated from `client.presentation`)
- Create: `admin/AdminMain.java`
- Create: `admin/presentation/AdminLoginController.java` + `.../AdminLoginView.fxml`
- Create: `admin/presentation/DashboardController.java` + `.../DashboardView.fxml`

- [ ] **Step 1: `pom.xml` profile**
```xml
    <profiles>
        <profile>
            <id>admin</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.openjfx</groupId>
                        <artifactId>javafx-maven-plugin</artifactId>
                        <configuration>
                            <mainClass>com.matchmaker.admin.AdminMain</mainClass>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```
Add this as a sibling to the existing `<build>` element (not nested inside it).

- [ ] **Step 2: Verify the profile actually works** *before* writing any admin screens, using `ClientMain` as a throwaway proof:
```bash
mvn javafx:run -Padmin -Djavafx.mainClass=com.matchmaker.client.ClientMain
```
This confirms the profile mechanism overrides `mainClass` correctly (using the player client as a known-working target) before betting the real `AdminMain` on it. **If this doesn't launch `ClientMain`'s Login screen, stop and re-read the javafx-maven-plugin docs for the actual override mechanism** rather than assuming the profile alone is sufficient — this was flagged in the design doc as the one unverified piece.

- [ ] **Step 3: `SceneNavigator`** — identical to `client.presentation.SceneNavigator` (same fixed-size-window fields/logic), package `com.matchmaker.admin.presentation`. Reasonable fixed size for this milestone: `720x600` (Dashboard's table needs more width than the player client's forms did; no board to fit, so less height).

- [ ] **Step 4: `AdminLoginView.fxml`** — same shape as the player client's `LoginView.fxml` (username/password fields, Login button; no Register button -- admin accounts aren't self-service).

- [ ] **Step 5: `AdminLoginController`**
```java
@FXML
private void onLogin() {
    setControlsDisabled(true);
    adminClientService.login(usernameField.getText(), passwordField.getText(),
            user -> {
                DashboardController controller = navigator.show("DashboardView.fxml", "MatchMaker Admin - Dashboard");
                controller.init(adminClientService, navigator);
            },
            () -> {
                setControlsDisabled(false);
                statusLabel.setText("This account is not an admin account.");
            },
            error -> {
                setControlsDisabled(false);
                statusLabel.setText(error.getMessage());
            });
}
```

- [ ] **Step 6: `DashboardView.fxml` / `DashboardController`** — a `TableView<GameStateDTO>` for active sessions (columns: Session ID, Game Type ID, Player 1, Player 2, Turn -- keep it to raw ids for this milestone rather than joining against usernames, which `AdminService.listActiveSessions()` doesn't provide), a "Monitor" button per row navigating to `LiveSessionMonitorView`, a total-user-count label from `listUsers()`, and a "New Game Type" button navigating to `AddGameTypeView`.

- [ ] **Step 7: `AdminMain`**
```java
package com.matchmaker.admin;

import com.matchmaker.admin.communication.RmiJmsAdminConnection;
import com.matchmaker.admin.logic.AdminClientService;
import com.matchmaker.admin.presentation.AdminLoginController;
import com.matchmaker.admin.presentation.SceneNavigator;
import javafx.application.Application;
import javafx.stage.Stage;

public class AdminMain extends Application {

    private static final String SERVER_HOST = "localhost";
    private static final int RMI_PORT = 1099;
    private static final int JMS_PORT = 61616;

    private RmiJmsAdminConnection adminConnection;

    @Override
    public void start(Stage primaryStage) {
        adminConnection = new RmiJmsAdminConnection(SERVER_HOST, RMI_PORT, JMS_PORT);
        AdminClientService adminClientService = new AdminClientService(adminConnection);
        SceneNavigator navigator = new SceneNavigator(primaryStage);

        AdminLoginController controller = navigator.show("AdminLoginView.fxml", "MatchMaker Admin - Login");
        controller.init(adminClientService, navigator);
    }

    @Override
    public void stop() {
        if (adminConnection != null) {
            adminConnection.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 8: Compile** — `mvn compile` (forward references to `AddGameTypeController`/`LiveSessionMonitorController` from `DashboardController` won't resolve until Task 7 -- same known-and-expected gap the player client plan had between its Task 6 and Task 7).

- [ ] **Step 9: Commit** (once Task 7 makes this compile, same as the player client plan's Task 6+7 pairing)

---

### Task 7: Add Game Type + Live Session Monitor screens

**Files:**
- Create: `admin/presentation/AddGameTypeController.java` + `.../AddGameTypeView.fxml`
- Create: `admin/presentation/LiveSessionMonitorController.java` + `.../LiveSessionMonitorView.fxml`

- [ ] **Step 1: `AddGameTypeView.fxml` / `AddGameTypeController`** — form fields matching spec §10.2 (Name, Description, Min/Max Players, Board Rows/Cols), a Submit button calling `adminClientService.addGameType(...)`, on success navigate back to `DashboardView`.

- [ ] **Step 2: `LiveSessionMonitorView.fxml` / `LiveSessionMonitorController`** — session detail labels (game type id, both player ids, status, turn), a read-only board rendering reusing the exact same `renderBoard`/`buildCell`/`toAlgebraic` logic as `client.presentation.GameBoardController` (duplicated, not shared -- `admin` doesn't import `client`; no click handlers on any cell, since admin never moves), a "Force End Session" button calling `adminClientService.forceEndSession(...)`. On `init(sessionId, initialState)`, call `adminClientService.monitorSession(sessionId, this::onEvent)` immediately (same "subscribe before anything else" rule as the player client), and `adminClientService.stopMonitoring()` on navigating away.

- [ ] **Step 3: Compile everything** — `mvn compile`. Fix any forward-reference mismatches from Task 6.

- [ ] **Step 4: Manual smoke check** — `mvn exec:java` (server), `mvn javafx:run -Padmin` (admin client): register/login as the `admin` seed user (or a fresh one with `IsAdmin` set directly in the DB -- `db/seed-demo-users.sql` already creates one), confirm the Dashboard loads with no active sessions initially, confirm "New Game Type" round-trips a new entry back into the Dashboard's list.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/matchmaker/admin/ src/main/resources/com/matchmaker/admin/ pom.xml
git commit -m "Add Add-Game-Type and Live Session Monitor screens; wire up AdminMain and the admin Maven profile"
```

---

### Task 8: Manual end-to-end verification

- [ ] **Step 1:** `docker compose up -d`, `mvn exec:java` (server).
- [ ] **Step 2:** `mvn javafx:run` (player, twice) -- match two players into a Checkers game, per step 8's Task 8.
- [ ] **Step 3:** `mvn javafx:run -Padmin` -- log in as `admin`. Confirm the Dashboard's active-sessions table shows the game from Step 2.
- [ ] **Step 4:** Open the Live Session Monitor for that session -- confirm the board matches what the two players see, and updates live as they play a move (no action needed on the admin's part).
- [ ] **Step 5:** Click "Force End Session." Confirm the Monitor shows `ABANDONED`, and **both player windows** update to show the game ended, without either player doing anything.
- [ ] **Step 6:** Back on the Dashboard, confirm the session no longer appears in the active list.
- [ ] **Step 7:** No commit for this task -- verification only. Fix and re-verify from Step 1 if anything fails.

---

## Post-plan status update

Once Task 8 passes: fold this into `docs/build-plan.md` as Milestone 8, following the exact write-up shape used for Milestone 7 (what's real, what's tested, current state, honest note on manual-verification status), update `docs/project-structure.md` with a new `admin/` section (mirroring the `client/` one), and trim "Next Steps" to step 10 (edge cases: `keepAlive`/disconnect detection, turn timeout, Rematch, authorization checks) as the only remaining roadmap item before testing/polish.
