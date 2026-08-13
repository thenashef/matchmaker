# Design: Admin Client (Roadmap Step 9)

## Context

Milestone 7 (JavaFX player client, step 8) is code-complete and mostly manually verified. `AdminService`'s five methods (`listGameTypes`, `addGameType`, `listUsers`, `listActiveSessions`, `forceEndSession`) have existed since the RMI-skeleton milestone (Milestone 2) but are still deliberate `UnsupportedOperationException` stubs — nothing about them has been touched since.

Spec §1: *"The system supports a user who is an admin, who can add new game types, manage users, and monitor active game sessions."* Spec §4 mandates the same three-layer split for the admin module as the player client: *"Presentation layer... Logic layer... Server communication layer."* Spec §5: *"Admin monitoring – the admin subscribes read-only to the topics of active sessions, thereby following them in real time without the ability to influence the game."* Spec §10 has concrete wireframes: a Dashboard (summary tiles + active-sessions table), an Add Game Type form, and a Live Game Monitor (session detail + live board + Force End Session / Export Log buttons).

**What scoping this surfaced:** step 9 is not primarily a client-UI milestone the way the tail end of step 8 was. `AdminServiceImpl`'s admin-authorization check (`NotAdminException`) has never been implemented anywhere — `SessionManager.resolve()` only ever returns a bare `userId`, with no way to check `IsAdmin` without a DAO lookup, the same way `AuthServiceImpl` already does for login. So real, DAO-backed implementations of all five `AdminService` methods, including that authorization check, are a hard prerequisite before any admin client screen has something real to call — the same shape of prerequisite the JMS broker fix was for step 8.

**Also decided already, in chat before this doc:** dashboard elements with no backing `AdminService` data (online player count, games played today, queue size, "Export Log") are out of scope — build from what the five existing methods actually provide, don't invent new RMI surface to chase the wireframe.

## Decisions

1. **`AdminServiceImpl` gets one private `requireAdmin(sessionToken)` helper**, not a separate class — all five methods need the identical "resolve token → look up the user → verify `IsAdmin`, else throw `NotAdminException`" preamble, and it's four lines, not enough to justify a collaborator of its own. Returns the resolved `UserRecord` so callers that also need the caller's id (none currently do, but keeps the helper genuinely useful) have it without a second lookup.
2. **DAO surface needs four additions**, following each DAO's existing style exactly:
   - `UserDao.findById(int id)` — for the admin check itself, and reusable for `listUsers()`'s per-row mapping.
   - `UserDao.findAll()` — for `listUsers()`.
   - `GameTypeDao.insert(GameTypeDTO newGameType)` — for `addGameType()`. Returns the created `GameTypeDTO` (with the DB-assigned id) directly via `Statement.RETURN_GENERATED_KEYS`, no re-query needed. No uniqueness constraint on `GameType.Name` in the schema, so no duplicate-name failure mode to report — admin's responsibility, not the DAO's.
   - `GameSessionDao.findAllActive()` — for `listActiveSessions()`: every `Status = 'ACTIVE'` row, not scoped to one user (unlike the existing `findFinishedSessionsForUser`).
   - `GameSessionDao.forceEnd(int sessionId)` — `UPDATE ... SET Status = 'ABANDONED', WinnerID = NULL, EndTime = NOW() WHERE ID = ? AND Status = 'ACTIVE'`, mirroring `recordMove()`'s guarded-update pattern (a session that already ended naturally in the gap between an admin viewing it and clicking the button just makes this a no-op — 0 rows affected, `Optional.empty()` returned, `AdminServiceImpl.forceEndSession()` treats that as success-doing-nothing rather than an error, since the RMI method's signature doesn't declare any "already ended" exception).
3. **Force-ending a session notifies the two players in it, not just the admin.** They're actively subscribed to that session's JMS topic (from `enterGame()` in step 8's `GameClientService`) — if their game gets force-ended, their Game Board screens need to hear about it, not sit there indefinitely thinking the game is still `ACTIVE`. New `GameEventType.SESSION_FORCE_ENDED` value (not a reuse of `MOVE_MADE` — no move happened, and calling it one would be a lie the same way an earlier decision already rejected inventing a redundant `GAME_ENDED` type for the *opposite* reason). `PlayerServiceImpl`... no — `AdminServiceImpl.forceEndSession()` publishes it to `session.{sessionId}.events` via the existing `GameEventPublisher.publishToSession()`, no new publisher method needed. **This requires one small change to the already-shipped step-8 client code:** `GameClientService.onSessionTopicEvent()` currently hard-filters to only `GameEventType.MOVE_MADE`; it needs to also accept `SESSION_FORCE_ENDED` and route it through the same `applyState`-shaped refresh, since both carry a fresh `GameStateDTO` the board should just re-render from.
4. **`admin.communication` / `admin.logic` / `admin.presentation` — the same three-layer shape as `client.*`, fully independent of it.** Own `AdminConnection` (interface) / `RmiJmsAdminConnection` (real impl) / `InMemoryAdminConnection` (test fake), same names for the small shared-shape pieces (`ServerEventListener`, `Subscription`) duplicated into the `admin.communication` package rather than imported from `client.communication` — same reasoning as keeping `client` independent of `server`: a few lines of duplication is cheaper than a cross-package dependency between two things the spec draws as separate boxes. `AdminConnection` only ever *subscribes* to a session topic (`subscribeToSessionTopic`), never publishes — matches spec §5's "without the ability to influence the game" for everything except the one explicit exception (force-ending), which goes through the ordinary RMI `forceEndSession()` call, not JMS.
5. **One `AdminClientService`** (Logic layer), mirroring `GameClientService`. Simpler than the player one — no matchmaking-style queue/topic subscription handoff dance, since monitoring a session is an explicit per-screen opt-in (open the Live Monitor for session X), not something that starts automatically at login.
6. **Admin login reuses `AuthService.login()` as-is** (same `User` table, `IsAdmin` just a flag on it) — but the admin app's own Login screen checks `user.isAdmin()` after a successful login and shows a clear error for a non-admin account, purely as a UX guard (the real security boundary is server-side `NotAdminException` on every `AdminService` call regardless). This needs its own `AdminLoginController`/`AdminLoginView.fxml`, not a reuse of the player client's `LoginController` — that class is wired to `GameClientService`/`ServerConnection`, and reusing it would mean `admin.presentation` importing `client.*`, which breaks the same independence this doc just established for the communication layer.
7. **Screens, scoped to what's backed by real data** (per the chat decision before this doc): Dashboard (active-session count and table from `listActiveSessions()`, total user count from `listUsers().size()` — no online/today/queue tiles), Add Game Type (form → `addGameType()`), Live Session Monitor (session detail + live board, read-only, refreshed by both `MOVE_MADE` and `SESSION_FORCE_ENDED` pushes; a "Force End Session" button; no "Export Log" button — nothing backs it).
8. **`AdminMain` is a second, separate JavaFX entry point**, run via a Maven profile that swaps `javafx-maven-plugin`'s `<mainClass>` (`mvn javafx:run -Padmin`), not a second hardcoded default — the plugin only supports one configured main class per plain invocation. **Flagged to verify empirically during implementation**, not asserted with full confidence here — this is the one piece of this design pulled from the plugin's README rather than something already proven working in this repo the way `mvn javafx:run` for `ClientMain` is.

## Architecture

```
db/schema.sql                              no changes -- existing tables already have everything needed

server/dao/
├── UserDao.java                            + findById(int), findAll()
├── JdbcUserDao.java                        + both, same query style as findByUsername
├── GameTypeDao.java                        + insert(GameTypeDTO)
├── JdbcGameTypeDao.java                    + insert, via RETURN_GENERATED_KEYS
├── GameSessionDao.java                     + findAllActive(), forceEnd(int sessionId)
└── JdbcGameSessionDao.java                 + both, forceEnd() mirrors recordMove()'s guarded UPDATE

src/test/.../server/dao/
├── InMemoryUserDao.java                    + findById, findAll
├── InMemoryGameTypeDao.java                + insert
└── InMemoryGameSessionDao.java             + findAllActive, forceEnd

common/enums/GameEventType.java             + SESSION_FORCE_ENDED

server/rmi/AdminServiceImpl.java            real implementation, requireAdmin() helper, all 5 methods

client/logic/GameClientService.java         onSessionTopicEvent() also accepts SESSION_FORCE_ENDED

com.matchmaker.admin/                       new top-level package, sibling to client/server/common
├── AdminMain.java                          JavaFX Application entry point (own Stage, own SceneNavigator instance)
├── communication/
│   ├── AdminConnection.java                interface: listGameTypes, addGameType, listUsers,
│   │                                        listActiveSessions, forceEndSession, subscribeToSessionTopic
│   ├── RmiJmsAdminConnection.java          real impl -- RMI stub lookup (AdminService), JMS subscribe-only
│   ├── ServerEventListener.java            duplicated shape from client.communication, own package
│   ├── Subscription.java                   duplicated shape from client.communication, own package
│   └── AdminCommunicationException.java    unchecked wrapper for RemoteException, same role as
│                                            client.communication.ServerCommunicationException
├── logic/
│   └── AdminClientService.java             the whole Logic layer for this milestone
└── presentation/
    ├── AdminLoginView.fxml / AdminLoginController.java
    ├── DashboardView.fxml / DashboardController.java
    ├── AddGameTypeView.fxml / AddGameTypeController.java
    ├── LiveSessionMonitorView.fxml / LiveSessionMonitorController.java
    └── SceneNavigator.java                 duplicated from client.presentation (same fixed-size-window
                                              pattern this session just fixed for the player client)

src/test/java/com/matchmaker/admin/
├── communication/InMemoryAdminConnection.java
└── logic/AdminClientServiceTest.java
```

## Data flow

**Login:** `AdminLoginController` calls `AdminClientService.login(username, password, onSuccess, onError)` → `AdminConnection.login()` (same `AuthService.login()` RMI call the player client uses) → on success, if `!user.isAdmin()`, show "This account isn't an admin account" and stay on the login screen; otherwise navigate to `DashboardView`.

**Dashboard:** on show, calls `listActiveSessions()` and `listUsers()`, populates the active-sessions table and a user count. Each session row has a "Monitor" action navigating to `LiveSessionMonitorView` for that session id. A "New Game Type" button navigates to `AddGameTypeView`.

**Add Game Type:** form fields map directly to `GameTypeDTO`'s constructor args (minus id). Submit calls `addGameType()`; on success, navigate back to Dashboard (which re-fetches and shows the new type).

**Live Session Monitor:** on show, subscribes read-only to `session.{sessionId}.events` (the same topic the two players are on) and renders the session detail + board from whatever `GameStateDTO` it has (the one `listActiveSessions()` returned, refreshed live by any `MOVE_MADE`/`SESSION_FORCE_ENDED` push — same "subscribe before anything else, since Topics don't retain for late subscribers" rule from step 8's design doc applies here too). "Force End Session" calls `forceEndSession()`; the resulting `SESSION_FORCE_ENDED` push (which the admin's own subscription also receives) updates this screen to show `ABANDONED`, and separately reaches both players' Game Board screens the same way.

## Wiring

- `ServerMain`: `AdminServiceImpl`'s constructor widens from `(SessionManager)` to `(SessionManager, UserDao, GameTypeDao, GameSessionDao)` — all three already exist as local variables in `startWithImpls()`, just not currently passed to `AdminServiceImpl`.
- `pom.xml`: add an `admin` Maven profile overriding `javafx-maven-plugin`'s `mainClass` to `com.matchmaker.admin.AdminMain`. Run with `mvn javafx:run -Padmin`; the player client keeps working unchanged via the plugin's default `mainClass` (`mvn javafx:run`, no profile).

## Testing

- **DAO tier** (Docker, `matchmaker_test`): extend `UserDaoTest`/`GameTypeDaoTest`/`GameSessionDaoTest` for the four new methods, same real-SQL style as the existing tests in each file.
- **`AdminServiceImplTest`**: rewritten off the current "every method throws" test — real assertions against `InMemory*Dao` fakes: each method's happy path, and one shared `NotAdminException` case per method for a non-admin caller. Mirrors `PlayerServiceImplTest`'s shape.
- **`GameClientServiceTest`** (already exists, step 8): one new case confirming `SESSION_FORCE_ENDED` reaches the attached game-update listener the same way `MOVE_MADE` does.
- **`AdminClientServiceTest`** (new): Docker/RMI/JMS-free against `InMemoryAdminConnection`, mirroring `GameClientServiceTest`'s shape — login/admin-check, each RMI-wrapping method's success/error path, the session-topic subscribe-on-monitor / unsubscribe-on-leave lifecycle.
- No automated tests for `admin.presentation`, same reasoning as `client.presentation`.
- Manual verification (mirrors step 8's Task 8): run `ServerMain`, one `mvn javafx:run` (player) matched into a game, one `mvn javafx:run -Padmin` (admin) — confirm the Dashboard lists that session, Live Monitor shows the live board updating as the players move, and Force End Session both updates the monitor and ends the game on both player screens.

## Out of scope (deferred, not forgotten)

- Dashboard tiles with no backing data (online player count, games played today, queue size) — revisit only if new `AdminService` methods are ever added to support them.
- "Export Log" — no backing method, no roadmap line calling for it.
- Editing or deleting an existing `GameType` — `AdminService` only has `addGameType`.
- Any admin action that influences a live game beyond ending it outright — spec §5 is explicit that monitoring is read-only "without the ability to influence the game."
