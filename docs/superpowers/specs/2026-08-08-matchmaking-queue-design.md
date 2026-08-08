# Design: Matchmaking Queue (Roadmap Step 5)

## Context

Steps 1–4 are merged to `main`: contracts, RMI server skeleton, and a MySQL-backed JDBC/DAO layer. `AuthServiceImpl` and `PlayerServiceImpl.listGameTypes()`/`.getHistory()` are real; every other `PlayerServiceImpl` method still throws `UnsupportedOperationException`. This is roadmap step 5: implement `PlayerServiceImpl.joinQueue()`/`.cancelQueue()` for real, backed by the `MatchmakingQueue` table, with atomic opponent pairing that creates a `GameSession` row.

Spec section 5 ("Handling Edge Cases and Consistency") is explicit about the core requirement: *"pairing is carried out by a single, synchronized matchmaking component. Within a single transaction it takes the waiting player, creates the game session, and removes both records from the queue. This prevents a situation where two threads pair the same player twice, and guarantees correct support for multiple pairs playing simultaneously."*

## Decisions

Worked through with the user, compressed but real:

1. **`PlayerService.joinQueue()` changes from `void` to a nullable `GameStateDTO`.** With JMS (async server→client push) not built until step 6, this call's own return value is the only way a client can learn "you were matched" — a player who calls `join()` before an opponent shows up has no return value to relay a later match (that's step 6's job: pushing the notification to whichever client is already waiting). `null` return means "queued, no opponent yet"; non-null means "matched, here's the new session." This is a deliberate change to an already-established RMI interface (Milestone 1), not additive-only.
2. **Matched (and cancelled) queue rows are deleted, not marked `Status=MATCHED`/`CANCELLED` and kept.** Matches the spec's literal wording ("removes both from the queue"); nothing in the spec's screens reads historical queue entries. The `MatchmakingQueue.Status` enum column still exists in the schema (unused beyond `WAITING` in practice) since it's part of the given spec's table definition — not worth a schema change for this milestone.
3. **Atomicity via Java's own `synchronized`, not DB-level row locking.** The spec's own wording ("a single, synchronized matchmaking component") points at this directly, and it's simpler to reason about than `SELECT ... FOR UPDATE` isolation-level tuning for a course project. This relies on the server being a single, non-clustered JVM process — already assumed everywhere in this codebase (`SessionManager`'s in-memory token map has the identical assumption).

## Architecture

New package `com.matchmaker.server.matchmaking` — the name `project-structure.md` already reserved for this step. Follows the exact interface + `Jdbc*` implementation + test-only in-memory fake pattern established by the DAOs in step 4:

- **`MatchmakingQueue`** (interface): `GameStateDTO join(int userId, int gameTypeId)`, `void cancel(int userId)`.
- **`JdbcMatchmakingQueue`** (real implementation): holds a `DataSource` directly and owns its own raw JDBC transaction spanning both the `MatchmakingQueue` and `GameSession` tables. This deliberately does *not* go through `GameSessionDao` — that DAO has no `insert()` yet (scoped out of step 4 on purpose), and this pairing operation is a genuinely self-contained atomic unit; routing it through a cross-DAO transaction seam would be more machinery than a two-table insert-and-delete needs.
- **`InMemoryMatchmakingQueue`** (test-only fake, `src/test/java/.../matchmaking/`): keeps `PlayerServiceImplTest` Docker-free, same role as `InMemoryUserDao` etc.

Both `join()` and `cancel()` on `JdbcMatchmakingQueue` are `synchronized` instance methods. `ServerMain` constructs exactly one `MatchmakingQueue` for the whole process and injects it into `PlayerServiceImpl`, so every RMI-invoked call — from any connected client — funnels through the same monitor lock. `cancel()` is synchronized on the same lock as `join()` deliberately: without that, a `join()` call mid-transaction (opponent found, about to delete their queue row) could race against that same opponent's own `cancel()` call and pair them into a session they thought they'd backed out of.

## `join()` logic — one transaction

1. `SELECT` the oldest `WAITING` row for this `gameTypeId` where `UserID != userId` (`ORDER BY JoinedAt ASC LIMIT 1`).
2. **Found one** → `INSERT` a new `GameSession`: `Player1`=the waiting user (arrived first, moves first), `Player2`=the caller, `Status=ACTIVE`, `CurrentTurnUserID`=Player1, `TurnStartedAt=now`, `BoardState=null` (step 7's game engine initializes the real board later — not matchmaking's concern). `DELETE` the opponent's queue row — the caller never had one to begin with, since `join()` checks for an opponent *before* ever inserting its own row, so only one row ever existed for this pair. Commit. Return a `GameStateDTO` built from the values just inserted (including the generated session ID via `Statement.RETURN_GENERATED_KEYS` — a genuine use this time, since the ID is needed immediately for the return value, unlike step 4's `JdbcUserDao` which needed a full re-select for server-computed defaults).
3. **Found none** → `INSERT` a new `WAITING` row for the caller (`UserID`, `GameTypeID`, `Status=WAITING`, `JoinedAt=now`). Commit. Return `null`.

`cancel(userId)` deletes the caller's own `WAITING` row if one exists; silent no-op otherwise (no exception for "you weren't queued").

Any `SQLException` during the transaction rolls it back and is wrapped in the existing `DaoException` (`com.matchmaker.server.dao.DaoException` — reused here even though `JdbcMatchmakingQueue` lives outside the `dao` package; it's a general "unexpected DB failure" wrapper, not DAO-specific by construction).

## Wiring

- `common/rmi/PlayerService.java`: `joinQueue()` return type changes `void` → `GameStateDTO`.
- `PlayerServiceImpl`: constructor gains a `MatchmakingQueue matchmakingQueue` parameter (now `SessionManager, GameSessionDao, GameTypeDao, MatchmakingQueue`). `joinQueue()`: resolve session → `matchmakingQueue.join(userId, gameTypeId)` → return result. `cancelQueue()`: resolve session → `matchmakingQueue.cancel(userId)`.
- `ServerMain`: builds one `JdbcMatchmakingQueue` from the already-shared `DataSource`, passes it into `PlayerServiceImpl`.

## Testing

Extends the existing four-tier pattern:

- **`MatchmakingQueueTest`** (real MySQL — joins `UserDaoTest`/`GameTypeDaoTest`/`GameSessionDaoTest` as the 4th Docker-required class): sequential `join()` (first call → `null`, row inserted; second call for a different user, same game type → matched, `GameSession` fields correct, both queue rows genuinely gone); `cancel()` removes a waiting row and is a no-op when nothing's queued.
- **A real concurrency test**, also in `MatchmakingQueueTest`: 3 users, 3 threads, all calling `join()` for the same `gameTypeId` at once (via `ExecutorService` + a `CountDownLatch` to maximize actual overlap). Asserts: exactly one thread's result is non-null (matched), the other two are `null`, and exactly one `MatchmakingQueue` row remains afterward. This is the actual proof of the atomicity claim — not an inspection of the `synchronized` keyword, a real race exercised and observed to resolve correctly.
- `PlayerServiceImplTest`: `joinQueue`/`cancelQueue` graduate off the stub list, using `InMemoryMatchmakingQueue`. The remaining-stubs aggregate test shrinks to `makeMove`/`sendChatMessage`/`resign`/`rematch`.
- No changes needed to `AuthServiceRmiIntegrationTest` or `ServerMainTest` — neither touches `PlayerService` methods.

## Out of scope (deferred, not forgotten)

- **Notifying the first-queued player when a second one joins later.** That's JMS (step 6) — this milestone proves the pairing/persistence logic works; it doesn't push anything to a client.
- **Validating `gameTypeId` exists before queuing.** No client exists yet to send an untrusted one, and `listGameTypes()` is the only current source of IDs. An invalid ID today would surface as a raw `DaoException`-wrapped foreign-key violation rather than a clean domain exception — acceptable for now, revisit if/when a real client can send arbitrary input.
- **`BoardState` initialization.** Left `null` by matchmaking; step 7's game engine sets it up when wiring `makeMove`.
