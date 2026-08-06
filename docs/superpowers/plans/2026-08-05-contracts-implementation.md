# MatchMaker Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the shared `common` contracts (exceptions, DTOs, RMI interfaces) exactly as designed in `docs/specs/2026-08-05-contracts-design.md`, so the RMI server skeleton (next milestone) has a stable, tested foundation to build against.

**Architecture:** A single Maven module, package `com.matchmaker.common`, split into `exceptions`, `dto`, `enums` (already done), and `rmi` sub-packages. No behavior lives here — pure data holders, a checked-exception hierarchy, and remote interface declarations. Nothing in this plan touches the server or client yet; that starts next milestone once these contracts compile and are tested.

**Tech Stack:** Java 21, Maven, JUnit 5 (Jupiter) — added in this plan, since no test dependency exists yet.

## Global Constraints

- Package root: `com.matchmaker.common` (`.dto`, `.enums`, `.exceptions`, `.rmi`).
- Every DTO: plain class (not record), `private final` fields, single constructor setting all fields, getters only (no setters), `implements Serializable` with `private static final long serialVersionUID = 1L`.
- Every exception: checked (`extends Exception` via `MatchmakerException`), single constructor `(String message)` calling `super(message)`.
- No Request/Response DTO wrappers — methods take plain typed parameters (per contracts spec Decision 4).
- Session-token parameter is always named `sessionToken` and always first, on every method except `register`/`login`.
- Source/target: Java 21 (already set in `pom.xml`).

---

## File Structure

**Already on disk (no changes in this plan):**
- `src/main/java/com/matchmaker/common/enums/GameStatus.java`
- `src/main/java/com/matchmaker/common/enums/QueueStatus.java`
- `src/main/java/com/matchmaker/common/dto/UserDTO.java`
- `src/main/java/com/matchmaker/common/dto/MoveDTO.java`
- `src/main/java/com/matchmaker/common/dto/GameStateDTO.java`

**Modified:**
- `pom.xml` — add JUnit 5 test dependency + Surefire plugin version (Task 1).

**Created:**
- `src/main/java/com/matchmaker/common/exceptions/MatchmakerException.java` (Task 1)
- `src/main/java/com/matchmaker/common/exceptions/AuthenticationException.java` (Task 1)
- `src/main/java/com/matchmaker/common/exceptions/UsernameTakenException.java` (Task 1)
- `src/main/java/com/matchmaker/common/exceptions/NotParticipantException.java` (Task 1)
- `src/main/java/com/matchmaker/common/exceptions/NotYourTurnException.java` (Task 1)
- `src/main/java/com/matchmaker/common/exceptions/IllegalMoveException.java` (Task 1)
- `src/main/java/com/matchmaker/common/exceptions/NotAdminException.java` (Task 1)
- `src/test/java/com/matchmaker/common/exceptions/MatchmakerExceptionHierarchyTest.java` (Task 1)
- `src/main/java/com/matchmaker/common/dto/LoginResultDTO.java` (Task 2)
- `src/main/java/com/matchmaker/common/dto/GameTypeDTO.java` (Task 2)
- `src/main/java/com/matchmaker/common/dto/ChatMessageDTO.java` (Task 2)
- `src/test/java/com/matchmaker/common/dto/NewDtoSerializationTest.java` (Task 2)
- `src/test/java/com/matchmaker/common/dto/ExistingDtoSerializationTest.java` (Task 3 — tests only, no production files)
- `src/main/java/com/matchmaker/common/rmi/AuthService.java` (Task 4)
- `src/main/java/com/matchmaker/common/rmi/PlayerService.java` (Task 4)
- `src/main/java/com/matchmaker/common/rmi/AdminService.java` (Task 4)

---

### Task 1: Exception hierarchy

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/matchmaker/common/exceptions/MatchmakerException.java`
- Create: `src/main/java/com/matchmaker/common/exceptions/AuthenticationException.java`
- Create: `src/main/java/com/matchmaker/common/exceptions/UsernameTakenException.java`
- Create: `src/main/java/com/matchmaker/common/exceptions/NotParticipantException.java`
- Create: `src/main/java/com/matchmaker/common/exceptions/NotYourTurnException.java`
- Create: `src/main/java/com/matchmaker/common/exceptions/IllegalMoveException.java`
- Create: `src/main/java/com/matchmaker/common/exceptions/NotAdminException.java`
- Test: `src/test/java/com/matchmaker/common/exceptions/MatchmakerExceptionHierarchyTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `MatchmakerException` and its 6 subclasses, all in `com.matchmaker.common.exceptions`, each with a public constructor `(String message)`. Tasks 2 and 4 don't use these directly, but Task 4's `PlayerService`/`AdminService`/`AuthService` interfaces declare `throws` clauses referencing these exact class names.

- [ ] **Step 1: Add JUnit 5 to `pom.xml`**

Add a `<dependencies>` block (new, top-level under `<project>`) and a `<plugins>` entry inside the existing `<build>` block for Surefire (so `mvn test` actually runs JUnit 5 tests):

```xml
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.2</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

```xml
<build>
    <finalName>matchmaker</finalName>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.2.5</version>
        </plugin>
    </plugins>
</build>
```

(The existing `<build><finalName>matchmaker</finalName></build>` block gets the `<plugins>` section added inside it — don't duplicate `<build>`.)

- [ ] **Step 2: Write the failing test**

```java
package com.matchmaker.common.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakerExceptionHierarchyTest {

    @Test
    void matchmakerException_carriesMessage_andIsAnException() {
        MatchmakerException ex = new MatchmakerException("base");
        assertEquals("base", ex.getMessage());
        assertTrue(ex instanceof Exception);
        // MatchmakerException extends Exception (checked), not RuntimeException --
        // enforced at compile time by the class declaration; an
        // `instanceof RuntimeException` check here wouldn't even compile, since
        // the two types are unrelated.
    }

    @Test
    void authenticationException_carriesMessage_andExtendsBase() {
        AuthenticationException ex = new AuthenticationException("bad credentials");
        assertEquals("bad credentials", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void usernameTakenException_carriesMessage_andExtendsBase() {
        UsernameTakenException ex = new UsernameTakenException("username exists");
        assertEquals("username exists", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void notParticipantException_carriesMessage_andExtendsBase() {
        NotParticipantException ex = new NotParticipantException("not a participant");
        assertEquals("not a participant", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void notYourTurnException_carriesMessage_andExtendsBase() {
        NotYourTurnException ex = new NotYourTurnException("not your turn");
        assertEquals("not your turn", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void illegalMoveException_carriesMessage_andExtendsBase() {
        IllegalMoveException ex = new IllegalMoveException("illegal move");
        assertEquals("illegal move", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void notAdminException_carriesMessage_andExtendsBase() {
        NotAdminException ex = new NotAdminException("not an admin");
        assertEquals("not an admin", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn test -Dtest=MatchmakerExceptionHierarchyTest`
Expected: **compilation failure** — `MatchmakerException` and the other classes don't exist yet. In a statically-typed language, "the test fails" often means "it doesn't even compile," which counts as red in TDD terms.

- [ ] **Step 4: Implement the exception classes**

```java
// MatchmakerException.java
package com.matchmaker.common.exceptions;

public class MatchmakerException extends Exception {
    public MatchmakerException(String message) {
        super(message);
    }
}
```

```java
// AuthenticationException.java
package com.matchmaker.common.exceptions;

public class AuthenticationException extends MatchmakerException {
    public AuthenticationException(String message) {
        super(message);
    }
}
```

```java
// UsernameTakenException.java
package com.matchmaker.common.exceptions;

public class UsernameTakenException extends MatchmakerException {
    public UsernameTakenException(String message) {
        super(message);
    }
}
```

```java
// NotParticipantException.java
package com.matchmaker.common.exceptions;

public class NotParticipantException extends MatchmakerException {
    public NotParticipantException(String message) {
        super(message);
    }
}
```

```java
// NotYourTurnException.java
package com.matchmaker.common.exceptions;

public class NotYourTurnException extends MatchmakerException {
    public NotYourTurnException(String message) {
        super(message);
    }
}
```

```java
// IllegalMoveException.java
package com.matchmaker.common.exceptions;

public class IllegalMoveException extends MatchmakerException {
    public IllegalMoveException(String message) {
        super(message);
    }
}
```

```java
// NotAdminException.java
package com.matchmaker.common.exceptions;

public class NotAdminException extends MatchmakerException {
    public NotAdminException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=MatchmakerExceptionHierarchyTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/matchmaker/common/exceptions src/test/java/com/matchmaker/common/exceptions
git commit -m "Add MatchmakerException hierarchy for contract-level errors"
```

---

### Task 2: New DTOs (`LoginResultDTO`, `GameTypeDTO`, `ChatMessageDTO`)

**Files:**
- Create: `src/main/java/com/matchmaker/common/dto/LoginResultDTO.java`
- Create: `src/main/java/com/matchmaker/common/dto/GameTypeDTO.java`
- Create: `src/main/java/com/matchmaker/common/dto/ChatMessageDTO.java`
- Test: `src/test/java/com/matchmaker/common/dto/NewDtoSerializationTest.java`

**Interfaces:**
- Consumes: `UserDTO` (already on disk, in `com.matchmaker.common.dto`) — `LoginResultDTO` wraps it.
- Produces: `LoginResultDTO`, `GameTypeDTO`, `ChatMessageDTO` — Task 4's `AuthService`/`PlayerService`/`AdminService` interfaces reference these exact class names as return/parameter types.

- [ ] **Step 1: Write the failing test**

This test does a real Java serialization round-trip (write to a byte stream, read it back as a new object) — this is exactly what RMI does internally to move an object across the network, so it's a genuine check that these DTOs are RMI-safe, not just a compile check.

```java
package com.matchmaker.common.dto;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NewDtoSerializationTest {

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(byteStream)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(byteStream.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @Test
    void loginResultDTO_survivesSerializationRoundTrip() throws Exception {
        UserDTO user = new UserDTO(1, "alice", false, 10, 2, 1, 1240);
        LoginResultDTO original = new LoginResultDTO(user, "token-abc-123");

        LoginResultDTO restored = roundTrip(original);

        assertEquals(original.getSessionToken(), restored.getSessionToken());
        assertEquals(original.getUser().getId(), restored.getUser().getId());
        assertEquals(original.getUser().getUsername(), restored.getUser().getUsername());
    }

    @Test
    void gameTypeDTO_survivesSerializationRoundTrip() throws Exception {
        GameTypeDTO original = new GameTypeDTO(1, "Checkers", "Classic checkers", 2, 2, 8, 8);

        GameTypeDTO restored = roundTrip(original);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getDescription(), restored.getDescription());
        assertEquals(original.getMinPlayers(), restored.getMinPlayers());
        assertEquals(original.getMaxPlayers(), restored.getMaxPlayers());
        assertEquals(original.getBoardRows(), restored.getBoardRows());
        assertEquals(original.getBoardCols(), restored.getBoardCols());
    }

    @Test
    void chatMessageDTO_survivesSerializationRoundTrip() throws Exception {
        LocalDateTime sentAt = LocalDateTime.of(2026, 8, 5, 14, 30);
        ChatMessageDTO original = new ChatMessageDTO(42, 7, "good luck", sentAt);

        ChatMessageDTO restored = roundTrip(original);

        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getUserId(), restored.getUserId());
        assertEquals(original.getContent(), restored.getContent());
        assertEquals(original.getSentAt(), restored.getSentAt());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=NewDtoSerializationTest`
Expected: compilation failure — `LoginResultDTO`, `GameTypeDTO`, `ChatMessageDTO` don't exist yet.

- [ ] **Step 3: Implement the three DTOs**

```java
// LoginResultDTO.java
package com.matchmaker.common.dto;

import java.io.Serializable;

public class LoginResultDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UserDTO user;
    private final String sessionToken;

    public LoginResultDTO(UserDTO user, String sessionToken) {
        this.user = user;
        this.sessionToken = sessionToken;
    }

    public UserDTO getUser() { return user; }
    public String getSessionToken() { return sessionToken; }
}
```

```java
// GameTypeDTO.java
package com.matchmaker.common.dto;

import java.io.Serializable;

public class GameTypeDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String name;
    private final String description;
    private final int minPlayers;
    private final int maxPlayers;
    private final int boardRows;
    private final int boardCols;

    public GameTypeDTO(int id, String name, String description,
                        int minPlayers, int maxPlayers, int boardRows, int boardCols) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
        this.boardRows = boardRows;
        this.boardCols = boardCols;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getBoardRows() { return boardRows; }
    public int getBoardCols() { return boardCols; }
}
```

```java
// ChatMessageDTO.java
package com.matchmaker.common.dto;

import java.io.Serializable;
import java.time.LocalDateTime;

public class ChatMessageDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int sessionId;
    private final int userId;
    private final String content;
    private final LocalDateTime sentAt;

    public ChatMessageDTO(int sessionId, int userId, String content, LocalDateTime sentAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.content = content;
        this.sentAt = sentAt;
    }

    public int getSessionId() { return sessionId; }
    public int getUserId() { return userId; }
    public String getContent() { return content; }
    public LocalDateTime getSentAt() { return sentAt; }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=NewDtoSerializationTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/common/dto/LoginResultDTO.java src/main/java/com/matchmaker/common/dto/GameTypeDTO.java src/main/java/com/matchmaker/common/dto/ChatMessageDTO.java src/test/java/com/matchmaker/common/dto/NewDtoSerializationTest.java
git commit -m "Add LoginResultDTO, GameTypeDTO, ChatMessageDTO with serialization tests"
```

---

### Task 3: Serialization safety-net tests for the existing DTOs

**Files:**
- Test: `src/test/java/com/matchmaker/common/dto/ExistingDtoSerializationTest.java`

No production code changes — `UserDTO`, `MoveDTO`, `GameStateDTO` already exist and were already confirmed. This task only adds the same RMI-safety check Task 2 added for the new DTOs, so all six DTOs have the same guarantee before Task 4 starts referencing them from RMI interfaces.

**Interfaces:**
- Consumes: `UserDTO`, `MoveDTO`, `GameStateDTO`, `GameStatus` (all already on disk).
- Produces: nothing new for later tasks — this is a regression-test safety net only.

- [ ] **Step 1: Write the test**

Unlike Tasks 1–2, this test is expected to **pass immediately** — the classes already exist and were already reviewed. This is a "characterization test": locking in behavior that already exists, in writing, before other code starts depending on it.

```java
package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExistingDtoSerializationTest {

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(byteStream)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(byteStream.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @Test
    void userDTO_survivesSerializationRoundTrip() throws Exception {
        UserDTO original = new UserDTO(3, "checkers_king", false, 160, 55, 0, 1790);

        UserDTO restored = roundTrip(original);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getUsername(), restored.getUsername());
        assertEquals(original.isAdmin(), restored.isAdmin());
        assertEquals(original.getWins(), restored.getWins());
        assertEquals(original.getLosses(), restored.getLosses());
        assertEquals(original.getDraws(), restored.getDraws());
        assertEquals(original.getRating(), restored.getRating());
    }

    @Test
    void moveDTO_survivesSerializationRoundTrip() throws Exception {
        MoveDTO original = new MoveDTO(42, 3, 7, "{\"from\":\"b6\",\"to\":\"a5\"}");

        MoveDTO restored = roundTrip(original);

        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getUserId(), restored.getUserId());
        assertEquals(original.getMoveNumber(), restored.getMoveNumber());
        assertEquals(original.getPayload(), restored.getPayload());
    }

    @Test
    void gameStateDTO_survivesSerializationRoundTrip_withNullWinner() throws Exception {
        GameStateDTO original = new GameStateDTO(
            42, 1, 3, 4, GameStatus.ACTIVE, 3, null, "{\"board\":\"...\"}"
        );

        GameStateDTO restored = roundTrip(original);

        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getGameTypeId(), restored.getGameTypeId());
        assertEquals(original.getPlayer1Id(), restored.getPlayer1Id());
        assertEquals(original.getPlayer2Id(), restored.getPlayer2Id());
        assertEquals(original.getStatus(), restored.getStatus());
        assertEquals(original.getCurrentTurnUserId(), restored.getCurrentTurnUserId());
        assertEquals(original.getWinnerId(), restored.getWinnerId());
        assertEquals(original.getBoardState(), restored.getBoardState());
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `mvn test -Dtest=ExistingDtoSerializationTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0` (passes immediately — these classes already existed and were already correct).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/matchmaker/common/dto/ExistingDtoSerializationTest.java
git commit -m "Add serialization regression tests for existing DTOs"
```

---

### Task 4: RMI interfaces (`AuthService`, `PlayerService`, `AdminService`)

**Files:**
- Create: `src/main/java/com/matchmaker/common/rmi/AuthService.java`
- Create: `src/main/java/com/matchmaker/common/rmi/PlayerService.java`
- Create: `src/main/java/com/matchmaker/common/rmi/AdminService.java`

**Interfaces:**
- Consumes: `UserDTO`, `LoginResultDTO`, `GameTypeDTO`, `GameStateDTO` (dto package), and all 6 exception subclasses (exceptions package) — every `throws` clause below references classes built in Tasks 1–2.
- Produces: `AuthService`, `PlayerService`, `AdminService` — the next milestone (RMI server skeleton, separate plan) implements these and binds them in an RMI registry.

There is no implementation yet, so there is nothing to unit test — an interface has no behavior to assert against until something implements it (that's next milestone). The verification for this task is that the whole module still compiles with these interfaces referencing the Task 1–2 types correctly.

- [ ] **Step 1: Create `AuthService`**

```java
package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface AuthService extends Remote {
    UserDTO register(String username, String password)
        throws RemoteException, UsernameTakenException;

    LoginResultDTO login(String username, String password)
        throws RemoteException, AuthenticationException;

    void keepAlive(String sessionToken)
        throws RemoteException, AuthenticationException;
}
```

- [ ] **Step 2: Create `PlayerService`**

```java
package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface PlayerService extends Remote {
    List<GameTypeDTO> listGameTypes(String sessionToken)
        throws RemoteException, AuthenticationException;

    void joinQueue(String sessionToken, int gameTypeId)
        throws RemoteException, AuthenticationException;

    void cancelQueue(String sessionToken)
        throws RemoteException, AuthenticationException;

    GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
        throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

    void sendChatMessage(String sessionToken, int gameSessionId, String content)
        throws RemoteException, AuthenticationException, NotParticipantException;

    void resign(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    GameStateDTO rematch(String sessionToken, int finishedSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    List<GameStateDTO> getHistory(String sessionToken)
        throws RemoteException, AuthenticationException;
}
```

- [ ] **Step 3: Create `AdminService`**

```java
package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface AdminService extends Remote {
    List<GameTypeDTO> listGameTypes(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
        throws RemoteException, AuthenticationException, NotAdminException;

    List<UserDTO> listUsers(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    List<GameStateDTO> listActiveSessions(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    void forceEndSession(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotAdminException;
}
```

- [ ] **Step 4: Verify the module compiles**

Run: `mvn compile`
Expected: `BUILD SUCCESS` — confirms all three interfaces resolve correctly against every DTO and exception type they reference.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/common/rmi
git commit -m "Add AuthService, PlayerService, AdminService RMI interfaces"
```

---

### Task 5: Full module verification

**Files:** none (verification only).

**Interfaces:** N/A.

- [ ] **Step 1: Run the full test suite**

Run: `mvn test`
Expected: all 13 tests across `MatchmakerExceptionHierarchyTest` (7), `NewDtoSerializationTest` (3), `ExistingDtoSerializationTest` (3) pass, `BUILD SUCCESS`.

- [ ] **Step 2: Run a full compile of the whole module**

Run: `mvn compile`
Expected: `BUILD SUCCESS` — enums, exceptions, DTOs, and RMI interfaces all compile together as one coherent module.

- [ ] **Step 3: Confirm working tree is clean**

Run: `git status`
Expected: nothing to commit — every task above already committed its own files.

---

## What comes after this plan

The RMI server skeleton (`ServerMain`, per-interface implementations, a throwaway test client to prove a real network round-trip) is a **separate, later plan** — per `docs/build-plan.md`, that's the next milestone once these contracts are implemented and merged, not part of this one.
