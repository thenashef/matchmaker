# JMS Setup (Roadmap Step 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the server a working, tested JMS notification path so the player who was already waiting in the matchmaking queue learns — asynchronously, without a live RMI call — that they've just been matched.

**Architecture:** A new `server/jms` package (interface + real ActiveMQ implementation + test fake, mirroring the existing `server/dao` and `server/matchmaking` pattern) publishes a `GameEventDTO` to a per-player JMS `Queue` named `player.{userId}.events`. `PlayerServiceImpl.joinQueue()` calls this immediately after a successful pairing, targeting whichever participant wasn't the RMI caller. The broker is embedded (ActiveMQ's `vm://` transport) — no Docker, no separate process.

**Tech Stack:** Java 21, JUnit 5, Apache ActiveMQ Classic 5.19.7 (`activemq-client` + `activemq-broker`, embedded `vm://` transport, `javax.jms` API).

## Global Constraints

- No Docker required anywhere in this plan — every test must run against the embedded `vm://` broker.
- Follow the existing DAO/matchmaking package pattern: an interface in `server/jms/`, a real `ActiveMq*` implementation, and a test-only `InMemory*` fake in the mirrored `src/test/...` package — same shape as `MatchmakingQueue`/`JdbcMatchmakingQueue`/`InMemoryMatchmakingQueue`.
- DTOs follow the existing style exactly: `Serializable`, `private static final long serialVersionUID = 1L;`, private final fields, one constructor, getters only, no setters.
- Unchecked exceptions wrap checked JMS/SQL failures, matching `DaoException` wrapping `SQLException`.
- No logging framework exists in this codebase yet — use `System.err.println(...)` for the one "log and continue" case in this plan, matching `ServerMain`'s existing use of `System.out.println` for its banner (the only precedent for console output in `src/main`).
- A publish failure must never fail the calling player's `joinQueue()` RMI call (see design doc, Decision #5).

---

### Task 1: Embedded JMS connection factory

**Files:**
- Modify: `pom.xml` (add ActiveMQ dependencies)
- Create: `src/main/java/com/matchmaker/server/jms/JmsConnectionFactory.java`
- Test: `src/test/java/com/matchmaker/server/jms/JmsConnectionFactoryTest.java`

**Interfaces:**
- Produces: `JmsConnectionFactory.create()` — static method, returns a started `javax.jms.Connection`, throws `javax.jms.JMSException`. Every later task that needs a JMS connection (tests and `ServerMain`) calls this.

**Design note:** each call to `create()` generates a fresh, uniquely-named embedded broker (`vm://matchmaker-<random-uuid>?broker.persistent=false`) rather than a fixed `vm://localhost`. This keeps every test's broker fully isolated from every other test's — since JMS `Queue`s retain unconsumed messages until read, a shared broker across the whole test JVM would risk one test's leftover message being picked up by a later test using the same numeric user id. `ServerMain` only ever calls `create()` once at startup, so the unique name costs it nothing.

- [ ] **Step 1: Add the ActiveMQ dependencies to `pom.xml`**

Add inside the existing `<dependencies>` block (after the `jbcrypt` dependency):

```xml
        <dependency>
            <groupId>org.apache.activemq</groupId>
            <artifactId>activemq-client</artifactId>
            <version>5.19.7</version>
        </dependency>
        <dependency>
            <groupId>org.apache.activemq</groupId>
            <artifactId>activemq-broker</artifactId>
            <version>5.19.7</version>
        </dependency>
```

`activemq-client` provides the `javax.jms` API types and `ActiveMQConnectionFactory`. `activemq-broker` is required too — it provides the actual broker implementation classes that the `vm://` transport starts in-process; without it, `vm://` connections fail at runtime with a `ClassNotFoundException`.

- [ ] **Step 2: Write the failing test**

```java
package com.matchmaker.server.jms;

import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.Session;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JmsConnectionFactoryTest {

    @Test
    void create_returnsAStartedUsableConnection() throws Exception {
        Connection connection = JmsConnectionFactory.create();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            assertNotNull(session);
        } finally {
            connection.close();
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn test -Dtest=JmsConnectionFactoryTest`
Expected: compile error — `JmsConnectionFactory` does not exist yet.

- [ ] **Step 4: Write the implementation**

```java
package com.matchmaker.server.jms;

import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import java.util.List;
import java.util.UUID;

public class JmsConnectionFactory {

    public static Connection create() throws JMSException {
        String brokerUrl = "vm://matchmaker-" + UUID.randomUUID() + "?broker.persistent=false";

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));

        Connection connection = factory.createConnection();
        connection.start();
        return connection;
    }
}
```

`setTrustedPackages(...)` matters, not boilerplate: modern ActiveMQ refuses to deserialize `ObjectMessage` payloads whose class isn't `java.*`/`javax.*` or explicitly trusted, as a security hardening measure. Without this line, every `ObjectMessage` carrying a `GameEventDTO` in later tasks will throw a `JMSException` on the receiving end even though sending succeeds.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=JmsConnectionFactoryTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/matchmaker/server/jms/JmsConnectionFactory.java src/test/java/com/matchmaker/server/jms/JmsConnectionFactoryTest.java
git commit -m "Add embedded JMS connection factory"
```

---

### Task 2: `GameEventType` enum and `GameEventDTO`

**Files:**
- Create: `src/main/java/com/matchmaker/common/enums/GameEventType.java`
- Create: `src/main/java/com/matchmaker/common/dto/GameEventDTO.java`
- Modify: `src/test/java/com/matchmaker/common/dto/NewDtoSerializationTest.java` (add one test method)

**Interfaces:**
- Consumes: `GameStateDTO` (existing, `com.matchmaker.common.dto`) — used as the `gameState` field.
- Produces: `GameEventType` enum with one value, `MATCH_FOUND`. `GameEventDTO(GameEventType type, int sessionId, GameStateDTO gameState)` with `getType()`, `getSessionId()`, `getGameState()`. Task 3's publisher and Task 4's `PlayerServiceImpl` both construct and pass these.

- [ ] **Step 1: Write the failing test**

Add this test method to the existing `src/test/java/com/matchmaker/common/dto/NewDtoSerializationTest.java` (inside the class, alongside the other `*_survivesSerializationRoundTrip` tests):

```java
    @Test
    void gameEventDTO_survivesSerializationRoundTrip() throws Exception {
        GameStateDTO gameState = new GameStateDTO(7, 1, 42, 99, GameStatus.ACTIVE, 42, null, null);
        GameEventDTO original = new GameEventDTO(GameEventType.MATCH_FOUND, 7, gameState);

        GameEventDTO restored = roundTrip(original);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getGameState().getPlayer1Id(), restored.getGameState().getPlayer1Id());
        assertEquals(original.getGameState().getPlayer2Id(), restored.getGameState().getPlayer2Id());
    }
```

Add these two imports to the top of the file, alongside the existing imports:

```java
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=NewDtoSerializationTest`
Expected: compile error — `GameEventType` and `GameEventDTO` do not exist yet.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/matchmaker/common/enums/GameEventType.java`:

```java
package com.matchmaker.common.enums;

public enum GameEventType {
    MATCH_FOUND
}
```

`src/main/java/com/matchmaker/common/dto/GameEventDTO.java`:

```java
package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameEventType;

import java.io.Serializable;

public class GameEventDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final GameEventType type;
    private final int sessionId;
    private final GameStateDTO gameState;

    public GameEventDTO(GameEventType type, int sessionId, GameStateDTO gameState) {
        this.type = type;
        this.sessionId = sessionId;
        this.gameState = gameState;
    }

    public GameEventType getType() {
        return type;
    }

    public int getSessionId() {
        return sessionId;
    }

    public GameStateDTO getGameState() {
        return gameState;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=NewDtoSerializationTest`
Expected: PASS (all tests in the file, including the new one)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/common/enums/GameEventType.java src/main/java/com/matchmaker/common/dto/GameEventDTO.java src/test/java/com/matchmaker/common/dto/NewDtoSerializationTest.java
git commit -m "Add GameEventType and GameEventDTO"
```

---

### Task 3: `GameEventPublisher` + `ActiveMqGameEventPublisher` (the real publish path)

**Files:**
- Create: `src/main/java/com/matchmaker/server/jms/GameEventPublisher.java`
- Create: `src/main/java/com/matchmaker/server/jms/JmsPublishException.java`
- Create: `src/main/java/com/matchmaker/server/jms/ActiveMqGameEventPublisher.java`
- Test: `src/test/java/com/matchmaker/server/jms/GameEventPublisherJmsIntegrationTest.java`

**Interfaces:**
- Consumes: `JmsConnectionFactory.create()` (Task 1), `GameEventDTO`/`GameEventType` (Task 2).
- Produces: `GameEventPublisher` interface — `void publishToPlayer(int userId, GameEventDTO event)`. `ActiveMqGameEventPublisher(Session session)` constructor. `JmsPublishException` (unchecked, wraps `JMSException`). Task 4 injects `GameEventPublisher` into `PlayerServiceImpl`; Task 5 constructs `ActiveMqGameEventPublisher` in `ServerMain`.

This is the "standalone consumer" proof from the roadmap (`build-plan.md` step 6: *"a minimal standalone consumer to prove messages arrive before touching the UI"*) — done as a real automated integration test rather than a manual two-terminal demo, matching how `AuthServiceRmiIntegrationTest` proves RMI end-to-end on every `mvn test` run.

- [ ] **Step 1: Write the failing test**

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GameEventPublisherJmsIntegrationTest {

    private Connection connection;
    private Session session;
    private GameEventPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        connection = JmsConnectionFactory.create();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        publisher = new ActiveMqGameEventPublisher(session);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void publishToPlayer_realConsumerReceivesTheEvent() throws Exception {
        int waitingPlayerUserId = 42;
        Queue queue = session.createQueue("player." + waitingPlayerUserId + ".events");
        MessageConsumer consumer = session.createConsumer(queue);

        GameStateDTO matchedSession = new GameStateDTO(7, 1, waitingPlayerUserId, 99,
                GameStatus.ACTIVE, waitingPlayerUserId, null, null);
        GameEventDTO event = new GameEventDTO(GameEventType.MATCH_FOUND, 7, matchedSession);

        publisher.publishToPlayer(waitingPlayerUserId, event);

        Message received = consumer.receive(2000);

        assertNotNull(received, "expected a message to arrive on the player's queue");
        assertInstanceOf(ObjectMessage.class, received);
        GameEventDTO receivedEvent = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.MATCH_FOUND, receivedEvent.getType());
        assertEquals(7, receivedEvent.getSessionId());
        assertEquals(waitingPlayerUserId, receivedEvent.getGameState().getPlayer1Id());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=GameEventPublisherJmsIntegrationTest`
Expected: compile error — `GameEventPublisher` and `ActiveMqGameEventPublisher` do not exist yet.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/matchmaker/server/jms/GameEventPublisher.java`:

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

public interface GameEventPublisher {

    void publishToPlayer(int userId, GameEventDTO event);
}
```

`src/main/java/com/matchmaker/server/jms/JmsPublishException.java`:

```java
package com.matchmaker.server.jms;

public class JmsPublishException extends RuntimeException {
    public JmsPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/com/matchmaker/server/jms/ActiveMqGameEventPublisher.java`:

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;

public class ActiveMqGameEventPublisher implements GameEventPublisher {

    private final Session session;

    public ActiveMqGameEventPublisher(Session session) {
        this.session = session;
    }

    @Override
    public void publishToPlayer(int userId, GameEventDTO event) {
        try {
            Queue queue = session.createQueue("player." + userId + ".events");
            MessageProducer producer = session.createProducer(queue);
            try {
                ObjectMessage message = session.createObjectMessage(event);
                producer.send(message);
            } finally {
                producer.close();
            }
        } catch (JMSException e) {
            throw new JmsPublishException("Failed to publish event to player " + userId, e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=GameEventPublisherJmsIntegrationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/jms/GameEventPublisher.java src/main/java/com/matchmaker/server/jms/JmsPublishException.java src/main/java/com/matchmaker/server/jms/ActiveMqGameEventPublisher.java src/test/java/com/matchmaker/server/jms/GameEventPublisherJmsIntegrationTest.java
git commit -m "Add ActiveMqGameEventPublisher with a real-consumer integration test"
```

---

### Task 4: Wire publishing into `PlayerServiceImpl.joinQueue()`

**Files:**
- Create: `src/test/java/com/matchmaker/server/jms/InMemoryGameEventPublisher.java` (test fake)
- Modify: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`
- Modify: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`

**Interfaces:**
- Consumes: `GameEventPublisher`, `GameEventDTO`, `GameEventType` (Tasks 2–3).
- Produces: `PlayerServiceImpl` constructor becomes `PlayerServiceImpl(SessionManager, GameSessionDao, GameTypeDao, MatchmakingQueue, GameEventPublisher)`. Task 5's `ServerMain` must be updated to match this new constructor signature (it is currently the only other caller besides this task's test).

- [ ] **Step 1: Write the test fake**

`src/test/java/com/matchmaker/server/jms/InMemoryGameEventPublisher.java`:

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameEventPublisher implements GameEventPublisher {

    public record PublishedEvent(int userId, GameEventDTO event) {
    }

    private final List<PublishedEvent> published = new ArrayList<>();

    @Override
    public void publishToPlayer(int userId, GameEventDTO event) {
        published.add(new PublishedEvent(userId, event));
    }

    public List<PublishedEvent> published() {
        return published;
    }
}
```

- [ ] **Step 2: Write the failing tests**

In `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`, add this import:

```java
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.server.jms.InMemoryGameEventPublisher;
```

Add a field and wire it into `@BeforeEach`:

```java
    private InMemoryGameEventPublisher gameEventPublisher;
```

Change the `@BeforeEach` method from:

```java
    @BeforeEach
    void createPlayerService() throws Exception {
        sessionManager = new SessionManager();
        gameSessionDao = new InMemoryGameSessionDao();
        gameTypeDao = new InMemoryGameTypeDao();
        matchmakingQueue = new InMemoryMatchmakingQueue();
        playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue);
        sessionToken = sessionManager.createSession(1);
    }
```

to:

```java
    @BeforeEach
    void createPlayerService() throws Exception {
        sessionManager = new SessionManager();
        gameSessionDao = new InMemoryGameSessionDao();
        gameTypeDao = new InMemoryGameTypeDao();
        matchmakingQueue = new InMemoryMatchmakingQueue();
        gameEventPublisher = new InMemoryGameEventPublisher();
        playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue, gameEventPublisher);
        sessionToken = sessionManager.createSession(1);
    }
```

Add these two new test methods (near the existing `joinQueue_*` tests):

```java
    @Test
    void joinQueue_opponentWaiting_publishesMatchFoundEventToWaitingPlayer() throws Exception {
        String otherToken = sessionManager.createSession(2);
        playerService.joinQueue(otherToken, 1); // user 2 waits first

        GameStateDTO result = playerService.joinQueue(sessionToken, 1); // user 1 (this test's default caller) matches them

        assertEquals(1, gameEventPublisher.published().size());
        InMemoryGameEventPublisher.PublishedEvent published = gameEventPublisher.published().get(0);
        assertEquals(2, published.userId());
        assertEquals(GameEventType.MATCH_FOUND, published.event().getType());
        assertEquals(result.getSessionId(), published.event().getSessionId());
    }

    @Test
    void joinQueue_noOpponentWaiting_doesNotPublishAnyEvent() throws Exception {
        playerService.joinQueue(sessionToken, 1);

        assertEquals(0, gameEventPublisher.published().size());
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: compile error — `PlayerServiceImpl`'s constructor doesn't accept a 5th argument yet.

- [ ] **Step 4: Update `PlayerServiceImpl`**

Add these imports to `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`:

```java
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsPublishException;
```

Add a field:

```java
    private final GameEventPublisher gameEventPublisher;
```

Change the constructor from:

```java
    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao,
                              MatchmakingQueue matchmakingQueue) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
        this.matchmakingQueue = matchmakingQueue;
    }
```

to:

```java
    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao,
                              MatchmakingQueue matchmakingQueue, GameEventPublisher gameEventPublisher) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
        this.matchmakingQueue = matchmakingQueue;
        this.gameEventPublisher = gameEventPublisher;
    }
```

Change `joinQueue()` from:

```java
    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        return matchmakingQueue.join(userId, gameTypeId);
    }
```

to:

```java
    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        GameStateDTO result = matchmakingQueue.join(userId, gameTypeId);

        if (result != null) {
            int opponentUserId = (result.getPlayer1Id() == userId)
                    ? result.getPlayer2Id()
                    : result.getPlayer1Id();
            try {
                gameEventPublisher.publishToPlayer(opponentUserId,
                        new GameEventDTO(GameEventType.MATCH_FOUND, result.getSessionId(), result));
            } catch (JmsPublishException e) {
                // The pairing already committed to the DB -- a failed notification to the
                // *other* player shouldn't fail this caller's own, already-successful result.
                System.err.println("Failed to notify opponent " + opponentUserId + " of match: " + e.getMessage());
            }
        }

        return result;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: PASS (all tests in the file, including the two new ones)

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/matchmaker/server/jms/InMemoryGameEventPublisher.java src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java
git commit -m "Publish MATCH_FOUND event to the waiting player from joinQueue()"
```

---

### Task 5: Wire `ServerMain` and run the full regression suite

**Files:**
- Modify: `src/main/java/com/matchmaker/server/ServerMain.java`

**Interfaces:**
- Consumes: `JmsConnectionFactory.create()` (Task 1), `ActiveMqGameEventPublisher` (Task 3), `PlayerServiceImpl`'s new 5-arg constructor (Task 4).
- Produces: nothing new for later tasks — this is the last task in this plan.

This task has no new unit test of its own: `ServerMain` is exercised by the existing `ServerMainTest`, which must keep passing once the constructor call and imports change. The full-suite run in Step 3 is this task's actual verification.

- [ ] **Step 1: Update `ServerMain`**

Add these imports to `src/main/java/com/matchmaker/server/ServerMain.java`:

```java
import com.matchmaker.server.jms.ActiveMqGameEventPublisher;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
```

Change the `start`/`startWithImpls` signatures from:

```java
    public static Registry start(int port) throws RemoteException {
        return startWithImpls(port).registry();
    }
```
```java
    static Started startWithImpls(int port) throws RemoteException {
```

to:

```java
    public static Registry start(int port) throws RemoteException, JMSException {
        return startWithImpls(port).registry();
    }
```
```java
    static Started startWithImpls(int port) throws RemoteException, JMSException {
```

Inside `startWithImpls`, change:

```java
        MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(dataSource);

        Registry registry = LocateRegistry.createRegistry(port);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);
```

to:

```java
        MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(dataSource);

        Connection jmsConnection = JmsConnectionFactory.create();
        Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        GameEventPublisher gameEventPublisher = new ActiveMqGameEventPublisher(jmsSession);

        Registry registry = LocateRegistry.createRegistry(port);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue, gameEventPublisher);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);
```

`main()` already declares `throws Exception`, so it needs no change to compile against the widened `start()` signature.

- [ ] **Step 2: Compile**

Run: `mvn compile`
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 3: Run the full test suite**

Run: `docker compose up -d && mvn test`
Expected: every test passes, including the pre-existing `ServerMainTest` (proves `ServerMain` still wires and binds all three RMI services correctly with the new JMS pieces in place) and all of this plan's new tests (`JmsConnectionFactoryTest`, `GameEventPublisherJmsIntegrationTest`, the extended `PlayerServiceImplTest` and `NewDtoSerializationTest`).

- [ ] **Step 4: Manually confirm the server still starts**

Run: `mvn exec:java`
Expected console output: the existing `"MatchMaker RMI registry started on port 1099"` / `"Bound services: ..."` banner, with no exceptions or stack traces before it — proving the new JMS connection setup doesn't blow up server startup. Stop it with Ctrl-C once confirmed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/ServerMain.java
git commit -m "Wire ActiveMqGameEventPublisher into ServerMain"
```

---

## Post-plan status update

After Task 5's commit, update `docs/build-plan.md`: move step 6 from "Immediate next focus" into the "What's Implemented So Far" section (as Milestone 5, following the existing per-milestone write-up style used for Milestones 1–4), and update "Next Steps" to point at step 7 (the game engine). This documentation update is not a separate plan task with its own test — it's a direct doc edit, done once all five tasks above are verified green, following the same pattern used after each prior milestone.
