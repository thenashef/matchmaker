# Contracts Design — RMI Interfaces, DTOs, Exceptions

Date: 2026-08-05
Status: Design agreed in discussion; awaiting user's review of this written doc before implementation begins

## Context

This is the first real design piece of the MatchMaker course project (see `../../MatchMaker_Spec_EN.md` for the full functional spec). Before any server or client code is written, the client/server "contract" needs to be nailed down: the RMI interfaces the client calls, the plain data objects that cross the network, and how failures are communicated. Everything else in the build roadmap (JDBC, JMS, the game engine, both JavaFX clients) gets built against this contract, so getting it right first avoids reshaping deeper code later.

This document was produced through a collaborative brainstorming session — every structural decision below was discussed, explained, and explicitly agreed on before being written here.

## Decision 1: Three RMI interfaces, not one

`AuthService`, `PlayerService`, and `AdminService` — split by *who calls what*, not one giant interface.

**Why:** an admin is just a `User` row with `IsAdmin = true` (per the DB spec), so `register`/`login`/`keepAlive` are identical for both kinds of clients — putting them in `PlayerService` or `AdminService` (or both) would mean duplicating the same method in two places, or forcing the admin client to depend on player-only methods it will never call. Splitting auth into its own shared interface avoids both problems: one definition of `login`, reused by both clients; the player client only ever holds `AuthService` + `PlayerService` stubs, the admin client only `AuthService` + `AdminService`. This is Interface Segregation — a client shouldn't be forced to depend on methods it can't legally use — and it's also a compile-time boundary: the player client's code doesn't have `addGameType()` available to call at all, not just a runtime permission check.

Live game monitoring (the admin's "Live Game Monitor" screen) is deliberately **not** in `AdminService` — per the functional spec, that's read-only JMS subscription, not an RMI call, and belongs to a later JMS design pass (roadmap step 6).

**Self-review fix:** listing available game types (needed by the Lobby screen to show game choices, and by the Admin "Games" screen) is a trivial, side-effect-free read with no branching business logic — unlike `login`, there's no real risk of two copies drifting apart. So `listGameTypes()` appears in **both** `PlayerService` and `AdminService` rather than forcing it into a fourth shared interface for one method.

Lobby leaderboard/online-player-count are also **deliberately deferred**, not forgotten — they're not part of the core game loop, and adding methods to an interface later is a non-breaking change, so there's no cost to leaving them out of this pass.

## Decision 2: Session-token authentication

`login()` returns a `LoginResultDTO` bundling the user's profile and a session token (a server-generated random string). Every other method (on any of the three interfaces) takes that token as its first parameter. Server-side, an in-memory `Map<String sessionToken, Integer userId>` resolves who's actually calling.

**Why:** the alternative — passing the caller's raw `userId` as a parameter — would let any client simply claim to be a different user, since nothing stops them from passing someone else's id. A server-issued token that the client can't forge or guess closes that hole, while still keeping every method call stateless and simple (no custom per-session remote objects needed).

## Decision 3: Checked exceptions for domain errors

Every domain-level failure is a checked exception, not a result/status object. All extend one base:

- `MatchmakerException` (base, `extends Exception`)
  - `AuthenticationException` — bad credentials on `login`, or an invalid/expired session token on any other call
  - `UsernameTakenException` — `register()` with a username already in the `User` table
  - `NotParticipantException` — caller's userId isn't `Player1ID`/`Player2ID` on the session being acted on
  - `NotYourTurnException` — caller is a participant, but isn't `CurrentTurnUserID`
  - `IllegalMoveException` — the move itself breaks the game engine's rules
  - `NotAdminException` — caller is authenticated but `IsAdmin` is false, calling an `AdminService` method

**Why:** RMI already forces every remote method to declare `throws RemoteException` for transport failures. Domain-specific checked exceptions fit into that same try/catch a caller can't avoid, and the compiler forces every caller to handle each one — unlike a status field on a result object, which is easy to forget to check. This also matches how RMI actually works: exceptions are `Serializable` and genuinely cross the wire, so a `catch (IllegalMoveException e)` on the client reads exactly like ordinary local error handling, not like parsing a response code.

`NotParticipantException` and `NotYourTurnException` are kept separate (not merged into one "not allowed" exception) because the functional spec itself describes two distinct checks — participation, then turn order — and separate exceptions let the client show a precise message for each.

## Decision 4: No blanket Request/Response DTO wrappers

Methods take plain, typed, named parameters directly (e.g. `login(String username, String password)`), not `LoginRequest`/`LoginResponse`-style wrapper objects for every call.

**Why:** the Request/Response wrapper pattern earns its keep in APIs that must evolve independently across many client versions over time (REST, gRPC) — the wrapper lets you add optional fields without ever breaking an existing method signature. That constraint doesn't apply here: client and server are compiled together from one codebase for one submission. Java method signatures are already typed and self-documenting, and every method in this contract has a short (1–3 parameter) list, so a wrapper class would add a file and ceremony without adding information.

The one deliberate exception is `LoginResultDTO` — introduced not as a blanket pattern, but because `login()` genuinely needs to return two distinct things (the user, and the session token). If any method's parameter list grows unwieldy later, introducing a dedicated request object for *that* method specifically is the right response — not a rule applied uniformly upfront.

## Decision 5: DTO style — plain classes, not records

DTOs are plain classes: `private final` fields, a constructor that sets all of them, and getters — no setters (immutable once built).

**Why:** matches the style already used for `UserDTO`/`MoveDTO`/`GameStateDTO` before this design pass; consistency across all DTOs was preferred over mixing styles. Java `record` was discussed as a lower-ceremony alternative built for exactly this "pure data holder" use case, and explicitly declined in favor of the more explicit, uniform class style.

---

## The Interfaces

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

## The DTOs

### Existing (already on disk, confirmed as final)

```java
package com.matchmaker.common.dto;

import java.io.Serializable;

public class UserDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String username;
    private final boolean admin;
    private final int wins;
    private final int losses;
    private final int draws;
    private final int rating;

    public UserDTO(int id, String username, boolean admin, int wins, int losses, int draws, int rating) {
        this.id = id;
        this.username = username;
        this.admin = admin;
        this.wins = wins;
        this.losses = losses;
        this.draws = draws;
        this.rating = rating;
    }

    public int getId() { return id; }
    public String getUsername() { return username; }
    public boolean isAdmin() { return admin; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getDraws() { return draws; }
    public int getRating() { return rating; }
}
```

No password field — this DTO crosses the network to the client, and the password (even hashed) never needs to leave the server.

```java
package com.matchmaker.common.dto;

import java.io.Serializable;

public class MoveDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int sessionId;
    private final int userId;
    private final int moveNumber;
    private final String payload;

    public MoveDTO(int sessionId, int userId, int moveNumber, String payload) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.moveNumber = moveNumber;
        this.payload = payload;
    }

    public int getSessionId() { return sessionId; }
    public int getUserId() { return userId; }
    public int getMoveNumber() { return moveNumber; }
    public String getPayload() { return payload; }
}
```

`payload` is a JSON string describing the move itself; kept unstructured because move shape varies per game type (a checkers jump sequence looks nothing like a chess move).

```java
package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameStatus;
import java.io.Serializable;

public class GameStateDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int sessionId;
    private final int gameTypeId;
    private final int player1Id;
    private final int player2Id;
    private final GameStatus status;
    private final Integer currentTurnUserId;
    private final Integer winnerId;
    private final String boardState;

    public GameStateDTO(int sessionId, int gameTypeId, int player1Id, int player2Id,
                         GameStatus status, Integer currentTurnUserId, Integer winnerId, String boardState) {
        this.sessionId = sessionId;
        this.gameTypeId = gameTypeId;
        this.player1Id = player1Id;
        this.player2Id = player2Id;
        this.status = status;
        this.currentTurnUserId = currentTurnUserId;
        this.winnerId = winnerId;
        this.boardState = boardState;
    }

    public int getSessionId() { return sessionId; }
    public int getGameTypeId() { return gameTypeId; }
    public int getPlayer1Id() { return player1Id; }
    public int getPlayer2Id() { return player2Id; }
    public GameStatus getStatus() { return status; }
    public Integer getCurrentTurnUserId() { return currentTurnUserId; }
    public Integer getWinnerId() { return winnerId; }
    public String getBoardState() { return boardState; }
}
```

`currentTurnUserId`/`winnerId` are boxed `Integer`, not primitive `int`, specifically because they're nullable in the DB (no winner until the game ends) — a primitive can't represent "no value."

### New

```java
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

Mirrors the `GameType` table; used both when the admin creates a new game type and when any client lists available games.

```java
package com.matchmaker.common.dto;

import java.time.LocalDateTime;
import java.io.Serializable;

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

Mirrors the `ChatMessage` table; the server constructs this (assigning `sentAt`) after receiving `sendChatMessage()`, then broadcasts it over the session's JMS topic (roadmap step 6) to the other player.

## The Exceptions (`common.exceptions`)

```java
package com.matchmaker.common.exceptions;

public class MatchmakerException extends Exception {
    public MatchmakerException(String message) {
        super(message);
    }
}
```

```java
package com.matchmaker.common.exceptions;

public class AuthenticationException extends MatchmakerException {
    public AuthenticationException(String message) {
        super(message);
    }
}
```

```java
package com.matchmaker.common.exceptions;

public class UsernameTakenException extends MatchmakerException {
    public UsernameTakenException(String message) {
        super(message);
    }
}
```

```java
package com.matchmaker.common.exceptions;

public class NotParticipantException extends MatchmakerException {
    public NotParticipantException(String message) {
        super(message);
    }
}
```

```java
package com.matchmaker.common.exceptions;

public class NotYourTurnException extends MatchmakerException {
    public NotYourTurnException(String message) {
        super(message);
    }
}
```

```java
package com.matchmaker.common.exceptions;

public class IllegalMoveException extends MatchmakerException {
    public IllegalMoveException(String message) {
        super(message);
    }
}
```

```java
package com.matchmaker.common.exceptions;

public class NotAdminException extends MatchmakerException {
    public NotAdminException(String message) {
        super(message);
    }
}
```

## Explicitly Out of Scope for This Spec

- **Lobby leaderboard / online-player-count** — not part of the core game loop; can be added to `PlayerService` later without breaking anything already built against it.
- **Live Game Monitor (admin)** — read-only JMS topic subscription per the functional spec, not an RMI call; designed when JMS itself is designed (roadmap step 6).
- **JMS message shapes** (match-found notification, opponent-moved push, etc.) — a separate design pass once RMI is working end-to-end (roadmap step 6).
- **`GameEngine` / `CheckersEngine`** (rule validation, legal-move checking) — roadmap step 7; `IllegalMoveException` is defined here as a contract-level concept, but what makes a move "illegal" is the engine's responsibility, not this spec's.

## Next Step

Implementation, one file at a time, per the working style already agreed: create the actual `.java` files for the enums/DTOs/exceptions/interfaces listed above, in `src/main/java/com/matchmaker/...`, each shown and agreed before being written — then the RMI server skeleton (`ServerMain`, per-interface implementations, a throwaway test client) to prove the whole thing compiles and connects.
