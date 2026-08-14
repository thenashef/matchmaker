# Disconnect Detection & Turn Timeout Implementation Plan

**Goal:** Implement `docs/specs/2026-08-14-disconnect-timeout-design.md` in full — `keepAlive`-based disconnect detection, turn timeout, both ending a session as `ABANDONED` with a real winner and ELO update, plus the turn-start notification sound. See that doc for full rationale; this plan is the "how," ordered so each task leaves the build/test suite green.

**Tech stack:** unchanged — Java 21, JUnit 5, `java.time`/`java.util.concurrent.ScheduledExecutorService` for the new timing/sweep logic, `javafx-media` (new dependency) for the sound.

## Global constraints

- `GameStateDTO` gaining a `turnStartedAt` field is a breaking constructor change — every construction site across `main` and `test` must be updated in the same task (Task 1), or the build won't compile at all in between. Do this first, before anything else depends on the DTO shape.
- `GameSessionDao.abandon(int, Integer)` and `SessionWatchdog` are new, additive surface — safe to build incrementally after Task 1 lands.
- Every step that touches `mvn test`'s DB-integration tier needs `docker compose up -d` running first; everything else stays Docker-free per the existing test-tier split.
- Match existing style exactly: no comments except where a hidden constraint/invariant needs explaining (this codebase's own convention, visible in every file touched below).

---

### Task 1: `GameStateDTO.turnStartedAt`

**Files:**
- Modify: `src/main/java/com/matchmaker/common/dto/GameStateDTO.java`
- Modify: `src/main/java/com/matchmaker/server/dao/JdbcGameSessionDao.java` (every `ResultSet`-mapping method: `findFinishedSessionsForUser`, `findActiveById`, `findAllActive`, `recordMove`, `forceEnd`'s `findAnyById`)
- Modify: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java` (`joinQueue()`, `makeMove()` — both branches)
- Modify: `src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java`
- Modify: `src/test/java/com/matchmaker/common/dto/ExistingDtoSerializationTest.java` (or wherever `GameStateDTO` round-trip is covered — extend for the new field)
- Modify: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java` (any inline `new GameStateDTO(...)` fixtures)

**Steps:**
1. Add `private final Instant turnStartedAt;` + constructor arg (append at the end, after `boardState`, to minimize positional-arg churn risk) + `getTurnStartedAt()` to `GameStateDTO`.
2. Fix every compile error this produces — `JdbcGameSessionDao` reads `rs.getTimestamp("TurnStartedAt").toInstant()` in each mapping method (all five queries already `SELECT`... confirm `TurnStartedAt` is in each `SELECT` list; add it where missing). `PlayerServiceImpl.joinQueue()`/`makeMove()` thread the existing session's `turnStartedAt` through unchanged on the `CONTINUE` branch; on `makeMove()`'s successful-move branches, use `Instant.now()` for a fresh turn (only `recordMove()`'s own `TurnStartedAt = NOW()` in the SQL is authoritative — the DTO built in `makeMove()` before calling `recordMove()` should still pass something reasonable through, but `recordMove()`'s returned/re-read row is what actually matters downstream).
3. `InMemoryGameSessionDao`: give its fake rows a `turnStartedAt`, defaulting to `Instant.now()` at insertion, updated on `recordMove()`'s fake.
4. Run `mvn test-compile` to confirm everything compiles before running tests.
5. Run `mvn test` (no Docker needed for this task's own new coverage, but the full suite touches DAO tests — run `docker compose up -d && mvn test` for a full green baseline).
6. Commit: `git commit -m "Add turnStartedAt to GameStateDTO"`.

---

### Task 2: `SessionManager` presence tracking

**Files:**
- Modify: `src/main/java/com/matchmaker/server/SessionManager.java`
- Modify: `src/test/java/com/matchmaker/server/SessionManagerTest.java`

**Steps:**
1. Write failing tests first: `lastSeen(userId)` returns `Optional.empty()` before any `resolve()` call for that user; after `createSession()` + `resolve(token)`, `lastSeen(userId)` returns a recent `Instant`; a second `resolve()` call later updates it further.
2. Add `Map<Integer, Instant> lastSeenByUserId = new ConcurrentHashMap<>()`. In `resolve(token)`, after successfully looking up `userId`, `lastSeenByUserId.put(userId, Instant.now())` before returning. Add `Optional<Instant> lastSeen(int userId)`.
3. Run `mvn test -Dtest=SessionManagerTest`, confirm green.
4. Commit: `git commit -m "Track per-user last-seen timestamps in SessionManager"`.

---

### Task 3: `GameSessionDao.abandon()`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/dao/GameSessionDao.java`
- Modify: `src/main/java/com/matchmaker/server/dao/JdbcGameSessionDao.java`
- Modify: `src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java`
- Modify: `src/test/java/com/matchmaker/server/dao/GameSessionDaoTest.java` (Docker)

**Steps:**
1. `GameSessionDao`: `Optional<GameStateDTO> abandon(int sessionId, Integer winnerUserId);`
2. `JdbcGameSessionDao.abandon()`: same guarded-`UPDATE` shape as `forceEnd()` (`WHERE ID = ? AND Status = 'ACTIVE'`) but `SET Status = 'ABANDONED', WinnerID = ?, EndTime = NOW()`. If `winnerUserId != null`, inside the same transaction call the existing private `applyEloAndRecordResult(conn, winnerUserId, loserUserId)` — `loserUserId` is whichever of `Player1ID`/`Player2ID` isn't the winner, read from the row before/via the update. If `winnerUserId == null`, skip the ELO call entirely (mirrors `forceEnd()`).
3. `InMemoryGameSessionDao.abandon()`: same no-op-if-already-finished semantics as its existing `forceEnd()` fake, plus recording the winner (or lack of one) on the fake row for assertions.
4. `GameSessionDaoTest`: extend with — winner case (status/winner/ELO all update, mirroring the existing `recordMove()` ELO-transaction test), no-winner case (status updates, `WinnerID` stays null, `Wins`/`Losses`/`Rating` untouched for both players), already-finished no-op case.
5. Run `docker compose up -d && mvn test -Dtest=GameSessionDaoTest`, confirm green.
6. Commit: `git commit -m "Add GameSessionDao.abandon() for disconnect/timeout auto-forfeit"`.

---

### Task 4: `GameEventType.SESSION_ABANDONED` + client routing

**Files:**
- Modify: `src/main/java/com/matchmaker/common/enums/GameEventType.java`
- Modify: `src/main/java/com/matchmaker/client/logic/GameClientService.java` (`onSessionTopicEvent`/equivalent filter)
- Modify: `src/main/java/com/matchmaker/admin/logic/AdminClientService.java` (same)
- Modify: `src/test/java/com/matchmaker/client/logic/GameClientServiceTest.java`
- Modify: `src/test/java/com/matchmaker/admin/logic/AdminClientServiceTest.java`

**Steps:**
1. Add `SESSION_ABANDONED` to `GameEventType`, alongside `MATCH_FOUND`/`MOVE_MADE`/`SESSION_FORCE_ENDED`.
2. Widen both clients' event-type filter (currently `MOVE_MADE || SESSION_FORCE_ENDED`, per Milestone 8) to also accept `SESSION_ABANDONED`, routed through the identical refresh path.
3. Extend both `*ClientServiceTest` files with one case each, mirroring the existing `SESSION_FORCE_ENDED` test: a pushed `SESSION_ABANDONED` event reaches the attached listener.
4. Run `mvn test -Dtest=GameClientServiceTest,AdminClientServiceTest`, confirm green.
5. Commit: `git commit -m "Add SESSION_ABANDONED event type and route it like SESSION_FORCE_ENDED"`.

---

### Task 5: `SessionWatchdog`

**Files:**
- New: `src/main/java/com/matchmaker/server/SessionWatchdog.java`
- New: `src/test/java/com/matchmaker/server/SessionWatchdogTest.java`
- Modify: `src/main/java/com/matchmaker/server/ServerMain.java`

**Steps:**
1. Design the class for testability: constructor `SessionWatchdog(SessionManager, GameSessionDao, GameEventPublisher, Duration disconnectTimeout, Duration turnTimeout)` (thresholds injected, not hardcoded, so the test can use millisecond-scale thresholds instead of waiting 60 real seconds) plus a package-visible `void sweepOnce()` doing one pass — `start(Duration tickInterval)`/`stop()` wrap `sweepOnce()` in a `ScheduledExecutorService`. Production code (`ServerMain`) calls `start(Duration.ofSeconds(5))` with real 60s thresholds; tests call `sweepOnce()` directly, synchronously, with short thresholds and fabricated `Instant`s.
2. `sweepOnce()` logic, for every `gameSessionDao.findAllActive()` row:
   - Look up both participants' `sessionManager.lastSeen(...)`.
   - Exactly one missing/older than `disconnectTimeout` → `abandon(sessionId, otherParticipant)`.
   - Both missing/older than `disconnectTimeout` → `abandon(sessionId, null)`.
   - Else, `Duration.between(session.getTurnStartedAt(), Instant.now())` older than `turnTimeout` → `abandon(sessionId, theNonCurrentTurnParticipant)`.
   - On any of the three, call `gameSessionDao.abandon(...)`; if it returns a present `Optional` (i.e. actually did something), publish `SESSION_ABANDONED` via `gameEventPublisher.publishToSession(...)`, catching/logging `JmsPublishException` same as every other publish call in this codebase.
3. Write `SessionWatchdogTest` first (TDD): against `InMemoryGameSessionDao`/`InMemoryGameEventPublisher`/a real `SessionManager` seeded via `createSession()` + manually backdating `lastSeenByUserId` (may need a small test-only seam — e.g. a package-private setter, or drive it purely through `Duration`s short enough that `Thread.sleep` in the test is acceptable). Cases: single-participant silence → abandon + correct winner + event published; both silent → abandon + null winner + event published; expired turn → abandon + non-current-turn participant wins; healthy session → untouched, nothing published.
4. Implement `SessionWatchdog` to make the tests pass.
5. Wire into `ServerMain.startWithImpls()`: construct with real 60s/60s thresholds, `.start(Duration.ofSeconds(5))`, add to the `Started` record; extend `ServerMainTest`'s teardown to `.stop()` it.
6. Run `mvn test -Dtest=SessionWatchdogTest,ServerMainTest`, then the full suite.
7. Commit: `git commit -m "Add SessionWatchdog: sweep active sessions for disconnect/turn timeout"`.

---

### Task 6: Wire `keepAlive` through both client stacks

**Files:**
- Modify: `src/main/java/com/matchmaker/client/communication/ServerConnection.java`
- Modify: `src/main/java/com/matchmaker/client/communication/RmiJmsServerConnection.java`
- Modify: `src/main/java/com/matchmaker/admin/communication/AdminConnection.java`
- Modify: `src/main/java/com/matchmaker/admin/communication/RmiJmsAdminConnection.java`
- Modify: `src/test/java/com/matchmaker/client/communication/InMemoryServerConnection.java`
- Modify: `src/test/java/com/matchmaker/admin/communication/InMemoryAdminConnection.java`
- Modify: `src/main/java/com/matchmaker/client/logic/GameClientService.java`
- Modify: `src/main/java/com/matchmaker/admin/logic/AdminClientService.java`
- Modify: `src/test/java/com/matchmaker/client/logic/GameClientServiceTest.java`
- Modify: `src/test/java/com/matchmaker/admin/logic/AdminClientServiceTest.java`

**Steps:**
1. Add `void keepAlive(String sessionToken) throws AuthenticationException;` to both `ServerConnection`/`AdminConnection`.
2. `RmiJmsServerConnection`/`RmiJmsAdminConnection`: delegate to `authService.keepAlive(sessionToken)`, wrapping `RemoteException` in the existing `ServerCommunicationException`/`AdminCommunicationException` the same way every other method here does.
3. `InMemoryServerConnection`/`InMemoryAdminConnection`: trivial recording fake (a call counter or last-called-token field), matching the existing style for other methods.
4. `GameClientService`/`AdminClientService`: after a successful `login()`, start a `ScheduledExecutorService` calling `serverConnection.keepAlive(token)` every 15s; stop it wherever the class already tears down state on logout (or add a `stop()`/`shutdown()` if nothing exists yet — check each class's current lifecycle methods first).
5. Tests: confirm `keepAlive()` is callable end-to-end against the in-memory fake, and that the periodic timer starts after login. Real-time 15s waits are too slow for a unit test — either expose a package-visible way to trigger one tick synchronously, or inject the interval, matching the same testability approach as `SessionWatchdog` in Task 5.
6. Run `mvn test -Dtest=GameClientServiceTest,AdminClientServiceTest`, then full suite.
7. Commit: `git commit -m "Wire keepAlive through ServerConnection/AdminConnection with a 15s client-side timer"`.

---

### Task 7: Turn-start notification sound

**Files:**
- Modify: `pom.xml` (add `javafx-media:21.0.12`)
- New: a short audio asset under `src/main/resources/com/matchmaker/client/presentation/` (or similar, matching existing FXML resource placement)
- Modify: `src/main/java/com/matchmaker/client/presentation/GameBoardController.java`

**Steps:**
1. Add the `javafx-media` dependency, matching the existing pinned version.
2. Add a small `.wav` (a short, simple generated tone is fine — this is a course project, not a produced game) under `src/main/resources`.
3. In `GameBoardController`, wherever it currently receives a fresh `GameStateDTO` (from `makeMove`'s return, a pushed `MOVE_MADE`/`SESSION_ABANDONED`, or the initial matched state), check `newState.getCurrentTurnUserId()` against the logged-in user's own id; if equal, play the clip via `new AudioClip(getClass().getResource(...).toExternalForm()).play()`.
4. No automated test (consistent with the rest of `client.presentation`) — verify manually in Task 9.
5. Run `mvn compile` to confirm it builds (JavaFX media isn't exercised by any headless test).
6. Commit: `git commit -m "Play a sound when it becomes the player's turn"`.

---

### Task 8: Full suite + manual verification

**Steps:**
1. `docker compose up -d && mvn test` — full suite green.
2. Manual: run `mvn exec:java`, confirm the startup banner includes no new errors and the watchdog doesn't crash the process.
3. Manual (if time allows, not a hard requirement before requesting review): two `mvn javafx:run` processes, match a game, sit on a turn past 60s, confirm both players see the game end `ABANDONED` with the sitting player's opponent awarded the win, and confirm the turn sound plays on `MATCH_FOUND` and after each opponent move.
4. Manual: check whether the three pre-existing orphaned `ACTIVE` sessions in the dev `matchmaker` database (not `matchmaker_test`) get swept — expect a no-winner `ABANDONED` (both participants long silent) once `ServerMain` runs against real dev data for over 60s.

---

## Post-plan status update

Once the full suite is green: update `docs/build-plan.md` — fold this work in as a new Milestone entry (matching the existing per-milestone write-up shape), move its "Now" bullet in "Next Steps" to done, and leave Rematch/JMS-broker-security as the remaining deferred items. Update `docs/project-structure.md`'s `server/` section for `SessionManager`'s new field and the new `SessionWatchdog`, `server/dao/`'s `GameSessionDao` bullet for `abandon()`, `common/enums/GameEventType` for `SESSION_ABANDONED`, and the client/admin communication+logic bullets for `keepAlive`. This is a direct doc edit after both the code and this plan are verified, not a plan task with its own test — same pattern used after every prior milestone.
