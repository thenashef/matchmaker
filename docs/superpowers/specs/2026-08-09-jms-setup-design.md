# Design: JMS Setup (Roadmap Step 6)

## Context

Steps 1–5 are merged to `main`: contracts, RMI server skeleton, JDBC/DAO layer, and a real matchmaking queue (`JdbcMatchmakingQueue`). `PlayerServiceImpl.joinQueue()`/`.cancelQueue()` are real and DAO-backed; `makeMove`/`sendChatMessage`/`resign`/`rematch` are still deliberate `UnsupportedOperationException` stubs.

Milestone 4 deliberately left a gap: `joinQueue()`'s return value (`GameStateDTO` or `null`) is currently the *only* way a client learns it was matched. The player who called `join()` and got matched immediately learns synchronously through that return value — but the player who was already queued, waiting, has no equivalent notification, since their own `joinQueue()` call already returned `null` before the match happened. Closing that gap is this step's actual goal.

Spec section 3 ("Communication Protocols") is explicit about the mechanism: *"JMS – for asynchronous messages from the server to the client... A message queue for each player and a topic for each game session provide this naturally."* and section (topic details): *"For each game session a dedicated topic is opened, which both players of the session subscribe to; the topic is created at the moment of pairing and closed when the game ends."*

## Decisions

Worked through with the user, compressed but real:

1. **Embedded ActiveMQ broker (`vm://localhost`), not a standalone Docker service.** No separate process to start, no new docker-compose service — the broker starts in-process on first connection. Keeps this step Docker-free, matching how the RMI integration tier already avoids Docker.
2. **A generic `GameEventDTO` envelope now, not a one-off `MatchFoundMessage`.** Fields: `GameEventType type`, `int sessionId`, `GameStateDTO gameState`. Only `GameEventType.MATCH_FOUND` exists today; `MOVE_MADE`/`CHAT_MESSAGE`/`GAME_ENDED` get added in steps 7/10 when there's real logic to back them, not stubbed in ahead of time. This avoids inventing a second message shape later while still following YAGNI on the enum's members.
3. **Per-player queue only — no per-session topic yet.** The spec describes two destination types (a personal queue per player, a broadcast topic per session), but only the personal queue is needed to close this step's actual gap: notifying a player who has no in-flight RMI call to receive a push through. The per-session topic's first real use is step 7 (broadcasting a move to both players); building it now would mean shipping a destination with zero callers. Naming convention reserved for step 7: `session.{sessionId}.events`.
4. **Publish is triggered from `PlayerServiceImpl.joinQueue()`, not from inside `JdbcMatchmakingQueue`.** Keeps `JdbcMatchmakingQueue` purely DB/transaction-focused (matching its existing class-level design rationale around commit-before-unlock correctness) and keeps the JMS dependency out of that class entirely. `PlayerServiceImpl` already sits at the point where both the DB result and the RMI-call context (who the caller is) are available.
5. **Publish failures are logged, not propagated.** If `publishToPlayer()` throws, the matched pairing has already committed to the DB — failing the calling player's `joinQueue()` RMI call over a notification failure aimed at the *other* player would be wrong. The unmatched player is left exactly as before this step existed (waiting, undiscoverable) rather than in some new broken state. No retry/redelivery mechanism is being built here; that's out of scope.

## Architecture

New package `com.matchmaker.server.jms` — reserved by `project-structure.md` for this step. Follows the same interface + real-implementation pattern as `server/dao/` and `server/matchmaking/`:

- **`GameEventPublisher`** (interface): `void publishToPlayer(int userId, GameEventDTO event)`.
- **`ActiveMqGameEventPublisher`** (real implementation): holds a `javax.jms.Session`, resolves a `Queue` named `player.{userId}.events` per call, sends `event` as an `ObjectMessage`.
- **`JmsConnectionFactory`**: mirrors `DataSourceFactory`. Static `create()` builds an `ActiveMQConnectionFactory` against `vm://localhost?broker.persistent=false` and returns a started `javax.jms.Connection`. Non-persistent — this is an in-memory, single-JVM course project; no durability guarantee is required at this stage.

New shared contracts (`common/`):

- **`common/enums/GameEventType`**: enum, one value — `MATCH_FOUND`.
- **`common/dto/GameEventDTO`** (`Serializable`, same shape as the other DTOs — private final fields, one constructor, getters only): `type`, `sessionId`, `gameState`.

New unchecked exception: **`JmsPublishException`** (in `server/jms/`), wrapping `javax.jms.JMSException` — same role as `DaoException` wrapping `SQLException`.

## Data flow

1. Player A calls `joinQueue()` → no opponent found → `matchmakingQueue.join()` enqueues A's row, returns `null`. `PlayerServiceImpl` returns `null` to A, unchanged from today.
2. Player B calls `joinQueue()` → `matchmakingQueue.join()` finds A waiting, pairs them, commits the `GameSession` row, returns B's own `GameStateDTO`.
3. Still inside B's `joinQueue()` call (same thread, same RMI invocation — not a separate later event): since the result is non-null, `PlayerServiceImpl` determines the *other* participant's id (whichever of `player1Id`/`player2Id` isn't the caller) and calls `gameEventPublisher.publishToPlayer(otherUserId, new GameEventDTO(MATCH_FOUND, sessionId, result))`.
4. That message lands on A's queue (`player.{A}.events`). No consumer exists yet in this step (no client built until step 8) — proven instead by an automated integration test (see Testing).
5. B's `joinQueue()` call returns B's `GameStateDTO`, unchanged from today, regardless of whether step 3's publish succeeded.

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
            // Logged, not propagated -- see Decisions #5. The pairing already committed;
            // failing to notify the *other* player shouldn't fail this caller's own result.
        }
    }

    return result;
}
```

## Wiring

- `PlayerServiceImpl`: constructor gains a `GameEventPublisher gameEventPublisher` parameter (now `SessionManager, GameSessionDao, GameTypeDao, MatchmakingQueue, GameEventPublisher`).
- `ServerMain`: builds a `Connection` via `JmsConnectionFactory.create()`, starts it, opens one shared `Session`, wraps it in `ActiveMqGameEventPublisher`, passes it into `PlayerServiceImpl`.

## Testing

Extends the existing test-tier pattern:

- **`GameEventPublisherJmsIntegrationTest`** (new, Docker-free) — this step's "standalone consumer" proof, as a real automated test rather than a manual demo: starts the embedded broker, subscribes a genuine `javax.jms.MessageConsumer` to a player's queue, calls `publishToPlayer()`, asserts the consumer receives the `ObjectMessage` with the expected `GameEventDTO`. Same role as `AuthServiceRmiIntegrationTest` — proves the real mechanism end-to-end on every `mvn test`.
- **`PlayerServiceImplTest`** (existing file, extended): a new `InMemoryGameEventPublisher` test fake (mirrors `InMemoryMatchmakingQueue`) lets it assert (a) a successful match calls `publishToPlayer()` with the opponent's id and a correctly-populated `MATCH_FOUND` event, and (b) a `null` result (still waiting) never calls `publishToPlayer()`.
- **`GameEventDTO` serialization round-trip** — added alongside the existing `NewDtoSerializationTest`/`ExistingDtoSerializationTest` coverage.

No Docker required anywhere in this step — the embedded `vm://localhost` broker keeps the whole JMS tier in the same Docker-free bucket as the RMI integration tests.

## Out of scope (deferred, not forgotten)

- **Per-session topic** (`session.{sessionId}.events`) for broadcasting moves/chat/game-end to both players. First real caller is step 7 (`makeMove`).
- **Topic/queue teardown.** The spec says a session's topic is "closed when the game ends," but there's no game-end event to hook into yet (step 7 for winners, step 10 for abandon/timeout). Revisit then.
- **Retry/redelivery for failed notifications.** A publish failure is logged and the affected player is left exactly as undiscoverable as they'd have been before this step — no reconciliation job is being built.
- **Client-side consumer.** No player/admin client exists until steps 8–9; this step only proves the server-side publish path works.
