# JavaFX Player Client (Roadmap Step 8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the JavaFX player client — Login/Register → Lobby → Matchmaking Wait → Game Board — wired to the server over RMI (commands) and JMS (push updates), per `docs/specs/2026-08-13-player-client-design.md`. That design doc has the full rationale for every decision below; this plan is the "how," ordered bottom-up so each task can compile and (where possible) be tested before the next depends on it.

**Architecture recap** (full detail in the design doc): `client.communication` (RMI + JMS, zero JavaFX awareness) → `client.logic` (one `GameClientService` class, the only place that touches a background thread or `Platform.runLater`) → `client.presentation` (FXML + Controllers, only ever touches JavaFX nodes on the JavaFX Application Thread). A prerequisite server-side fix (Task 1) makes the JMS broker network-reachable, since the existing `vm://`-per-call broker is invisible outside `ServerMain`'s own JVM.

## Global Constraints

- `client.communication`, `client.logic`, and `client.presentation` do not import from `com.matchmaker.server.*` at all — the client and server are meant to be independently deployable (spec's own architecture diagram draws them as separate boxes touching only via RMI/JMS), even though this course project keeps them in one Maven module for submission simplicity. Where this would otherwise save a few lines (e.g. reusing `server.jms.JmsConnectionFactory`, or `ServerMain`'s port constants), duplicate the few lines instead in `client.communication` — same tradeoff `build-plan.md`'s own "Assumptions" section already makes elsewhere (simplicity for a course submission over strict layering purity, but this one boundary is worth keeping).
- Follow existing DTO/exception conventions exactly — nothing here invents a new style.
- `GameClientService` is unit-tested against `InMemoryServerConnection`, no RMI/JMS/JavaFX involved, same tier as `PlayerServiceImplTest` against `InMemoryGameEventPublisher`.
- No automated tests for `client.presentation` — consistent with how JavaFX UI isn't unit-tested elsewhere in this course's tooling. Each presentation task ends in a manual smoke check instead of a test run.
- Every new/changed file gets its own task-ending commit, same cadence as every prior milestone.

---

### Task 1: Make the JMS broker network-reachable

**Files:**
- Create: `src/main/java/com/matchmaker/server/jms/EmbeddedJmsBroker.java`
- Modify: `src/main/java/com/matchmaker/server/jms/JmsConnectionFactory.java`
- Modify: `src/main/java/com/matchmaker/server/ServerMain.java`
- Modify: `src/test/java/com/matchmaker/server/ServerMainTest.java`
- Test: `src/test/java/com/matchmaker/server/jms/EmbeddedJmsBrokerTest.java`

**Why this is first:** every later task in this plan (the real `ServerConnection` implementation, and the manual end-to-end check) needs a server process that's actually reachable from a separate client JVM. See design doc Decision #5.

- [ ] **Step 1: Write the failing test** — `EmbeddedJmsBrokerTest.java`, proving a *second, fully independent* connection (simulating a separate client process) can reach the broker over `tcp://`:

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.Topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmbeddedJmsBrokerTest {

    private static final int TEST_PORT = 61617; // distinct from ServerMain's production JMS_PORT

    private BrokerService broker;
    private Connection publisherConnection;
    private Connection clientConnection;

    @AfterEach
    void tearDown() throws Exception {
        if (publisherConnection != null) publisherConnection.close();
        if (clientConnection != null) clientConnection.close();
        if (broker != null) broker.stop();
    }

    @Test
    void aSecondIndependentTcpConnectionCanReachTheBroker() throws Exception {
        broker = EmbeddedJmsBroker.start(TEST_PORT);

        // Simulates ServerMain's own publisher connection.
        publisherConnection = JmsConnectionFactory.createForBroker("tcp://localhost:" + TEST_PORT);
        Session publisherSession = publisherConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        ActiveMqGameEventPublisher publisher = new ActiveMqGameEventPublisher(publisherSession);

        // Simulates a real player client in a separate JVM/process -- a totally independent
        // Connection, not sharing anything in-process with the publisher above. This is exactly
        // what the old vm://matchmaker-<uuid> broker could never support.
        clientConnection = JmsConnectionFactory.createForBroker("tcp://localhost:" + TEST_PORT);
        Session clientSession = clientConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = clientSession.createTopic("session.7.events");
        MessageConsumer consumer = clientSession.createConsumer(topic);

        GameStateDTO state = new GameStateDTO(7, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        publisher.publishToSession(7, new GameEventDTO(GameEventType.MOVE_MADE, 7, state));

        Message received = consumer.receive(2000);

        assertNotNull(received, "a genuinely separate tcp:// connection should still receive the event");
        GameEventDTO event = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.MOVE_MADE, event.getType());
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `mvn test -Dtest=EmbeddedJmsBrokerTest` — compile error, `EmbeddedJmsBroker`/`JmsConnectionFactory.createForBroker` don't exist yet.

- [ ] **Step 3: Add `EmbeddedJmsBroker`**

```java
package com.matchmaker.server.jms;

import org.apache.activemq.broker.BrokerService;

public class EmbeddedJmsBroker {

    public static BrokerService start(int port) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setBrokerName("matchmaker-" + port);
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.addConnector("tcp://0.0.0.0:" + port);
        broker.start();
        return broker;
    }
}
```

- [ ] **Step 4: Extend `JmsConnectionFactory`** to expose the general-purpose method, with the existing per-test-isolated `create()` now just calling it:

```java
package com.matchmaker.server.jms;

import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import java.util.List;
import java.util.UUID;

public class JmsConnectionFactory {

    public static Connection create() throws JMSException {
        return createForBroker("vm://matchmaker-" + UUID.randomUUID() + "?broker.persistent=false");
    }

    public static Connection createForBroker(String brokerUrl) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));

        Connection connection = factory.createConnection();
        connection.start();
        return connection;
    }
}
```

- [ ] **Step 5: Run to verify it passes** — `mvn test -Dtest=EmbeddedJmsBrokerTest` — PASS. Also run `mvn test -Dtest=JmsConnectionFactoryTest,GameEventPublisherJmsIntegrationTest` to confirm the existing `vm://`-based tests are untouched by the refactor.

- [ ] **Step 6: Wire `ServerMain` to the new long-lived broker instead of the disposable one**

Replace the `PORT` constant and the JMS setup block in `ServerMain`:

```java
public class ServerMain {

    public static final int RMI_PORT = 1099;
    public static final int JMS_PORT = 61616;

    public static void main(String[] args) throws Exception {
        start(RMI_PORT, JMS_PORT);

        System.out.println("MatchMaker RMI registry started on port " + RMI_PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");
        System.out.println("JMS broker listening on tcp://localhost:" + JMS_PORT);

        Thread.currentThread().join();
    }

    public static Registry start(int rmiPort, int jmsPort) throws Exception {
        return startWithImpls(rmiPort, jmsPort).registry();
    }

    static Started startWithImpls(int rmiPort, int jmsPort) throws Exception {
        SessionManager sessionManager = new SessionManager();

        DataSource dataSource = DataSourceFactory.create();
        UserDao userDao = new JdbcUserDao(dataSource);
        GameSessionDao gameSessionDao = new JdbcGameSessionDao(dataSource);
        GameTypeDao gameTypeDao = new JdbcGameTypeDao(dataSource);
        MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(dataSource);

        BrokerService jmsBroker = EmbeddedJmsBroker.start(jmsPort);
        Connection jmsConnection = JmsConnectionFactory.createForBroker("tcp://localhost:" + jmsPort);
        Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        GameEventPublisher gameEventPublisher = new ActiveMqGameEventPublisher(jmsSession);
        GameEngine gameEngine = new CheckersEngine();

        Registry registry = LocateRegistry.createRegistry(rmiPort);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao,
                matchmakingQueue, gameEventPublisher, gameEngine);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);

        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        return new Started(jmsBroker, registry, authService, playerService, adminService);
    }

    record Started(BrokerService jmsBroker, Registry registry, AuthServiceImpl authService,
                    PlayerServiceImpl playerService, AdminServiceImpl adminService) {
    }
}
```

Add `import org.apache.activemq.broker.BrokerService;`. `start`/`startWithImpls` now declare `throws Exception` (widened from `RemoteException, JMSException`, since `BrokerService.start()` throws checked `Exception`) — this is safe: the only caller besides `main()` is `ServerMainTest`, whose `@BeforeEach` already declares `throws Exception`.

- [ ] **Step 7: Update `ServerMainTest`** to tear down the broker too (use a distinct test JMS port, mirroring the existing distinct `TEST_PORT` for RMI — this matters in practice, not just in theory: a developer might have `mvn exec:java` running on the real `JMS_PORT` in one terminal while running `mvn test` in another):

```java
private static final int TEST_PORT = 21100;
private static final int TEST_JMS_PORT = 21106;

@BeforeEach
void startServer() throws Exception {
    started = ServerMain.startWithImpls(TEST_PORT, TEST_JMS_PORT);
}

@AfterEach
void tearDownRegistry() throws Exception {
    // ... existing registry/service unbind-and-unexport block, unchanged ...
    if (started != null && started.jmsBroker() != null) {
        started.jmsBroker().stop();
    }
}
```

- [ ] **Step 8: Run the full suite** — `docker compose up -d && mvn test` — all tests pass, including `ServerMainTest` (proves `ServerMain` still boots cleanly with the new broker wiring).

- [ ] **Step 9: Manually confirm the server still starts** — `mvn exec:java` — banner now includes the new `JMS broker listening on tcp://localhost:61616` line, no exceptions. Ctrl-C to stop.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/matchmaker/server/jms/EmbeddedJmsBroker.java \
        src/main/java/com/matchmaker/server/jms/JmsConnectionFactory.java \
        src/main/java/com/matchmaker/server/ServerMain.java \
        src/test/java/com/matchmaker/server/ServerMainTest.java \
        src/test/java/com/matchmaker/server/jms/EmbeddedJmsBrokerTest.java
git commit -m "Make the JMS broker network-reachable so a separate client process can subscribe"
```

---

### Task 2: JavaFX build setup

**Files:** Modify `pom.xml`.

- [ ] **Step 1: Add the JavaFX dependencies** (versions confirmed against Maven Central as the latest 21.x release, matching the installed JDK 21):

```xml
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-controls</artifactId>
            <version>21.0.12</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-fxml</artifactId>
            <version>21.0.12</version>
        </dependency>
```

No OS/arch classifier is set here (see design doc Decision #9) — `javafx-maven-plugin` resolves the right one for whatever machine runs the build.

- [ ] **Step 2: Add the `javafx-maven-plugin` build plugin**, alongside the existing `exec-maven-plugin`:

```xml
            <plugin>
                <groupId>org.openjfx</groupId>
                <artifactId>javafx-maven-plugin</artifactId>
                <version>0.0.8</version>
                <configuration>
                    <mainClass>com.matchmaker.client.ClientMain</mainClass>
                </configuration>
            </plugin>
```

- [ ] **Step 3: Verify** — `mvn compile` still succeeds (there's no client code yet, so this just proves the new dependencies/plugin resolve and don't break the build).

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "Add JavaFX dependencies and javafx-maven-plugin"
```

---

### Task 3: Communication layer contracts + test fake

**Files:**
- Create: `src/main/java/com/matchmaker/client/communication/ServerEventListener.java`
- Create: `src/main/java/com/matchmaker/client/communication/Subscription.java`
- Create: `src/main/java/com/matchmaker/client/communication/ServerCommunicationException.java`
- Create: `src/main/java/com/matchmaker/client/communication/ServerConnection.java`
- Create: `src/test/java/com/matchmaker/client/communication/InMemoryServerConnection.java`

No failing-test step here — these are plain contracts/a fake with no behavior of their own to assert yet; Task 4's `GameClientServiceTest` is what actually exercises `InMemoryServerConnection` and would fail to compile without it.

- [ ] **Step 1: `ServerEventListener`**

```java
package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameEventDTO;

@FunctionalInterface
public interface ServerEventListener {
    void onEvent(GameEventDTO event);
}
```

- [ ] **Step 2: `Subscription`**

```java
package com.matchmaker.client.communication;

@FunctionalInterface
public interface Subscription {
    void close();
}
```

- [ ] **Step 3: `ServerCommunicationException`**

```java
package com.matchmaker.client.communication;

public class ServerCommunicationException extends RuntimeException {
    public ServerCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: `ServerConnection`**

```java
package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.util.List;

public interface ServerConnection {

    UserDTO register(String username, String password) throws UsernameTakenException;

    LoginResultDTO login(String username, String password) throws AuthenticationException;

    List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException;

    GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws AuthenticationException;

    void cancelQueue(String sessionToken) throws AuthenticationException;

    GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

    Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener);

    Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener);
}
```

(`RemoteException` is deliberately absent from every signature — the real implementation, Task 5, catches it and rethrows the unchecked `ServerCommunicationException`, the same "don't make callers declare a plumbing failure" reasoning `server/dao/DaoException` already uses for `SQLException`.)

- [ ] **Step 5: `InMemoryServerConnection`** (test fake — configurable results/failures, records calls, and lets a test manually fire a queued/topic event to simulate a server push, the same role `InMemoryGameEventPublisher` plays for the server side):

```java
package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryServerConnection implements ServerConnection {

    public record MakeMoveCall(String sessionToken, int gameSessionId, String movePayload) {
    }

    private LoginResultDTO loginResult;
    private AuthenticationException loginFailure;
    private UserDTO registerResult;
    private UsernameTakenException registerFailure;
    private List<GameTypeDTO> gameTypes = new ArrayList<>();
    private GameStateDTO joinQueueResult;
    private boolean cancelQueueCalled = false;
    private GameStateDTO makeMoveResult;
    private IllegalMoveException makeMoveFailure;

    private final List<MakeMoveCall> makeMoveCalls = new ArrayList<>();
    private final Map<Integer, List<ServerEventListener>> playerQueueListeners = new HashMap<>();
    private final Map<Integer, List<ServerEventListener>> sessionTopicListeners = new HashMap<>();

    public void setLoginResult(LoginResultDTO result) { this.loginResult = result; }
    public void setLoginFailure(AuthenticationException failure) { this.loginFailure = failure; }
    public void setRegisterResult(UserDTO result) { this.registerResult = result; }
    public void setRegisterFailure(UsernameTakenException failure) { this.registerFailure = failure; }
    public void setGameTypes(List<GameTypeDTO> gameTypes) { this.gameTypes = gameTypes; }
    public void setJoinQueueResult(GameStateDTO result) { this.joinQueueResult = result; }
    public void setMakeMoveResult(GameStateDTO result) { this.makeMoveResult = result; }
    public void setMakeMoveFailure(IllegalMoveException failure) { this.makeMoveFailure = failure; }
    public boolean wasCancelQueueCalled() { return cancelQueueCalled; }
    public List<MakeMoveCall> makeMoveCalls() { return makeMoveCalls; }

    @Override
    public UserDTO register(String username, String password) throws UsernameTakenException {
        if (registerFailure != null) throw registerFailure;
        return registerResult;
    }

    @Override
    public LoginResultDTO login(String username, String password) throws AuthenticationException {
        if (loginFailure != null) throw loginFailure;
        return loginResult;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) {
        return gameTypes;
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) {
        return joinQueueResult;
    }

    @Override
    public void cancelQueue(String sessionToken) {
        cancelQueueCalled = true;
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload) throws IllegalMoveException {
        makeMoveCalls.add(new MakeMoveCall(sessionToken, gameSessionId, movePayload));
        if (makeMoveFailure != null) throw makeMoveFailure;
        return makeMoveResult;
    }

    @Override
    public Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener) {
        playerQueueListeners.computeIfAbsent(userId, id -> new ArrayList<>()).add(listener);
        return () -> playerQueueListeners.getOrDefault(userId, List.of()).remove(listener);
    }

    @Override
    public Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        sessionTopicListeners.computeIfAbsent(sessionId, id -> new ArrayList<>()).add(listener);
        return () -> sessionTopicListeners.getOrDefault(sessionId, List.of()).remove(listener);
    }

    public boolean isSubscribedToPlayerQueue(int userId) {
        return !playerQueueListeners.getOrDefault(userId, List.of()).isEmpty();
    }

    public boolean isSubscribedToSessionTopic(int sessionId) {
        return !sessionTopicListeners.getOrDefault(sessionId, List.of()).isEmpty();
    }

    public void firePlayerQueueEvent(int userId, GameEventDTO event) {
        for (ServerEventListener listener : List.copyOf(playerQueueListeners.getOrDefault(userId, List.of()))) {
            listener.onEvent(event);
        }
    }

    public void fireSessionTopicEvent(int sessionId, GameEventDTO event) {
        for (ServerEventListener listener : List.copyOf(sessionTopicListeners.getOrDefault(sessionId, List.of()))) {
            listener.onEvent(event);
        }
    }
}
```

- [ ] **Step 6: Compile** — `mvn compile` — succeeds (nothing depends on these yet; this just proves they're syntactically sound). `mvn test-compile` too, since `InMemoryServerConnection` lives in `src/test`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matchmaker/client/communication/ src/test/java/com/matchmaker/client/communication/
git commit -m "Add client communication-layer contracts and test fake"
```

---

### Task 4: `GameClientService` (Logic layer)

**Files:**
- Create: `src/main/java/com/matchmaker/client/logic/GameClientService.java`
- Test: `src/test/java/com/matchmaker/client/logic/GameClientServiceTest.java`

This is the task that actually earns the interface-behind-`ServerConnection` design (Decision #3) — full unit coverage, no RMI/JMS/JavaFX runtime involved. Note: `GameClientService` calls `javafx.application.Platform.runLater(...)`, which requires the JavaFX runtime to be *initialized* (not necessarily showing a window) — tests handle this via `Platform.startup(() -> {})` once, guarded against `IllegalStateException` if already started (JUnit may reuse the JVM across test classes).

- [ ] **Step 1: Write the failing tests** — `GameClientServiceTest.java`:

```java
package com.matchmaker.client.logic;

import com.matchmaker.client.communication.InMemoryServerConnection;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GameClientServiceTest {

    private InMemoryServerConnection serverConnection;
    private GameClientService service;

    @BeforeAll
    static void initJavaFxRuntime() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // Fine -- another test class in this JVM already initialized the toolkit.
        }
    }

    @BeforeEach
    void setUp() {
        serverConnection = new InMemoryServerConnection();
        service = new GameClientService(serverConnection);
    }

    /** Every GameClientService callback fires via Platform.runLater -- block until it does. */
    private <T> T await(java.util.function.Consumer<java.util.function.Consumer<T>> triggerWithCapture) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> captured = new AtomicReference<>();
        triggerWithCapture.accept(value -> { captured.set(value); latch.countDown(); });
        assertTrue(latch.await(2, TimeUnit.SECONDS), "callback never fired");
        return captured.get();
    }

    @Test
    void login_success_storesSessionAndReturnsUser() throws Exception {
        UserDTO user = new UserDTO(1, "alice", false, 0, 0, 0, 1000);
        serverConnection.setLoginResult(new LoginResultDTO(user, "token-123"));

        UserDTO result = await(capture -> service.login("alice", "pw", capture, err -> fail(err)));

        assertEquals("alice", result.getUsername());
        assertEquals(user.getId(), service.getCurrentUser().getId());
    }

    @Test
    void login_failure_invokesOnError() throws Exception {
        serverConnection.setLoginFailure(new AuthenticationException("bad password"));

        Throwable error = await(capture -> service.login("alice", "wrong", user -> fail("should not succeed"), capture));

        assertInstanceOf(AuthenticationException.class, error);
    }

    @Test
    void register_success_returnsUser() throws Exception {
        serverConnection.setRegisterResult(new UserDTO(2, "bob", false, 0, 0, 0, 1000));

        UserDTO result = await(capture -> service.register("bob", "pw", capture, err -> fail(err)));

        assertEquals("bob", result.getUsername());
    }

    @Test
    void joinQueue_immediateMatch_entersGameWithoutSubscribingToPlayerQueue() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);

        GameStateDTO result = await(capture ->
                service.joinQueue(1, capture, () -> fail("should not be waiting"), err -> fail(err)));

        assertEquals(5, result.getSessionId());
        assertFalse(serverConnection.isSubscribedToPlayerQueue(1), "matched immediately -- no need to wait on the player queue");
        assertTrue(serverConnection.isSubscribedToSessionTopic(5), "must subscribe to the session topic before returning control");
    }

    @Test
    void joinQueue_noOpponentYet_subscribesToPlayerQueueAndWaits() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);

        Boolean waited = await(capture ->
                service.joinQueue(1, matched -> fail("should not be matched yet"), () -> capture.accept(true), err -> fail(err)));

        assertTrue(waited);
        assertTrue(serverConnection.isSubscribedToPlayerQueue(1));
    }

    @Test
    void joinQueue_deferredMatchFoundPush_invokesOriginalOnMatchedAndSwitchesSubscriptions() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);
        await(capture -> service.joinQueue(1, m -> fail("not yet"), () -> capture.accept(true), err -> fail(err)));

        GameStateDTO matched = new GameStateDTO(9, 1, 2, 1, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        // Re-issue joinQueue's onMatched via a second call is wrong -- instead simulate the real
        // push GameClientService is already listening for.
        service.setTestOnlyMatchCallbackCapture(captured, latch); // see Step 3 note below
        serverConnection.firePlayerQueueEvent(1, new GameEventDTO(GameEventType.MATCH_FOUND, 9, matched));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(9, captured.get().getSessionId());
        assertFalse(serverConnection.isSubscribedToPlayerQueue(1), "no longer waiting once matched");
        assertTrue(serverConnection.isSubscribedToSessionTopic(9));
    }

    @Test
    void cancelQueue_closesPlayerQueueSubscription() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);
        await(capture -> service.joinQueue(1, m -> fail("not yet"), () -> capture.accept(true), err -> fail(err)));

        await(capture -> service.cancelQueue(() -> capture.accept(true), err -> fail(err)));

        assertTrue(serverConnection.wasCancelQueueCalled());
        assertFalse(serverConnection.isSubscribedToPlayerQueue(1));
    }

    @Test
    void makeMove_success_updatesCurrentGameStateAndReachesOnSuccess() throws Exception {
        loginAsUser(1);
        GameStateDTO updated = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.setMakeMoveResult(updated);

        GameStateDTO result = await(capture ->
                service.makeMove(5, "{\"path\":[\"b3\",\"a4\"]}", capture, err -> fail(err)));

        assertEquals(2, result.getCurrentTurnUserId());
        assertEquals(1, serverConnection.makeMoveCalls().size());
    }

    @Test
    void makeMove_failure_invokesOnError() throws Exception {
        loginAsUser(1);
        serverConnection.setMakeMoveFailure(new IllegalMoveException("nope"));

        Throwable error = await(capture ->
                service.makeMove(5, "{\"path\":[\"b3\",\"b5\"]}", r -> fail("should not succeed"), capture));

        assertInstanceOf(IllegalMoveException.class, error);
    }

    @Test
    void enterGame_pushedMoveMadeEvent_reachesTheAttachedGameUpdateListener() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);
        await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(err)));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        GameStateDTO latest = service.attachGameUpdateListener(state -> { captured.set(state); latch.countDown(); });
        assertEquals(5, latest.getSessionId(), "attaching should immediately replay the latest known state");

        GameStateDTO pushed = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.fireSessionTopicEvent(5, new GameEventDTO(GameEventType.MOVE_MADE, 5, pushed));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, captured.get().getCurrentTurnUserId());
    }

    @Test
    void leaveGame_closesSessionTopicSubscriptionAndStopsUpdates() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);
        await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(err)));

        service.leaveGame();

        assertFalse(serverConnection.isSubscribedToSessionTopic(5));
    }

    private void loginAsUser(int userId) throws InterruptedException {
        UserDTO user = new UserDTO(userId, "user" + userId, false, 0, 0, 0, 1000);
        serverConnection.setLoginResult(new LoginResultDTO(user, "token-" + userId));
        await(capture -> service.login("user" + userId, "pw", capture, err -> fail(err)));
    }
}
```

**Note on Step 1's `joinQueue_deferredMatchFoundPush...` test** — it references a `service.setTestOnlyMatchCallbackCapture(...)` that doesn't belong in real production code. Replace it before implementing: the cleanest way to observe the deferred match without a test-only hook is to have `onWaiting` (already required by `joinQueue`'s signature) capture nothing extra, and instead pass the *real* `onMatched` consumer wired to the same latch/captured-reference, since `GameClientService` is specified (Step 2 below) to store and reuse that exact `onMatched` callback for the deferred case:

```java
@Test
void joinQueue_deferredMatchFoundPush_invokesOriginalOnMatchedAndSwitchesSubscriptions() throws Exception {
    loginAsUser(1);
    serverConnection.setJoinQueueResult(null);

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<GameStateDTO> captured = new AtomicReference<>();
    CountDownLatch waitingLatch = new CountDownLatch(1);
    service.joinQueue(1,
            matched -> { captured.set(matched); latch.countDown(); },
            waitingLatch::countDown,
            err -> fail(err));
    assertTrue(waitingLatch.await(2, TimeUnit.SECONDS));

    GameStateDTO matched = new GameStateDTO(9, 1, 2, 1, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
    serverConnection.firePlayerQueueEvent(1, new GameEventDTO(GameEventType.MATCH_FOUND, 9, matched));

    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertEquals(9, captured.get().getSessionId());
    assertFalse(serverConnection.isSubscribedToPlayerQueue(1), "no longer waiting once matched");
    assertTrue(serverConnection.isSubscribedToSessionTopic(9));
}
```

Use this corrected version in the actual test file — it's simpler and doesn't need any test-only production hook.

- [ ] **Step 2: Run to verify it fails** — `mvn test -Dtest=GameClientServiceTest` — compile error, `GameClientService` doesn't exist yet.

- [ ] **Step 3: Implement `GameClientService`**

```java
package com.matchmaker.client.logic;

import com.matchmaker.client.communication.ServerConnection;
import com.matchmaker.client.communication.Subscription;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class GameClientService {

    private final ServerConnection serverConnection;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "server-communication");
        thread.setDaemon(true);
        return thread;
    });

    private UserDTO currentUser;
    private String sessionToken;

    private Subscription playerQueueSubscription;
    private Consumer<GameStateDTO> pendingMatchCallback;

    private Subscription sessionTopicSubscription;
    private volatile GameStateDTO currentGameState;
    private volatile Consumer<GameStateDTO> gameUpdateListener;

    public GameClientService(ServerConnection serverConnection) {
        this.serverConnection = serverConnection;
    }

    public UserDTO getCurrentUser() {
        return currentUser;
    }

    public void login(String username, String password, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.login(username, password),
                result -> {
                    currentUser = result.getUser();
                    sessionToken = result.getSessionToken();
                    onSuccess.accept(result.getUser());
                },
                onError);
    }

    public void register(String username, String password, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.register(username, password), onSuccess, onError);
    }

    public void listGameTypes(Consumer<List<GameTypeDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.listGameTypes(sessionToken), onSuccess, onError);
    }

    public void joinQueue(int gameTypeId, Consumer<GameStateDTO> onMatched, Runnable onWaiting, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.joinQueue(sessionToken, gameTypeId),
                result -> {
                    if (result != null) {
                        enterGame(result);
                        onMatched.accept(result);
                    } else {
                        pendingMatchCallback = onMatched;
                        playerQueueSubscription = serverConnection.subscribeToPlayerQueue(
                                currentUser.getId(), this::onPlayerQueueEvent);
                        onWaiting.run();
                    }
                },
                onError);
    }

    public void cancelQueue(Runnable onCancelled, Consumer<Throwable> onError) {
        runAsync(() -> { serverConnection.cancelQueue(sessionToken); return null; },
                ignored -> {
                    closePlayerQueueSubscription();
                    onCancelled.run();
                },
                onError);
    }

    public void makeMove(int gameSessionId, String movePayload, Consumer<GameStateDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.makeMove(sessionToken, gameSessionId, movePayload),
                result -> {
                    currentGameState = result;
                    onSuccess.accept(result);
                },
                onError);
    }

    /** Registers for live session-topic pushes and returns whatever the latest known state is,
     *  so the caller (GameBoardController) can render immediately even if it attaches slightly
     *  after enterGame() already subscribed -- nothing published in that gap is missed. */
    public GameStateDTO attachGameUpdateListener(Consumer<GameStateDTO> listener) {
        this.gameUpdateListener = listener;
        return currentGameState;
    }

    public void leaveGame() {
        gameUpdateListener = null;
        if (sessionTopicSubscription != null) {
            sessionTopicSubscription.close();
            sessionTopicSubscription = null;
        }
        currentGameState = null;
    }

    public void shutdown() {
        backgroundExecutor.shutdownNow();
    }

    /** Subscribes to the session's topic the instant a session id is known -- before returning
     *  control to any caller -- per the design doc's Queue-vs-Topic delivery-guarantee note:
     *  a Topic gives no retention to a late subscriber, so this must happen first, always. */
    private void enterGame(GameStateDTO initialState) {
        currentGameState = initialState;
        sessionTopicSubscription = serverConnection.subscribeToSessionTopic(
                initialState.getSessionId(), this::onSessionTopicEvent);
    }

    private void onPlayerQueueEvent(GameEventDTO event) {
        if (event.getType() != GameEventType.MATCH_FOUND) {
            return;
        }
        Platform.runLater(() -> {
            closePlayerQueueSubscription();
            GameStateDTO matchedState = event.getGameState();
            enterGame(matchedState);
            if (pendingMatchCallback != null) {
                Consumer<GameStateDTO> callback = pendingMatchCallback;
                pendingMatchCallback = null;
                callback.accept(matchedState);
            }
        });
    }

    private void onSessionTopicEvent(GameEventDTO event) {
        if (event.getType() != GameEventType.MOVE_MADE) {
            return;
        }
        Platform.runLater(() -> {
            currentGameState = event.getGameState();
            if (gameUpdateListener != null) {
                gameUpdateListener.accept(currentGameState);
            }
        });
    }

    private void closePlayerQueueSubscription() {
        if (playerQueueSubscription != null) {
            playerQueueSubscription.close();
            playerQueueSubscription = null;
        }
        pendingMatchCallback = null;
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

- [ ] **Step 4: Run to verify all tests pass** — `mvn test -Dtest=GameClientServiceTest` — PASS.

- [ ] **Step 5: Run the full suite** — `mvn test` (Docker not required for anything in this task).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matchmaker/client/logic/GameClientService.java \
        src/test/java/com/matchmaker/client/logic/GameClientServiceTest.java
git commit -m "Add GameClientService, the client's Logic layer, with full unit coverage"
```

---

### Task 5: `RmiJmsServerConnection` (real Communication implementation)

**Files:** Create `src/main/java/com/matchmaker/client/communication/RmiJmsServerConnection.java`.

No unit test for this one (it's the RMI/JMS-touching class the interface exists specifically to keep out of the unit-tested core) — it gets exercised by the Task 8 manual end-to-end run. Its internal shape mirrors `server/jms/ActiveMqGameEventPublisher`'s existing `synchronized` + shared-`Session` pattern.

- [ ] **Step 1: Implement**

```java
package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.common.rmi.PlayerService;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;

public class RmiJmsServerConnection implements ServerConnection {

    private final AuthService authService;
    private final PlayerService playerService;
    private final Connection jmsConnection;
    private final Session jmsSession;

    public RmiJmsServerConnection(String host, int rmiPort, int jmsPort) {
        try {
            Registry registry = LocateRegistry.getRegistry(host, rmiPort);
            authService = (AuthService) registry.lookup("AuthService");
            playerService = (PlayerService) registry.lookup("PlayerService");
        } catch (RemoteException | NotBoundException e) {
            throw new ServerCommunicationException("Failed to connect to RMI registry at " + host + ":" + rmiPort, e);
        }

        try {
            // Deliberately not reusing server.jms.JmsConnectionFactory -- client code never
            // imports from com.matchmaker.server.* (see this plan's Global Constraints).
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://" + host + ":" + jmsPort);
            factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));
            jmsConnection = factory.createConnection();
            jmsConnection.start();
            jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        } catch (JMSException e) {
            throw new ServerCommunicationException("Failed to connect to JMS broker at " + host + ":" + jmsPort, e);
        }
    }

    @Override
    public UserDTO register(String username, String password) throws UsernameTakenException {
        try {
            return authService.register(username, password);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("register() failed", e);
        }
    }

    @Override
    public LoginResultDTO login(String username, String password) throws AuthenticationException {
        try {
            return authService.login(username, password);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("login() failed", e);
        }
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException {
        try {
            return playerService.listGameTypes(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("listGameTypes() failed", e);
        }
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws AuthenticationException {
        try {
            return playerService.joinQueue(sessionToken, gameTypeId);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("joinQueue() failed", e);
        }
    }

    @Override
    public void cancelQueue(String sessionToken) throws AuthenticationException {
        try {
            playerService.cancelQueue(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("cancelQueue() failed", e);
        }
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException {
        try {
            return playerService.makeMove(sessionToken, gameSessionId, movePayload);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("makeMove() failed", e);
        }
    }

    @Override
    public Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener) {
        return subscribe(session -> session.createQueue("player." + userId + ".events"), listener);
    }

    @Override
    public Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        return subscribe(session -> session.createTopic("session." + sessionId + ".events"), listener);
    }

    public void close() {
        try {
            jmsConnection.close();
        } catch (JMSException e) {
            System.err.println("Failed to close JMS connection: " + e.getMessage());
        }
    }

    // A javax.jms.Session may only be used by one thread at a time -- see the identical note on
    // ActiveMqGameEventPublisher, which this mirrors.
    private synchronized Subscription subscribe(DestinationFactory destinationFactory, ServerEventListener listener) {
        try {
            Destination destination = destinationFactory.create(jmsSession);
            MessageConsumer consumer = jmsSession.createConsumer(destination);
            consumer.setMessageListener(message -> {
                try {
                    GameEventDTO event = (GameEventDTO) ((ObjectMessage) message).getObject();
                    listener.onEvent(event);
                } catch (JMSException e) {
                    System.err.println("Failed to read a JMS event: " + e.getMessage());
                }
            });
            return () -> {
                try {
                    consumer.close();
                } catch (JMSException e) {
                    System.err.println("Failed to close a JMS subscription: " + e.getMessage());
                }
            };
        } catch (JMSException e) {
            throw new ServerCommunicationException("Failed to subscribe", e);
        }
    }

    @FunctionalInterface
    private interface DestinationFactory {
        Destination create(Session session) throws JMSException;
    }
}
```

- [ ] **Step 2: Compile** — `mvn compile` — succeeds.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/matchmaker/client/communication/RmiJmsServerConnection.java
git commit -m "Add RmiJmsServerConnection, the real RMI+JMS Communication-layer implementation"
```

---

### Task 6: Presentation skeleton, Login/Register, Lobby

**Files:**
- Create: `src/main/java/com/matchmaker/client/ClientMain.java`
- Create: `src/main/java/com/matchmaker/client/presentation/SceneNavigator.java`
- Create: `src/main/java/com/matchmaker/client/presentation/LoginController.java` + `src/main/resources/com/matchmaker/client/presentation/LoginView.fxml`
- Create: `src/main/java/com/matchmaker/client/presentation/LobbyController.java` + `src/main/resources/com/matchmaker/client/presentation/LobbyView.fxml`

No automated tests (see Global Constraints) — each step ends in a compile check, and Task 8 covers manual verification once every screen exists.

- [ ] **Step 1: `SceneNavigator`**

```java
package com.matchmaker.client.presentation;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class SceneNavigator {

    private final Stage stage;

    public SceneNavigator(Stage stage) {
        this.stage = stage;
    }

    public <T> T show(String fxmlResource, String title) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlResource));
            Parent root = loader.load();
            stage.setScene(new Scene(root));
            stage.setTitle(title);
            stage.show();
            return loader.getController();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + fxmlResource, e);
        }
    }
}
```

- [ ] **Step 2: `LoginView.fxml`** (path: `src/main/resources/com/matchmaker/client/presentation/LoginView.fxml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.geometry.Insets?>
<?import javafx.scene.control.*?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.VBox?>

<VBox xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml"
      fx:controller="com.matchmaker.client.presentation.LoginController"
      spacing="12" alignment="CENTER">
    <padding><Insets top="40" right="40" bottom="40" left="40"/></padding>
    <Label text="MatchMaker" style="-fx-font-size: 24px; -fx-font-weight: bold;"/>
    <TextField fx:id="usernameField" promptText="Username" maxWidth="240"/>
    <PasswordField fx:id="passwordField" promptText="Password" maxWidth="240"/>
    <HBox spacing="10" alignment="CENTER">
        <Button fx:id="loginButton" text="Login" onAction="#onLogin"/>
        <Button fx:id="registerButton" text="Register" onAction="#onRegister"/>
    </HBox>
    <Label fx:id="statusLabel" textFill="#b00020" wrapText="true" maxWidth="280"/>
</VBox>
```

- [ ] **Step 3: `LoginController`**

```java
package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label statusLabel;
    @FXML private Button loginButton;
    @FXML private Button registerButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
    }

    @FXML
    private void onLogin() {
        setControlsDisabled(true);
        gameClientService.login(usernameField.getText(), passwordField.getText(),
                user -> {
                    LobbyController controller = navigator.show("LobbyView.fxml", "MatchMaker - Lobby");
                    controller.init(gameClientService, navigator);
                },
                error -> {
                    setControlsDisabled(false);
                    statusLabel.setText(error.getMessage());
                });
    }

    @FXML
    private void onRegister() {
        setControlsDisabled(true);
        gameClientService.register(usernameField.getText(), passwordField.getText(),
                user -> {
                    setControlsDisabled(false);
                    statusLabel.setTextFill(javafx.scene.paint.Color.GREEN);
                    statusLabel.setText("Registered -- now click Login.");
                },
                error -> {
                    setControlsDisabled(false);
                    statusLabel.setTextFill(javafx.scene.paint.Color.web("#b00020"));
                    statusLabel.setText(error.getMessage());
                });
    }

    private void setControlsDisabled(boolean disabled) {
        loginButton.setDisable(disabled);
        registerButton.setDisable(disabled);
    }
}
```

- [ ] **Step 4: `LobbyView.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.geometry.Insets?>
<?import javafx.scene.control.*?>
<?import javafx.scene.layout.VBox?>

<VBox xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml"
      fx:controller="com.matchmaker.client.presentation.LobbyController"
      spacing="12" alignment="TOP_CENTER">
    <padding><Insets top="30" right="30" bottom="30" left="30"/></padding>
    <Label text="Choose a game" style="-fx-font-size: 18px;"/>
    <ListView fx:id="gameTypeList" maxWidth="320" prefHeight="200"/>
    <Button fx:id="joinButton" text="Join Queue" onAction="#onJoinQueue"/>
    <Label fx:id="statusLabel" textFill="#b00020" wrapText="true" maxWidth="320"/>
</VBox>
```

- [ ] **Step 5: `LobbyController`**

```java
package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameTypeDTO;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

public class LobbyController {

    @FXML private ListView<GameTypeDTO> gameTypeList;
    @FXML private Button joinButton;
    @FXML private Label statusLabel;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;

        gameTypeList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(GameTypeDTO item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getBoardRows() + "x" + item.getBoardCols() + ")");
            }
        });

        gameClientService.listGameTypes(
                gameTypeList.getItems()::setAll,
                error -> statusLabel.setText(error.getMessage()));
    }

    @FXML
    private void onJoinQueue() {
        GameTypeDTO selected = gameTypeList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("Pick a game first.");
            return;
        }
        joinButton.setDisable(true);
        gameClientService.joinQueue(selected.getId(),
                matchedState -> {
                    GameBoardController controller = navigator.show("GameBoardView.fxml", "MatchMaker - Game");
                    controller.init(gameClientService, navigator, matchedState);
                },
                () -> {
                    MatchmakingWaitController controller = navigator.show("MatchmakingWaitView.fxml", "MatchMaker - Waiting");
                    controller.init(gameClientService, navigator);
                },
                error -> {
                    joinButton.setDisable(false);
                    statusLabel.setText(error.getMessage());
                });
    }
}
```

(Two forward references, `GameBoardController`/`MatchmakingWaitController`, don't exist until Task 7 — this file won't compile until then. That's fine; Task 7 is next and this whole task's compile check happens at the end of Task 7 instead. Note this explicitly when executing.)

- [ ] **Step 6: `ClientMain`**

```java
package com.matchmaker.client;

import com.matchmaker.client.communication.RmiJmsServerConnection;
import com.matchmaker.client.communication.ServerConnection;
import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.client.presentation.LoginController;
import com.matchmaker.client.presentation.SceneNavigator;
import javafx.application.Application;
import javafx.stage.Stage;

public class ClientMain extends Application {

    // Duplicated from ServerMain's RMI_PORT/JMS_PORT rather than imported -- client code never
    // depends on com.matchmaker.server.* (see the implementation plan's Global Constraints).
    private static final String SERVER_HOST = "localhost";
    private static final int RMI_PORT = 1099;
    private static final int JMS_PORT = 61616;

    private RmiJmsServerConnection serverConnection;

    @Override
    public void start(Stage primaryStage) {
        serverConnection = new RmiJmsServerConnection(SERVER_HOST, RMI_PORT, JMS_PORT);
        GameClientService gameClientService = new GameClientService(serverConnection);
        SceneNavigator navigator = new SceneNavigator(primaryStage);

        LoginController controller = navigator.show("LoginView.fxml", "MatchMaker - Login");
        controller.init(gameClientService, navigator);
    }

    @Override
    public void stop() {
        if (serverConnection != null) {
            serverConnection.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 7: Commit** (compile check happens at the end of Task 7, once the two forward-referenced controllers exist)

```bash
git add src/main/java/com/matchmaker/client/ClientMain.java \
        src/main/java/com/matchmaker/client/presentation/SceneNavigator.java \
        src/main/java/com/matchmaker/client/presentation/LoginController.java \
        src/main/java/com/matchmaker/client/presentation/LobbyController.java \
        src/main/resources/com/matchmaker/client/presentation/LoginView.fxml \
        src/main/resources/com/matchmaker/client/presentation/LobbyView.fxml
git commit -m "Add ClientMain, SceneNavigator, Login/Register and Lobby screens"
```

---

### Task 7: Matchmaking Wait and Game Board screens

**Files:**
- Create: `src/main/java/com/matchmaker/client/presentation/MatchmakingWaitController.java` + `.../MatchmakingWaitView.fxml`
- Create: `src/main/java/com/matchmaker/client/presentation/GameBoardController.java` + `.../GameBoardView.fxml`

- [ ] **Step 1: `MatchmakingWaitView.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.geometry.Insets?>
<?import javafx.scene.control.*?>
<?import javafx.scene.layout.VBox?>

<VBox xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml"
      fx:controller="com.matchmaker.client.presentation.MatchmakingWaitController"
      spacing="16" alignment="CENTER">
    <padding><Insets top="40" right="40" bottom="40" left="40"/></padding>
    <Label fx:id="statusLabel" style="-fx-font-size: 16px;"/>
    <ProgressIndicator/>
    <Button fx:id="cancelButton" text="Cancel" onAction="#onCancel"/>
</VBox>
```

- [ ] **Step 2: `MatchmakingWaitController`**

```java
package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

public class MatchmakingWaitController {

    @FXML private Label statusLabel;
    @FXML private Button cancelButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;

    public void init(GameClientService gameClientService, SceneNavigator navigator) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        statusLabel.setText("Waiting for an opponent...");
    }

    @FXML
    private void onCancel() {
        cancelButton.setDisable(true);
        gameClientService.cancelQueue(
                () -> {
                    LobbyController controller = navigator.show("LobbyView.fxml", "MatchMaker - Lobby");
                    controller.init(gameClientService, navigator);
                },
                error -> {
                    cancelButton.setDisable(false);
                    statusLabel.setText(error.getMessage());
                });
    }
}
```

Note the design's payoff here: this controller has no `onMatched`-style method. `GameClientService` already stores and reuses the exact `onMatched` consumer `LobbyController` passed into `joinQueue()` (Task 4, `pendingMatchCallback`), so the deferred `MATCH_FOUND` push drives navigation to `GameBoardView` directly from `GameClientService`'s callback, regardless of which screen happens to be showing when it arrives. One navigation-to-game-board code path, not two.

- [ ] **Step 3: `GameBoardView.fxml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?import javafx.geometry.Insets?>
<?import javafx.scene.control.*?>
<?import javafx.scene.layout.BorderPane?>
<?import javafx.scene.layout.GridPane?>
<?import javafx.scene.layout.HBox?>
<?import javafx.scene.layout.VBox?>

<BorderPane xmlns="http://javafx.com/javafx" xmlns:fx="http://javafx.com/fxml"
            fx:controller="com.matchmaker.client.presentation.GameBoardController">
    <top>
        <Label fx:id="statusLabel" style="-fx-font-size: 16px;">
            <BorderPane.margin><Insets top="10" right="10" bottom="10" left="10"/></BorderPane.margin>
        </Label>
    </top>
    <center>
        <GridPane fx:id="boardGrid" alignment="CENTER">
            <BorderPane.margin><Insets top="10" right="10" bottom="10" left="10"/></BorderPane.margin>
        </GridPane>
    </center>
    <bottom>
        <VBox spacing="8" alignment="CENTER">
            <BorderPane.margin><Insets top="10" right="10" bottom="20" left="10"/></BorderPane.margin>
            <HBox spacing="10" alignment="CENTER">
                <Button fx:id="submitButton" text="Submit Move" onAction="#onSubmitMove"/>
                <Button fx:id="clearButton" text="Clear Selection" onAction="#onClearSelection"/>
            </HBox>
            <Button fx:id="backToLobbyButton" text="Back to Lobby" onAction="#onBackToLobby" visible="false"/>
        </VBox>
    </bottom>
</BorderPane>
```

- [ ] **Step 4: `GameBoardController`**

```java
package com.matchmaker.client.presentation;

import com.matchmaker.client.logic.GameClientService;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class GameBoardController {

    private static final int BOARD_SIZE = 8;

    @FXML private Label statusLabel;
    @FXML private GridPane boardGrid;
    @FXML private Button submitButton;
    @FXML private Button clearButton;
    @FXML private Button backToLobbyButton;

    private GameClientService gameClientService;
    private SceneNavigator navigator;
    private GameStateDTO currentState;
    private final List<String> selectedPath = new ArrayList<>();

    public void init(GameClientService gameClientService, SceneNavigator navigator, GameStateDTO initialState) {
        this.gameClientService = gameClientService;
        this.navigator = navigator;
        GameStateDTO latest = gameClientService.attachGameUpdateListener(this::applyState);
        applyState(latest != null ? latest : initialState);
    }

    private void applyState(GameStateDTO state) {
        this.currentState = state;
        selectedPath.clear();
        renderBoard(state);
        updateStatusLabel(state);

        boolean finished = state.getStatus() == GameStatus.FINISHED;
        submitButton.setDisable(finished);
        clearButton.setDisable(finished);
        backToLobbyButton.setVisible(finished);
    }

    private void updateStatusLabel(GameStateDTO state) {
        if (state.getStatus() == GameStatus.FINISHED) {
            Integer winnerId = state.getWinnerId();
            int myId = gameClientService.getCurrentUser().getId();
            if (winnerId == null) {
                statusLabel.setText("Game over -- draw.");
            } else if (winnerId == myId) {
                statusLabel.setText("You won!");
            } else {
                statusLabel.setText("You lost.");
            }
        } else {
            statusLabel.setText(isMyTurn() ? "Your turn" : "Waiting for opponent...");
        }
    }

    private void renderBoard(GameStateDTO state) {
        boardGrid.getChildren().clear();
        JSONObject board = new JSONObject(state.getBoardState());
        JSONObject pieces = board.getJSONObject("pieces");

        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                boolean dark = (row + col) % 2 == 1;
                String algebraic = toAlgebraic(row, col);
                StackPane cell = buildCell(dark, algebraic, pieces);
                // Rank 1 (row 0) renders at the bottom of the screen, rank 8 at the top --
                // standard board orientation, fixed regardless of which player is viewing
                // (see design doc's Out of scope: no per-player board flip).
                boardGrid.add(cell, col, BOARD_SIZE - 1 - row);
            }
        }
    }

    private StackPane buildCell(boolean dark, String algebraic, JSONObject pieces) {
        StackPane cell = new StackPane();
        cell.setPrefSize(60, 60);

        String backgroundColor = dark ? "#5c4033" : "#e8d5b7";
        String borderStyle = selectedPath.contains(algebraic) ? "-fx-border-color: gold; -fx-border-width: 3;" : "";
        cell.setStyle("-fx-background-color: " + backgroundColor + "; " + borderStyle);

        if (dark && pieces.has(algebraic)) {
            char piece = pieces.getString(algebraic).charAt(0);
            Circle disc = new Circle(20);
            disc.setFill(Character.toLowerCase(piece) == 'b' ? Color.BLACK : Color.WHITE);
            boolean king = Character.isUpperCase(piece);
            disc.setStroke(king ? Color.GOLD : Color.GRAY);
            disc.setStrokeWidth(king ? 3 : 1);
            cell.getChildren().add(disc);
        }

        if (dark) {
            cell.setOnMouseClicked(event -> onSquareClicked(algebraic));
        }
        return cell;
    }

    private void onSquareClicked(String algebraic) {
        if (currentState == null || currentState.getStatus() != GameStatus.ACTIVE || !isMyTurn()) {
            return;
        }
        if (selectedPath.isEmpty() && !isOwnPiece(algebraic)) {
            return;
        }
        selectedPath.add(algebraic);
        renderBoard(currentState);
    }

    @FXML
    private void onSubmitMove() {
        if (selectedPath.size() < 2) {
            statusLabel.setText("Select an origin and at least one destination square first.");
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("path", new JSONArray(selectedPath));
        submitButton.setDisable(true);

        gameClientService.makeMove(currentState.getSessionId(), payload.toString(),
                this::applyState,
                error -> {
                    submitButton.setDisable(false);
                    selectedPath.clear();
                    renderBoard(currentState);
                    statusLabel.setText(error.getMessage());
                });
    }

    @FXML
    private void onClearSelection() {
        selectedPath.clear();
        renderBoard(currentState);
    }

    @FXML
    private void onBackToLobby() {
        gameClientService.leaveGame();
        LobbyController controller = navigator.show("LobbyView.fxml", "MatchMaker - Lobby");
        controller.init(gameClientService, navigator);
    }

    private boolean isMyTurn() {
        Integer turnUserId = currentState.getCurrentTurnUserId();
        return turnUserId != null && turnUserId == gameClientService.getCurrentUser().getId();
    }

    private boolean isOwnPiece(String algebraic) {
        JSONObject board = new JSONObject(currentState.getBoardState());
        JSONObject pieces = board.getJSONObject("pieces");
        if (!pieces.has(algebraic)) {
            return false;
        }
        char piece = pieces.getString(algebraic).charAt(0);
        boolean isPlayer1 = currentState.getPlayer1Id() == gameClientService.getCurrentUser().getId();
        boolean pieceIsPlayer1 = Character.toLowerCase(piece) == 'b';
        return isPlayer1 == pieceIsPlayer1;
    }

    private static String toAlgebraic(int row, int col) {
        char file = (char) ('a' + col);
        char rank = (char) ('1' + row);
        return "" + file + rank;
    }
}
```

- [ ] **Step 5: Compile everything** — `mvn compile`. This is the first point `LobbyController`'s forward references (`GameBoardController`, `MatchmakingWaitController`) resolve — fix anything that doesn't line up (constructor/method name typos are the likely culprits, not design issues).

- [ ] **Step 6: Manual smoke check (single client, matchmaking-only)** — with `docker compose up -d` and `mvn exec:java` (server) running:
  1. `mvn javafx:run` — the Login screen appears.
  2. Register a user, then log in — Lobby appears and lists "Checkers (8x8)" (seeded by `db/schema.sql`).
  3. Click Join Queue — since no opponent exists yet, the Matchmaking Wait screen appears with a spinner and a working Cancel button (verify Cancel returns to Lobby).
  Full two-player, real-game verification is Task 8.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matchmaker/client/presentation/MatchmakingWaitController.java \
        src/main/java/com/matchmaker/client/presentation/GameBoardController.java \
        src/main/resources/com/matchmaker/client/presentation/MatchmakingWaitView.fxml \
        src/main/resources/com/matchmaker/client/presentation/GameBoardView.fxml
git commit -m "Add Matchmaking Wait and Game Board screens"
```

---

### Task 8: Manual end-to-end verification

No new files — this is the real proof the whole client works, matching the design doc's Testing section ("run two client processes alongside ServerMain, play a full game between them").

- [ ] **Step 1:** `docker compose up -d`, then `mvn exec:java` in one terminal (server).
- [ ] **Step 2:** `mvn javafx:run` in a second terminal — register/login as player A, join the Checkers queue. Confirm the Matchmaking Wait screen appears (no opponent yet).
- [ ] **Step 3:** `mvn javafx:run` in a *third* terminal — register/login as player B, join the Checkers queue. Confirm player B is matched immediately (skips straight to Game Board).
- [ ] **Step 4:** Confirm player A's Matchmaking Wait window *also* transitions to Game Board on its own, within a second or two, with no manual refresh — this is the `MATCH_FOUND` push over the now-network-reachable broker actually working across two separate JVM processes.
- [ ] **Step 5:** Play several moves back and forth (including at least one multi-jump chain, if the board position allows it) — confirm each mover sees their own move applied immediately, and the *other* player's window updates within a second or two without any action on their part — this is the `MOVE_MADE` topic push working live.
- [ ] **Step 6:** Deliberately click an illegal destination and Submit — confirm the error message appears and the board doesn't change.
- [ ] **Step 7:** Play to a win (or force one via a favorable position) — confirm both windows show the correct win/loss banner and the board becomes non-interactive; confirm "Back to Lobby" returns to a working Lobby screen on both.
- [ ] **Step 8:** No commit for this task — it's verification, not a code change. If any step fails, fix the responsible task above and re-verify from Step 1.

---

## Post-plan status update

Once Task 8 passes: update `docs/build-plan.md` (Milestone 7 write-up covering the client, "Next Steps" trimmed to step 9 — the admin client) and `docs/project-structure.md` (new `client/` section, following the existing per-package documentation style; the `server/jms/` entry's broker description also needs its `vm://matchmaker-<uuid>` mention updated to describe the new long-lived `tcp://` broker). Same pattern as every prior milestone — a direct doc edit once everything is verified green, not a plan task with its own test.
