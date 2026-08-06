# RMI Server Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the RMI server skeleton exactly as designed in `docs/superpowers/specs/2026-08-05-rmi-server-skeleton-design.md` — a shared `SessionManager`, a real `AuthServiceImpl`, stub `PlayerServiceImpl`/`AdminServiceImpl`, a runnable `ServerMain`, and an automated integration test that proves real RMI calls work end-to-end.

**Architecture:** New package `com.matchmaker.server` (plus `com.matchmaker.server.rmi` for the three service implementations). `SessionManager` and `AuthServiceImpl` get real, unit-tested behavior against one hardcoded test user. `PlayerServiceImpl`/`AdminServiceImpl` are structurally complete but every method throws `UnsupportedOperationException` pointing at the future roadmap step that implements it. `ServerMain` wires everything into a real RMI registry. A dedicated integration test starts a real registry on a test-only port and calls through a real stub, proving RMI mechanics work without any manual two-terminal testing.

**Tech Stack:** Java 21, Maven, JUnit 5 (already configured from the contracts implementation), `java.rmi` (JDK built-in, no dependency needed).

## Global Constraints

- Package roots: `com.matchmaker.server` (`SessionManager`, `ServerMain`), `com.matchmaker.server.rmi` (`AuthServiceImpl`, `PlayerServiceImpl`, `AdminServiceImpl`).
- Every `*ServiceImpl` extends `java.rmi.server.UnicastRemoteObject` and implements its corresponding `common.rmi` interface; constructor signature is `(SessionManager sessionManager) throws RemoteException`.
- Session tokens are `UUID.randomUUID().toString()`, generated only inside `SessionManager`.
- Hardcoded test user constants (`id=1, username="test", password="test1234"`) live only in `AuthServiceImpl` — not duplicated elsewhere.
- Every stub method in `PlayerServiceImpl`/`AdminServiceImpl` throws `UnsupportedOperationException("<methodName> not implemented yet — see build-plan.md step <N>")`, with the exact step number from `docs/build-plan.md`'s roadmap.
- `ServerMain` binds services on port `1099` (RMI's conventional default). Integration tests use port `21099` instead, so they never collide with a real running `ServerMain`.

---

## File Structure

**Created:**
- `src/main/java/com/matchmaker/server/SessionManager.java` (Task 1)
- `src/test/java/com/matchmaker/server/SessionManagerTest.java` (Task 1)
- `src/main/java/com/matchmaker/server/rmi/AuthServiceImpl.java` (Task 2)
- `src/test/java/com/matchmaker/server/rmi/AuthServiceImplTest.java` (Task 2)
- `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java` (Task 3)
- `src/main/java/com/matchmaker/server/rmi/AdminServiceImpl.java` (Task 3)
- `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java` (Task 3)
- `src/test/java/com/matchmaker/server/rmi/AdminServiceImplTest.java` (Task 3)
- `src/main/java/com/matchmaker/server/ServerMain.java` (Task 4)
- `src/test/java/com/matchmaker/server/rmi/AuthServiceRmiIntegrationTest.java` (Task 5)

---

### Task 1: `SessionManager`

**Files:**
- Create: `src/main/java/com/matchmaker/server/SessionManager.java`
- Test: `src/test/java/com/matchmaker/server/SessionManagerTest.java`

**Interfaces:**
- Consumes: `com.matchmaker.common.exceptions.AuthenticationException` (already exists).
- Produces: `SessionManager` with `String createSession(int userId)` and `int resolve(String token) throws AuthenticationException` — Task 2 (`AuthServiceImpl`) takes a `SessionManager` in its constructor and calls both methods.

- [ ] **Step 1: Write the failing test**

```java
package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void createSession_thenResolve_returnsSameUserId() throws Exception {
        SessionManager sessionManager = new SessionManager();

        String token = sessionManager.createSession(42);

        assertEquals(42, sessionManager.resolve(token));
    }

    @Test
    void resolve_unknownToken_throwsAuthenticationException() {
        SessionManager sessionManager = new SessionManager();

        assertThrows(AuthenticationException.class, () -> sessionManager.resolve("nonexistent-token"));
    }

    @Test
    void createSession_generatesDifferentTokensAcrossCalls() {
        SessionManager sessionManager = new SessionManager();

        String token1 = sessionManager.createSession(1);
        String token2 = sessionManager.createSession(2);

        assertNotEquals(token1, token2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SessionManagerTest`
Expected: compilation failure — `SessionManager` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
package com.matchmaker.server;

import com.matchmaker.common.exceptions.AuthenticationException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final Map<String, Integer> userIdByToken = new ConcurrentHashMap<>();

    public String createSession(int userId) {
        String token = UUID.randomUUID().toString();
        userIdByToken.put(token, userId);
        return token;
    }

    public int resolve(String token) throws AuthenticationException {
        Integer userId = userIdByToken.get(token);
        if (userId == null) {
            throw new AuthenticationException("Invalid or expired session token");
        }
        return userId;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=SessionManagerTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/SessionManager.java src/test/java/com/matchmaker/server/SessionManagerTest.java
git commit -m "Add SessionManager for RMI session-token tracking"
```

---

### Task 2: `AuthServiceImpl`

**Files:**
- Create: `src/main/java/com/matchmaker/server/rmi/AuthServiceImpl.java`
- Test: `src/test/java/com/matchmaker/server/rmi/AuthServiceImplTest.java`

**Interfaces:**
- Consumes: `SessionManager` (Task 1), `com.matchmaker.common.rmi.AuthService`, `com.matchmaker.common.dto.UserDTO`, `com.matchmaker.common.dto.LoginResultDTO`, `com.matchmaker.common.exceptions.AuthenticationException`, `com.matchmaker.common.exceptions.UsernameTakenException` (all already exist).
- Produces: `AuthServiceImpl(SessionManager sessionManager)` constructor — Task 4 (`ServerMain`) and Task 5 (integration test) both construct this class directly.

Note on `extends UnicastRemoteObject`: this is what actually makes an object network-callable over RMI — its constructor "exports" the object (starts listening for incoming RMI calls) as a side effect of being constructed. That's why the constructor must declare `throws RemoteException`, and why we call `super()` explicitly even though it takes no arguments — the *no-arg* `UnicastRemoteObject()` constructor is itself declared to throw `RemoteException`, so Java requires our constructor to acknowledge that.

- [ ] **Step 1: Write the failing test**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceImplTest {

    @Test
    void login_withCorrectCredentials_returnsTokenAndUser() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        LoginResultDTO result = authService.login("test", "test1234");

        assertEquals("test", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withWrongPassword_throwsAuthenticationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(AuthenticationException.class, () -> authService.login("test", "wrongpassword"));
    }

    @Test
    void login_withUnknownUsername_throwsAuthenticationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(AuthenticationException.class, () -> authService.login("nobody", "whatever"));
    }

    @Test
    void register_withTakenUsername_throwsUsernameTakenException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(UsernameTakenException.class, () -> authService.register("test", "whatever"));
    }

    @Test
    void register_withNewUsername_throwsUnsupportedOperationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(UnsupportedOperationException.class, () -> authService.register("newuser", "whatever"));
    }

    @Test
    void keepAlive_withValidToken_doesNotThrow() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());
        LoginResultDTO result = authService.login("test", "test1234");

        assertDoesNotThrow(() -> authService.keepAlive(result.getSessionToken()));
    }

    @Test
    void keepAlive_withInvalidToken_throwsAuthenticationException() throws Exception {
        AuthServiceImpl authService = new AuthServiceImpl(new SessionManager());

        assertThrows(AuthenticationException.class, () -> authService.keepAlive("bogus-token"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=AuthServiceImplTest`
Expected: compilation failure — `AuthServiceImpl` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {

    private static final int TEST_USER_ID = 1;
    private static final String TEST_USERNAME = "test";
    private static final String TEST_PASSWORD = "test1234";

    private final SessionManager sessionManager;

    public AuthServiceImpl(SessionManager sessionManager) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
    }

    @Override
    public UserDTO register(String username, String password) throws RemoteException, UsernameTakenException {
        if (TEST_USERNAME.equals(username)) {
            throw new UsernameTakenException("Username '" + username + "' is already taken");
        }
        throw new UnsupportedOperationException(
            "register not implemented yet -- see build-plan.md step 4");
    }

    @Override
    public LoginResultDTO login(String username, String password) throws RemoteException, AuthenticationException {
        if (!TEST_USERNAME.equals(username) || !TEST_PASSWORD.equals(password)) {
            throw new AuthenticationException("Invalid username or password");
        }
        UserDTO user = new UserDTO(TEST_USER_ID, TEST_USERNAME, false, 0, 0, 0, 1200);
        String token = sessionManager.createSession(TEST_USER_ID);
        return new LoginResultDTO(user, token);
    }

    @Override
    public void keepAlive(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=AuthServiceImplTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/rmi/AuthServiceImpl.java src/test/java/com/matchmaker/server/rmi/AuthServiceImplTest.java
git commit -m "Add AuthServiceImpl with hardcoded test-user login/register/keepAlive"
```

---

### Task 3: `PlayerServiceImpl` / `AdminServiceImpl` stubs

**Files:**
- Create: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`
- Create: `src/main/java/com/matchmaker/server/rmi/AdminServiceImpl.java`
- Test: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`
- Test: `src/test/java/com/matchmaker/server/rmi/AdminServiceImplTest.java`

**Interfaces:**
- Consumes: `SessionManager` (Task 1), `com.matchmaker.common.rmi.PlayerService`, `com.matchmaker.common.rmi.AdminService`, and every DTO/exception type those interfaces reference (all already exist).
- Produces: `PlayerServiceImpl(SessionManager sessionManager)` and `AdminServiceImpl(SessionManager sessionManager)` constructors — Task 4 (`ServerMain`) constructs both.

- [ ] **Step 1: Write the failing tests**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerServiceImplTest {

    @Test
    void allMethods_throwUnsupportedOperationException() throws Exception {
        PlayerServiceImpl playerService = new PlayerServiceImpl(new SessionManager());

        assertThrows(UnsupportedOperationException.class, () -> playerService.listGameTypes("token"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.joinQueue("token", 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.cancelQueue("token"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.makeMove("token", 1, "{}"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.sendChatMessage("token", 1, "hi"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.resign("token", 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.rematch("token", 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.getHistory("token"));
    }
}
```

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceImplTest {

    @Test
    void allMethods_throwUnsupportedOperationException() throws Exception {
        AdminServiceImpl adminService = new AdminServiceImpl(new SessionManager());
        GameTypeDTO dummyGameType = new GameTypeDTO(0, "Checkers", "desc", 2, 2, 8, 8);

        assertThrows(UnsupportedOperationException.class, () -> adminService.listGameTypes("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.addGameType("token", dummyGameType));
        assertThrows(UnsupportedOperationException.class, () -> adminService.listUsers("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.listActiveSessions("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.forceEndSession("token", 1));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=PlayerServiceImplTest,AdminServiceImplTest`
Expected: compilation failure — neither class exists yet.

- [ ] **Step 3: Write minimal implementations**

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

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class PlayerServiceImpl extends UnicastRemoteObject implements PlayerService {

    private final SessionManager sessionManager;

    public PlayerServiceImpl(SessionManager sessionManager) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("listGameTypes not implemented yet -- see build-plan.md step 5");
    }

    @Override
    public void joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("joinQueue not implemented yet -- see build-plan.md step 5");
    }

    @Override
    public void cancelQueue(String sessionToken) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("cancelQueue not implemented yet -- see build-plan.md step 5");
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
        throw new UnsupportedOperationException("resign not implemented yet -- see build-plan.md step 5");
    }

    @Override
    public GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("rematch not implemented yet -- see build-plan.md step 5");
    }

    @Override
    public List<GameStateDTO> getHistory(String sessionToken) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("getHistory not implemented yet -- see build-plan.md step 4");
    }
}
```

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.server.SessionManager;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class AdminServiceImpl extends UnicastRemoteObject implements AdminService {

    private final SessionManager sessionManager;

    public AdminServiceImpl(SessionManager sessionManager) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("listGameTypes not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("addGameType not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public List<UserDTO> listUsers(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("listUsers not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public List<GameStateDTO> listActiveSessions(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("listActiveSessions not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public void forceEndSession(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("forceEndSession not implemented yet -- see build-plan.md step 9");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=PlayerServiceImplTest,AdminServiceImplTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java src/main/java/com/matchmaker/server/rmi/AdminServiceImpl.java src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java src/test/java/com/matchmaker/server/rmi/AdminServiceImplTest.java
git commit -m "Add PlayerServiceImpl/AdminServiceImpl stubs pointing at future roadmap steps"
```

---

### Task 4: `ServerMain`

**Files:**
- Create: `src/main/java/com/matchmaker/server/ServerMain.java`

**Interfaces:**
- Consumes: `SessionManager` (Task 1), `AuthServiceImpl` (Task 2), `PlayerServiceImpl`/`AdminServiceImpl` (Task 3).
- Produces: nothing later tasks depend on — this is the runnable entry point, not a library class. `Task 5`'s integration test does **not** use `ServerMain`; it starts its own registry on a separate port so it doesn't depend on a manually-running process.

There's no meaningful unit test for a `main` method that starts a live server process — verification here is a clean compile, confirmed by running it manually afterward.

- [ ] **Step 1: Write `ServerMain`**

```java
package com.matchmaker.server;

import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {

    public static final int PORT = 1099;

    public static void main(String[] args) throws Exception {
        SessionManager sessionManager = new SessionManager();

        AuthServiceImpl authService = new AuthServiceImpl(sessionManager);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);

        Registry registry = LocateRegistry.createRegistry(PORT);
        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        System.out.println("MatchMaker RMI registry started on port " + PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");
    }
}
```

- [ ] **Step 2: Verify the module compiles**

Run: `mvn compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/matchmaker/server/ServerMain.java
git commit -m "Add ServerMain to start the RMI registry and bind all three services"
```

---

### Task 5: `AuthServiceRmiIntegrationTest`

**Files:**
- Test: `src/test/java/com/matchmaker/server/rmi/AuthServiceRmiIntegrationTest.java`

**Interfaces:**
- Consumes: `AuthServiceImpl` (Task 2), `SessionManager` (Task 1), `com.matchmaker.common.rmi.AuthService` (already exists).
- Produces: nothing later tasks depend on — this is the proof-of-mechanics test for this milestone.

This test starts a **real** RMI registry (on port `21099`, distinct from `ServerMain`'s `1099`), binds a real `AuthServiceImpl`, looks it up via `LocateRegistry.getRegistry(...).lookup(...)` to get a genuine RMI stub, and calls methods through that stub — real serialization, real loopback network call, real `RemoteException` in every method signature. This is different from Task 2's test, which called `AuthServiceImpl`'s methods directly as a plain Java object with no RMI involved at all.

- [ ] **Step 1: Write the test**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceRmiIntegrationTest {

    private static final int TEST_PORT = 21099;

    private Registry registry;
    private AuthServiceImpl authServiceImpl;

    @BeforeEach
    void startRegistryAndBindService() throws Exception {
        registry = LocateRegistry.createRegistry(TEST_PORT);
        authServiceImpl = new AuthServiceImpl(new SessionManager());
        registry.rebind("AuthService", authServiceImpl);
    }

    @AfterEach
    void tearDownRegistry() throws Exception {
        registry.unbind("AuthService");
        UnicastRemoteObject.unexportObject(authServiceImpl, true);
        UnicastRemoteObject.unexportObject(registry, true);
    }

    @Test
    void login_throughRealRmiStub_returnsRealResult() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");

        LoginResultDTO result = stub.login("test", "test1234");

        assertEquals("test", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withBadCredentials_throwsAuthenticationExceptionAcrossRmi() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");

        assertThrows(AuthenticationException.class, () -> stub.login("test", "wrongpassword"));
    }

    @Test
    void register_takenUsername_throwsUsernameTakenExceptionAcrossRmi() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");

        assertThrows(UsernameTakenException.class, () -> stub.register("test", "whatever"));
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=AuthServiceRmiIntegrationTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0` — three genuinely different RMI round trips: a success, and two different domain exceptions crossing the network.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/matchmaker/server/rmi/AuthServiceRmiIntegrationTest.java
git commit -m "Add real RMI integration test proving AuthService works end-to-end"
```

---

### Task 6: Full module verification

**Files:** none (verification only).

**Interfaces:** N/A.

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: all tests across the whole module pass (13 from the contracts milestone + this milestone's new tests), `BUILD SUCCESS`.

- [ ] **Step 2: Run a full compile**

Run: `mvn compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Manually run the real server (sanity check, not automated)**

Run: `java -cp target/classes com.matchmaker.server.ServerMain` (uses the classes `mvn compile` already produced — no extra Maven plugin needed; running `ServerMain` directly from an IDE works too)
Expected: console prints `MatchMaker RMI registry started on port 1099` and the bound-services line, and the process keeps running (Ctrl+C to stop). This is a human sanity check that the entry point genuinely works, on top of the automated integration test already proving the RMI mechanics.

- [ ] **Step 4: Confirm working tree is clean**

Run: `git status`
Expected: nothing to commit — every task above already committed its own files.

---

## What comes after this plan

The database/DAO layer (`docs/build-plan.md` step 4) is next: replacing `AuthServiceImpl`'s hardcoded test user with a real `UserDao` backed by MySQL via JDBC, and implementing `getHistory()` for real. That's a separate, later plan.
