# Design: JavaFX Player Client (Roadmap Step 8)

## Context

Milestones 1–6.5 are merged to `main`: contracts, RMI server skeleton, JDBC/DAO layer, matchmaking queue, JMS setup (`MATCH_FOUND` on a per-player queue), the checkers game engine wired into `makeMove()`, and (Milestone 6.5) a per-session JMS Topic publishing `MOVE_MADE` to both players on every move. No client exists yet — `PlayerServiceImpl`'s real methods (`listGameTypes`, `getHistory`, `joinQueue`, `cancelQueue`, `makeMove`) have only ever been called from tests.

Spec §2 ("Technologies") mandates the mechanism: *"RMI – for synchronous command calls from the client to the server... JMS – for asynchronous messages from the server to the client... A message queue for each player and a topic for each game session provide this naturally."* Spec §4 ("System Layers") mandates the client's internal structure: *"Presentation layer – displays the player's screens using JavaFX... Logic layer – receives data from the server via the communication layer, processes it and passes it to the presentation layer, and vice versa... Server communication layer – sends commands to the server via RMI, and listens for asynchronous messages from the server as a JMS consumer."* This isn't a style choice — it's what the client's package structure follows below.

**A blocker found while scoping this:** `JmsConnectionFactory.create()` (`server/jms/`) builds a brand-new, uniquely-named, in-process `vm://matchmaker-<uuid>` broker on *every call* — fine when the only JMS participant was `ServerMain`'s own publisher connection (plus fully isolated per-test brokers), but a `vm://` broker is invisible outside the JVM that created it. A separate client process has no way to reach it. This has to change before a client can subscribe to anything.

Roadmap line for this step: *"Player client (JavaFX) — Login/Register → Lobby → Matchmaking wait → Game board, wired to RMI (commands) + JMS (push updates)."*

## Decisions

Worked through with the user, compressed but real:

1. **Presentation / Logic / Communication packages, per spec §4.** `client.presentation` (FXML + Controllers), `client.logic` (plain Java, no JavaFX imports), `client.communication` (RMI stubs + JMS consumer, no JavaFX imports). Presentation only calls Logic; Logic only calls Communication; nothing skips a layer.
2. **FXML + Controller classes for Presentation**, not hand-built JavaFX Java code. User's explicit choice — more traditional JavaFX MVC, and each screen ships as a `.fxml` layout file paired with a `*Controller.java`.
3. **`ServerConnection` is an interface, with a real RMI+JMS implementation and a test fake** — the same interface + real-impl + test-fake shape used everywhere else in this codebase (`GameEventPublisher`/`ActiveMqGameEventPublisher`/`InMemoryGameEventPublisher`, `MatchmakingQueue`/`JdbcMatchmakingQueue`/`InMemoryMatchmakingQueue`). This is the one class that's genuinely awkward to unit-test directly (real network I/O); putting it behind an interface makes `GameClientService` — the actual decision-making Logic-layer code — fully unit-testable without RMI or JMS, same as `PlayerServiceImpl` is tested today.
4. **One `GameClientService` class for the whole Logic layer, not one per screen.** Four screens' worth of orchestration (login/register, list game types, join/cancel queue, make a move) isn't enough distinct state to justify splitting yet — a single class with clearly-named methods mirrors how `PlayerServiceImpl` itself is one class covering every game-loop RMI method. Revisit if it grows unwieldy, same "flag if wrong" spirit as the assumptions in `build-plan.md`.
5. **The JMS broker becomes one long-lived, network-reachable broker instead of a disposable per-call one.** `ServerMain` starts a single embedded ActiveMQ broker with a `tcp://` transport connector (e.g. `tcp://0.0.0.0:61616`) at startup and keeps it for the server's lifetime, instead of calling `JmsConnectionFactory.create()`'s disposable `vm://matchmaker-<uuid>`. The client connects as a pure consumer via `tcp://localhost:61616` — no broker of its own. Still fully embedded (no Docker, no separate ActiveMQ install) — just now listening on a real socket instead of only being reachable in-process. Existing tests are unaffected: `JmsConnectionFactoryTest`/`GameEventPublisherJmsIntegrationTest` keep using isolated per-test `vm://` brokers, since they never needed cross-process reach.
6. **Threading boundary sits entirely in `GameClientService`.** `ServerConnection` has zero JavaFX awareness — its methods either block on RMI or fire a listener callback on whatever thread the JMS client library uses. `GameClientService` runs every `ServerConnection` call on a background thread and is the only place that calls `Platform.runLater(...)` before touching anything the Presentation layer observes. Presentation controllers never reason about threads at all.
7. **Move input: click-to-build-a-path, not click-origin-then-destination.** The user clicks the origin square, then clicks each square along the intended path in order, with visible "Submit Move" / "Clear Selection" buttons. This produces exactly the `{"path":["b3","a4",...]}` shape `Move.fromJson()` (`server/game/checkers/`) already parses — no server-side change needed — and it's the only input shape that naturally covers both a single step and a mandatory multi-jump chain without two different UI modes.
8. **Scope: Login/Register → Lobby → Matchmaking Wait → Game Board only.** No chat, resign, rematch, `keepAlive`, or admin client — matching the roadmap line for this step and the fact that `sendChatMessage`/`resign`/`rematch` are still `UnsupportedOperationException` stubs in `PlayerServiceImpl` today.
9. **Run via `javafx-maven-plugin` (`mvn javafx:run`), not a hardcoded OS classifier in `pom.xml`.** JavaFX's platform-specific jars (`javafx-controls`, `javafx-fxml`) need an OS/arch classifier (this machine is `mac-aarch64`) to resolve; hardcoding one in the dependency declaration would break on a different grading machine. The plugin auto-detects the current platform at build time instead — no manual classifier anywhere.

## Architecture

```
com.matchmaker.client/
├── ClientMain.java                    JavaFX Application entry point; owns the primary Stage,
│                                       constructs one RmiJmsServerConnection + one GameClientService,
│                                       shows LoginView first, closes the connection in stop()
├── communication/
│   ├── ServerConnection.java          interface
│   ├── RmiJmsServerConnection.java    real impl: RMI stub lookups + JMS Connection/Session
│   ├── ServerEventListener.java       functional interface: void onEvent(GameEventDTO event)
│   └── Subscription.java              AutoCloseable handle returned by the two subscribe methods
├── logic/
│   └── GameClientService.java         the whole Logic layer for this milestone
└── presentation/
    ├── LoginView.fxml / LoginController.java
    ├── LobbyView.fxml / LobbyController.java
    ├── MatchmakingWaitView.fxml / MatchmakingWaitController.java
    ├── GameBoardView.fxml / GameBoardController.java
    └── SceneNavigator.java            swaps the primary Stage's root between loaded FXML screens
```

`src/test/java/com/matchmaker/client/communication/InMemoryServerConnection.java` — test fake, same role as `InMemoryGameEventPublisher`: records calls, lets a test manually fire a queued/topic event to simulate a server push.

**`ServerConnection` (interface):**
```java
UserDTO register(String username, String password) throws UsernameTakenException;
LoginResultDTO login(String username, String password) throws AuthenticationException;
List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException;
GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws AuthenticationException;
void cancelQueue(String sessionToken) throws AuthenticationException;
GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
        throws AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener);
Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener);
```
`RemoteException` is unchecked-wrapped at this boundary (a new `ServerCommunicationException extends RuntimeException`) — the Logic layer shouldn't have to declare a checked exception for "the network broke," any more than `server/dao/DaoException` makes callers declare `SQLException`.

**`GameClientService` (Logic layer) — representative methods**, each running its `ServerConnection` call on a background thread and delivering its result via a callback on the JavaFX Application Thread:
```java
void login(String username, String password, Consumer<LoginResultDTO> onSuccess, Consumer<Throwable> onError);
void register(String username, String password, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError);
void listGameTypes(Consumer<List<GameTypeDTO>> onSuccess, Consumer<Throwable> onError);
void joinQueue(int gameTypeId, Consumer<GameStateDTO> onMatched, Runnable onWaiting, Consumer<Throwable> onError);
void cancelQueue(Runnable onCancelled, Consumer<Throwable> onError);
void makeMove(int gameSessionId, String movePayload, Consumer<GameStateDTO> onSuccess, Consumer<Throwable> onError);
void enterGame(int gameSessionId, GameStateDTO initialState, Consumer<GameStateDTO> onUpdate);
void leaveGame();
```
`GameClientService` holds the mutable client-side session state (`UserDTO currentUser`, `String sessionToken`, and the currently-open `Subscription`, if any) — nothing in `presentation/` holds server-derived state directly beyond what it's currently rendering.

## Data flow

### Login → Lobby
1. `LoginController` collects username/password, calls `gameClientService.login(...)`.
2. On success, `GameClientService` stores `currentUser`/`sessionToken`; the success callback (already hopped to the JavaFX thread) tells `SceneNavigator` to show `LobbyView`.
3. `LobbyController.initialize()` calls `gameClientService.listGameTypes(...)` and populates a list.

### Lobby → Matchmaking Wait → Game Board (the JMS-timing-sensitive part)

The queue and the topic have **different delivery guarantees**, and the design has to respect both:

- **`player.{userId}.events` is a JMS *Queue* — point-to-point, and a message waits on the broker until *some* consumer receives it**, even if no consumer was attached at send time. So `joinQueue()`'s call/return order relative to subscribing is safe either way: `GameClientService.joinQueue()` calls the RMI method first; if it returns `null` (still waiting), *then* it subscribes to the player queue. Even if another player raced in and matched during that small gap, the `MATCH_FOUND` message is sitting on the broker waiting — it isn't lost.
- **`session.{sessionId}.events` is a JMS *Topic* — pub/sub, and a non-durable subscriber only receives messages published *while it's subscribed*.** A message published before the client subscribes is gone forever. So the moment `GameClientService` has a session id — whether from `joinQueue()`'s immediate non-null return, or from a pushed `MATCH_FOUND` event — it must subscribe to that session's topic **before** doing anything else (before rendering the board, before either player can possibly move), via `enterGame(sessionId, initialState, onUpdate)`. Missing this window would mean silently missing the opponent's first move if they move before this client finishes setting up.

Concretely:
1. User clicks "Join Queue" for a game type → `LobbyController` calls `gameClientService.joinQueue(gameTypeId, onMatched, onWaiting, onError)`.
2. If the RMI call returns non-null (opponent was already waiting): `GameClientService` immediately calls `enterGame(sessionId, result, ...)` (subscribing to the session topic right away) and invokes `onMatched` — `LobbyController` navigates straight to `GameBoardView`, skipping the wait screen entirely.
3. If it returns `null`: `GameClientService` subscribes to `player.{userId}.events`, then invokes `onWaiting` — `LobbyController` navigates to `MatchmakingWaitView`.
4. On `MatchmakingWaitView`, when the queued `MATCH_FOUND` event eventually arrives (via the listener passed at subscribe time, firing on a JMS thread): `GameClientService` closes the player-queue subscription (no longer needed once matched), calls `enterGame(...)` for the new session, and — via `Platform.runLater` — the `MatchmakingWaitController`'s update callback navigates to `GameBoardView`.
5. A "Cancel" button on `MatchmakingWaitView` calls `gameClientService.cancelQueue(...)`, which also closes the player-queue subscription, then navigates back to `LobbyView`.

### Game Board
1. `GameBoardController` renders the board it was handed (`initialState`, from whichever path led here) as an 8×8 grid — only dark squares (`(row+col)%2==1`, matching `CheckersBoard.isDarkSquare`) are playable/clickable, matching `Square`'s `a1`-style algebraic coordinates. Fixed orientation (rank 1 at the bottom) regardless of which player is viewing — no per-player board flip (see Out of scope).
2. Clicking a piece the current user owns on their own turn selects it as the path origin; each subsequent click on a legal-looking square appends to the path. "Submit Move" calls `gameClientService.makeMove(gameSessionId, pathJson, onSuccess, onError)`; "Clear Selection" resets the in-progress path without calling the server. Illegality is still only ever decided server-side (`IllegalMoveException` on submit re-enables selection and shows the error) — the client does no legality pre-checking, consistent with spec §3's authoritative-server note.
3. On a successful `makeMove()`, the RMI response's `GameStateDTO` is applied to the board immediately (snappy for the mover — no need to wait on the async echo).
4. Independently, every `MOVE_MADE` event arriving on the session topic (via the `onUpdate` callback registered in `enterGame()`) re-renders the board from that event's `GameStateDTO`. Since Milestone 6.5 publishes to *both* players unconditionally, the mover receives their own move echoed back too — applying the same `GameStateDTO` twice is idempotent, so no special-casing is needed to tell "my own echo" apart from "the opponent's push."
5. When a received `GameStateDTO.status` is `FINISHED`, the board becomes read-only, a win/loss/draw banner is shown (comparing `winnerId` against `currentUser.getId()`), and a "Back to Lobby" button appears. Leaving this screen (win, or navigating away) calls `gameClientService.leaveGame()`, which closes the session-topic subscription.

## Wiring

- **`ServerMain`**: replace the `JmsConnectionFactory.create()` + one-off `Connection` with a long-lived embedded broker: start an ActiveMQ `BrokerService` with a `tcp://` transport connector once, then connect `ActiveMqGameEventPublisher`'s own `Session` to it the same way as today. The broker instance is stored so it can be stopped on shutdown alongside the RMI registry.
- **`pom.xml`**: add `org.openjfx:javafx-controls` and `org.openjfx:javafx-fxml` (no explicit classifier), and the `org.openjfx:javafx-maven-plugin` build plugin configured with `<mainClass>com.matchmaker.client.ClientMain</mainClass>`. Run with `mvn javafx:run`. `ServerMain` keeps running via the existing `mvn exec:java`.
- **`ClientMain`** constructs one `RmiJmsServerConnection` (looks up `AuthService`/`PlayerService` via `LocateRegistry.getRegistry("localhost", 1099)`, opens a JMS connection to `tcp://localhost:61616`) and one `GameClientService` wrapping it, then shows `LoginView`.

## Testing

Extends the existing test-tier pattern:
- **`GameClientServiceTest`** (Docker- and JavaFX-free, plain JUnit) — the bulk of the real logic coverage, against `InMemoryServerConnection`: login/register success and failure paths, the immediate-match-vs-queued-and-wait branch of `joinQueue`, the queue-subscription lifecycle (subscribed only when waiting, closed on match or cancel), the topic-subscription lifecycle (`enterGame`/`leaveGame`), and that both a direct `makeMove()` response and a simulated `MOVE_MADE` push reach the registered `onUpdate` callback.
- **A real end-to-end JMS reachability test** for the broker-transport change — a test that starts the new TCP-connector broker (mirroring how `ServerMainTest` already starts a real registry) and connects a second, independent `Connection` to it via `tcp://localhost:<test-port>` the way a real separate client process would, proving the fix actually solves the cross-process problem rather than just compiling.
- **No FXML/Presentation-layer unit tests** — consistent with how JavaFX UI is generally not unit-tested (no test-fake exists for `CheckersEngine` either, for the opposite reason — no I/O to fake around; here it's the opposite problem, UI is only really verifiable by running it). Manual verification: run `mvn javafx:run` twice (two client processes) alongside `ServerMain`, play a full game between them.

## Out of scope (deferred, not forgotten)

- **Chat, resign, rematch** — `PlayerServiceImpl` still throws `UnsupportedOperationException` for all three; no client UI for them until those roadmap steps land.
- **`keepAlive`/disconnect detection** — spec §5 assigns this to step 10; no periodic heartbeat is added by this client yet.
- **Per-player board orientation** (flipping the board for player 2 so their own pieces are always at the bottom) — fixed single orientation for now, a presentation-layer nicety that doesn't affect correctness.
- **Admin client** — separate roadmap step 9, shares nothing with this milestone except the `ServerConnection`-style pattern it'll likely reuse.
- **Reconnect-after-disconnect handling** — if the client's JMS connection drops, no automatic resubscribe/retry is built here; out of scope until step 10's edge-case work.
