# Design: Disconnect Detection & Turn Timeout (Roadmap Step 10, part 1)

## Context

Roadmap step 10: *"`keepAlive`/disconnect handling → `ABANDONED` state, turn timeout, Rematch, per-session authorization checks."* Spec §5: *"since RMI and JMS do not notify about disconnection on their own, the client periodically calls a `keepAlive` method on the server. If a player is silent beyond the allotted time, the server ends the game in an `ABANDONED` state and awards the opponent the win."* And: *"when the turn time limit is exceeded (per `TurnStartedAt`), the server ends the turn automatically."*

Step 10 splits into four pieces of very different size, plus a fifth (real JMS broker-level authentication) found during the per-session-authorization audit but not originally in the roadmap line. Per a sequencing decision made in chat (2026-08-14): **this doc covers only disconnect detection and turn timeout.** Rematch and the JMS broker-security work are deferred — full detail in `docs/build-plan.md`'s "Next Steps" section, not repeated here.

**Grounding facts, confirmed by reading the code rather than assumed:**
- `AuthServiceImpl.keepAlive()` only calls `sessionManager.resolve(sessionToken)` — validates the token and returns, no timestamp tracking. `SessionManager` has no last-seen field at all today (just `Map<String, Integer>` token→userId).
- Neither client ever calls `keepAlive()` (`grep -rn keepAlive src/main/java/com/matchmaker/client/ src/main/java/com/matchmaker/admin/` — zero hits), and it isn't even exposed on `ServerConnection`/`AdminConnection` — only the raw RMI `AuthService` interface has it.
- Three real `GameSession` rows sit `Status = 'ACTIVE'` in the dev database right now with no player actually connected (from earlier manual testing) — a natural, already-existing test case for this work, not a hypothetical.
- `AdminServiceImpl`/`GameSessionDao.forceEnd()` already does something adjacent but isn't reusable as-is: it sets `WinnerID = NULL` and never touches `Wins`/`Losses`/`Rating`, because an admin force-end isn't a win for anyone. This work needs a genuinely different DAO path.
- `JdbcGameSessionDao` already has a private `applyEloAndRecordResult(Connection, int winnerId, int loserId)` helper, used by `recordMove()` on a normal game-ending move — directly reusable here.
- `GameStateDTO` does not carry `TurnStartedAt` today — the column exists and is written on every `recordMove()`/session creation, but nothing ever reads it back out into the DTO. Needs adding.
- `pom.xml` declares `javafx-controls`/`javafx-fxml` (both `21.0.12`) but not `javafx-media` — needed for the new turn-notification sound.
- `JdbcMatchmakingQueue`'s session-creation `INSERT` already sets `TurnStartedAt` at match time (not left null until the first move) — so any `ACTIVE` session always has a real `TurnStartedAt` and a real `CurrentTurnUserID`, no null-guarding needed for either in the sweep below.

**Also decided in chat before this doc:**
- Turn timeout is a **forfeit**, not a "skip the turn, keep playing" mechanic — direct instruction, overriding the spec's more literal "ends the turn" wording. This means disconnect-silence and turn-timeout are two different *triggers* that both resolve to the exact same *action* (session → `ABANDONED`, opponent wins), which is why one shared mechanism covers both.
- Presence detection is a pure connectivity heartbeat (client pings on a fixed timer, unconditionally), not local activity/AFK tracking. Mouse/keyboard movement inside the JavaFX window isn't visible to the server at all unless the client turns it into a network call, and tying presence to real interaction would double-penalize a player who's legitimately thinking — that case is already covered by the turn-timeout clock.

## Decisions

1. **Timing constants**: client pings `keepAlive` every **15s**; a participant silent for **60s** is disconnected; a turn running longer than **60s** times out. Both conditions produce the identical outcome — `Status = ABANDONED`, `WinnerID` = the other participant, same `Wins`/`Losses`/`Rating` ELO update a normal game-ending move gets. No separate "turn skipped, game continues" state exists.

2. **`SessionManager` gains a second map**: `Map<Integer, Instant> lastSeenByUserId`, alongside the existing `Map<String, Integer> userIdByToken`. Every successful `resolve(token)` call — which is what every authenticated RMI method already goes through, not just `keepAlive` — updates `lastSeenByUserId.put(userId, Instant.now())` before returning. This means the periodic `keepAlive` ping is what *guarantees* a baseline signal (it's the only call a player currently waiting on their opponent's move ever makes), while every other real call (a move, joining a queue) updates the same timestamp as a free byproduct of already resolving the token — not a second detection system. New accessor: `Optional<Instant> lastSeen(int userId)`.

3. **New `SessionWatchdog`** (`server` package, alongside `SessionManager`/`ServerMain`) — owns one single-thread `ScheduledExecutorService`, ticking every **5s** (short relative to the 60s thresholds, so detection latency stays low; cheap, since it's just iterating active sessions). Constructor takes `SessionManager`, `GameSessionDao`, `GameEventPublisher`. Each tick, for every session `gameSessionDao.findAllActive()` returns:
   - If **exactly one** participant's `lastSeen` is missing or older than 60s → abandon, the *other* participant wins.
   - Else if **both** participants are silent past 60s (decided in chat: a double-disconnect isn't a competitive result — this is also what the three already-orphaned dev-DB sessions are, since neither player in them is actually still connected) → abandon with **no winner** (`WinnerID = NULL`, no ELO/`Wins`/`Losses` update), the same no-fault shape as admin's `forceEnd()`. `GameSessionDao` needs a second method for this, or `abandon()` takes a nullable `Integer winnerUserId` and only runs `applyEloAndRecordResult` when it's non-null.
   - Else if `Duration.between(gameSessionDao.currentTurnStartedAt(sessionId), Instant.now())` exceeds 60s → abandon, the participant who is **not** `currentTurnUserId` wins.
   
   `start()`/`stop()` methods around the executor; constructed and started in `ServerMain.startWithImpls()`, added to the `Started` record so `ServerMainTest` can shut it down in teardown, the same way the JMS broker and registry already are.

4. **New `GameSessionDao.currentTurnStartedAt(int sessionId)`** returning `Optional<Instant>`, rather than widening `GameStateDTO` itself. `GameStateDTO` is constructed in ~13 places across the codebase, most of them test fixtures for entirely unrelated features (`AdminServiceImplTest`, `GameClientServiceTest`, `JdbcMatchmakingQueue`, `EmbeddedJmsBrokerTest`, etc.) — only `SessionWatchdog` actually needs `TurnStartedAt`, so a small dedicated lookup (one query per active session per sweep tick — trivially cheap at this scale) keeps the change contained to `GameSessionDao`/`JdbcGameSessionDao`/`InMemoryGameSessionDao` instead of rippling through every DTO construction site in the codebase. `JdbcGameSessionDao`: `SELECT TurnStartedAt FROM GameSession WHERE ID = ? AND Status = 'ACTIVE'`. `InMemoryGameSessionDao`: a parallel `Map<Integer, Instant>` the fake maintains itself (seeded on `addActiveSession()`, refreshed on `recordMove()`), since `GameStateDTO` itself carries no such field.

5. **New `GameSessionDao.abandon(int sessionId, Integer winnerUserId)`**, mirroring `forceEnd()`'s guarded update (`WHERE ID = ? AND Status = 'ACTIVE'` — a session that already ended naturally in the gap before this commits just becomes a no-op, `Optional.empty()`, not an error). When `winnerUserId` is non-null, sets a real `WinnerID` and, in the same transaction, calls the existing `applyEloAndRecordResult(conn, winnerUserId, loserUserId)` — so a single-player disconnect/timeout updates rating exactly like a normal win. When `winnerUserId` is null (the double-disconnect case), behaves exactly like admin's `forceEnd()` — `WinnerID = NULL`, no rating touched.

6. **New `GameEventType.SESSION_ABANDONED`** — not a reuse of `SESSION_FORCE_ENDED` (that name specifically means an admin did it) or `MOVE_MADE` (no move happened). `SessionWatchdog` publishes it to the session's existing topic via `GameEventPublisher.publishToSession()` right after a successful `abandon()`, same catch-log-don't-fail-the-caller handling every other publish call in this codebase already uses. Both clients' existing `SESSION_FORCE_ENDED` board-refresh routing (`GameClientService`/`AdminClientService`'s `onSessionTopicEvent`) widens to also accept `SESSION_ABANDONED` — same handling, it's already just "refresh from a fresh `GameStateDTO`."

7. **`keepAlive` gets wired all the way through the client stack**, which today stops at the RMI layer:
   - `ServerConnection`/`AdminConnection` interfaces gain `void keepAlive(String sessionToken) throws AuthenticationException`.
   - `RmiJmsServerConnection`/`RmiJmsAdminConnection` delegate to `authService.keepAlive(...)`.
   - `InMemoryServerConnection`/`InMemoryAdminConnection` (test fakes) get a trivial recording implementation, matching how every other method is faked there.
   - `GameClientService`/`AdminClientService` each start a 15s periodic timer right after a successful login (a `ScheduledExecutorService`, same tool `SessionWatchdog` uses server-side) calling `keepAlive`, stopped on logout/shutdown alongside whatever else already gets torn down there.

8. **New: turn-start notification sound**, in `client.presentation` only (not `client.logic` — keeps `GameClientService` free of JavaFX media APIs, same layering reasoning as everywhere else in this codebase). Whenever `GameBoardController` receives a fresh `GameStateDTO` (from a move, or from being matched) whose `currentTurnUserId` equals the logged-in player's own id, it plays a short clip via `javafx.scene.media.AudioClip`. Requires adding the `javafx-media` Maven dependency (matching the existing `21.0.12` version already pinned for `javafx-controls`/`javafx-fxml`) and a small bundled sound asset under `src/main/resources`. Admin client doesn't need this — admin never has "a turn."

## Architecture

```
server/
├── SessionManager.java                     + lastSeenByUserId map, resolve() updates it, + lastSeen(int) accessor
├── SessionWatchdog.java                    new -- ScheduledExecutorService sweep, start()/stop()
└── ServerMain.java                         constructs + starts SessionWatchdog, Started record carries it

server/dao/
├── GameSessionDao.java                     + abandon(int sessionId, Integer winnerUserId),
│                                              currentTurnStartedAt(int sessionId)
└── JdbcGameSessionDao.java                 + abandon() (guarded UPDATE + applyEloAndRecordResult reuse),
                                              currentTurnStartedAt() (lean single-column query)

src/test/.../server/dao/InMemoryGameSessionDao.java   + abandon(), a parallel turnStartedAt map

common/enums/GameEventType.java             + SESSION_ABANDONED

client/communication/ServerConnection.java          + keepAlive(String)
client/communication/RmiJmsServerConnection.java    + keepAlive() -> authService.keepAlive()
admin/communication/AdminConnection.java            + keepAlive(String)
admin/communication/RmiJmsAdminConnection.java      + keepAlive() -> authService.keepAlive()
src/test/.../InMemoryServerConnection.java          + keepAlive() fake
src/test/.../InMemoryAdminConnection.java           + keepAlive() fake

client/logic/GameClientService.java         + 15s keepAlive timer after login; onSessionTopicEvent
                                              also accepts SESSION_ABANDONED
admin/logic/AdminClientService.java         + 15s keepAlive timer after login; onSessionTopicEvent
                                              also accepts SESSION_ABANDONED

client/presentation/GameBoardController.java   + plays a sound when currentTurnUserId == self

pom.xml                                     + javafx-media dependency (21.0.12)
src/main/resources/.../turn.wav (or similar)  new bundled asset
```

## Data flow

**Presence:** every authenticated RMI call (`makeMove`, `joinQueue`, `keepAlive`, etc.) resolves the caller's token through `SessionManager.resolve()`, which now also stamps `lastSeenByUserId`. A client that's actively playing stays "seen" purely as a side effect of play; a client that's idle-but-connected (waiting for the opponent) stays "seen" only because of its background 15s `keepAlive` ping.

**Sweep:** every 5s, `SessionWatchdog` asks `GameSessionDao.findAllActive()` for the current `ACTIVE` sessions, checks both participants' `lastSeen` and (via `currentTurnStartedAt()`) the current turn holder's start time against the two 60s thresholds, and for any session that trips either one, calls `GameSessionDao.abandon(sessionId, winnerId)`. On success, publishes `SESSION_ABANDONED` to `session.{id}.events`.

**Client side:** both players (already subscribed to the session's topic, per the existing "subscribe before anything else" rule) receive the `SESSION_ABANDONED` event and refresh their Game Board the same way a `SESSION_FORCE_ENDED` push already does — showing the game as ended, with a winner. The waiting-but-not-disconnected player's own 15s `keepAlive` ping is what kept them from being the one who gets abandoned in the first place.

**Turn sound:** whenever a fresh `GameStateDTO` reaches `GameBoardController` (from `makeMove`'s own return value, a pushed `MOVE_MADE`, or the initial matched state) and its `currentTurnUserId` is the logged-in player, play the clip.

## Wiring

- `ServerMain.startWithImpls()`: construct `SessionWatchdog(sessionManager, gameSessionDao, gameEventPublisher)`, call `.start()`, add it to the `Started` record.
- `pom.xml`: add `org.openjfx:javafx-media:21.0.12`.

## Testing

- **`SessionManagerTest`**: `lastSeen()` returns empty before any `resolve()` call, gets stamped on `resolve()`, updates on repeated calls.
- **`SessionWatchdogTest`** (new): against `InMemoryGameSessionDao`/`InMemoryGameEventPublisher`/a real `SessionManager` — a session with a silent participant gets abandoned with the other player winning and `SESSION_ABANDONED` published; a session with an expired `turnStartedAt` gets abandoned with the non-current-turn player winning; a healthy session (recent activity, fresh turn) is left untouched. Needs a way to inject a fake clock or a very short threshold for the test to run fast — decide the exact mechanism during implementation planning, not here.
- **`GameSessionDaoTest`** (Docker, `matchmaker_test`): extend for `abandon()` — happy path (status/winner/ELO all update correctly, mirroring the existing ELO-transaction test for `recordMove()`), and the guarded-update no-op case (session already finished).
- **`GameClientServiceTest`**/**`AdminClientServiceTest`**: extend for `keepAlive()` being callable, the periodic timer starting after login (may need a way to trigger a tick synchronously in test rather than waiting 15 real seconds), and `SESSION_ABANDONED` reaching the same listener `SESSION_FORCE_ENDED` already does.
- No automated test for the turn-sound feature, consistent with the rest of `client.presentation` — verify manually.
- **Manual verification**: the three real orphaned `ACTIVE` sessions already sitting in the dev database today are a ready-made disconnect case — confirm they get swept to `ABANDONED` (with *some* winner — whichever participant's `lastSeen` happens to still resolve, likely neither, so decide during implementation how `abandon()`/the sweep should behave if *both* participants are silent, since the doc above assumes exactly one is). Also manually verify a live two-client turn-timeout (sit on a turn past 60s) and confirm the turn-sound plays on the correct client when matched and after each opponent move.

## Out of scope (deferred, not forgotten)

- **Rematch** and **JMS broker-level authentication/authorization** — both deferred to be picked up after this lands; full detail already recorded in `docs/build-plan.md`'s "Next Steps" section.
- Live revocation of an already-open JMS connection when a session times out — not applicable here (this doc doesn't touch JMS auth at all, deferred alongside the broker-security work above).
- Any UI indicator *counting down* the turn or disconnect clock — this doc only covers detection + the sound cue on turn-start, not a visible timer widget.
