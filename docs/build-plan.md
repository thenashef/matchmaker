# MatchMaker – Build Plan

## Context
This is a from-scratch final project for an Advanced Java course (see `../MatchMaker_Spec_EN.md` for the full functional/DB/UI spec). No code exists yet. The goal here is twofold: (1) lay out the full sequence of steps to build the system, and (2) start actual coding with the RMI contract layer, since RMI (together with JMS) is the architectural backbone the whole system — and likely the course grading — depends on. Getting the client/server "contract" right first lets every later piece (DB, JMS, UI) be built against a stable interface instead of guessed at.

## Assumptions (flag if wrong)
- Build tool: **Maven**, single project (not multi-module) — simplest to manage/submit for a course project.
- Java: JDK 21 (installed via Homebrew) + Maven 3.9 — confirmed working (`java -version`, `mvn -v`).
- One Maven project containing multiple runnable `main` classes: `ServerMain`, `ClientMain` (JavaFX), `AdminMain` (JavaFX), rather than splitting into separate Maven modules. Keeps the course submission simple; can be split later if it gets unwieldy.
- MySQL and ActiveMQ are not required to be running yet for the first milestone (RMI only, no DB/JMS wiring yet).

## Working style (important)
This is the user's own course project — they need to understand and agree with every piece of code, not receive a batch of files they didn't review. **One file/concept at a time.** For each piece, explain what it does and why and show the intended content in chat first; the user reviews/pushes back/asks questions; only after agreement does it get written to disk. New work goes through brainstorming (design doc) → writing-plans (implementation plan) → subagent-driven execution with a task review after every task and a final whole-branch review before merge.

## Full Roadmap (in build order)

1. **Project setup** — Maven project, folder/package skeleton, dependencies (JavaFX, MySQL Connector/J, ActiveMQ client), `.gitignore`, git init.
2. **Shared contracts (`common` package)** — DTOs that will travel over RMI and JMS (`UserDTO`, `MoveDTO`, `GameStateDTO`, `ChatMessageDTO`), enums (`GameStatus`, `QueueStatus`), and the RMI remote interfaces (`AuthService`, `PlayerService`, `AdminService`).
3. **RMI server skeleton** — implement the remote interfaces, start an RMI registry, bind the services, and prove connectivity with a real RMI integration test (no real logic yet, just echo/ping-style calls).
4. **JDBC/DAO layer** — create the MySQL schema (6 tables per spec), write `UserDao` first, wire real `register`/`login` behavior into the RMI service.
5. **Matchmaking logic** — `MatchmakingQueue` handling with synchronized/atomic pairing; creates a `GameSession` row when two players match.
6. **JMS setup** — ActiveMQ connection, one topic per game session, server-side producer; a minimal standalone consumer to prove messages arrive before touching the UI. ← **next focus**
7. **Game engine** — `GameEngine` interface (`isLegalMove`, `applyMove`, `checkWinner`) with `CheckersEngine` as the first implementation; wire into the RMI `makeMove` call; persist `Move` rows and `BoardState`.
8. **Player client (JavaFX)** — Login/Register → Lobby → Matchmaking wait → Game board, wired to RMI (commands) + JMS (push updates).
9. **Admin client (JavaFX)** — Dashboard, Add Game Type, Live Session Monitor; RMI for actions, read-only JMS subscription for live monitoring.
10. **Edge cases** — `keepAlive`/disconnect handling → `ABANDONED` state, turn timeout, Rematch, per-session authorization checks (only participants + only the player whose turn it is can act).
11. **Testing & polish** — manual multi-client runs, error handling, demo/packaging prep.

## What's Implemented So Far (steps 1–5, merged to `main`)

**What "done" looks like:** a server process is running, exposes bound RMI remote objects, and a separate client can look them up over RMI and successfully call a method on them — proving the client/server wiring works before any real feature is built on top. That's working today.

The design and implementation here ended up superseding the plan originally sketched for this milestone: instead of a single `MatchmakerService.java` interface, the actual contract split into three interfaces (`AuthService`, `PlayerService`, `AdminService`) — see `docs/specs/2026-08-05-contracts-design.md` for that design and `docs/superpowers/plans/2026-08-05-contracts-implementation.md` for how it was implemented. Instead of a throwaway `RmiTestClient.java` `main` method, RMI connectivity is proven by `AuthServiceRmiIntegrationTest` — a real, automated integration test that stands up an RMI registry, binds a real `AuthServiceImpl`, and calls it through a genuine looked-up stub — see `docs/superpowers/specs/2026-08-05-rmi-server-skeleton-design.md` for that design and `docs/superpowers/plans/2026-08-05-rmi-server-skeleton-implementation.md` for how it was implemented.

### Milestone 1 — Contracts (`common` package)
- **Enums:** `GameStatus` (`ACTIVE`/`FINISHED`/`ABANDONED`), `QueueStatus` (`WAITING`/`MATCHED`/`CANCELLED`).
- **DTOs** (all `Serializable`, plain classes — private final fields, one constructor, getters only): `UserDTO`, `MoveDTO`, `GameStateDTO`, `LoginResultDTO`, `GameTypeDTO`, `ChatMessageDTO`.
- **Exceptions** (all checked, all extend `MatchmakerException`): `AuthenticationException`, `UsernameTakenException`, `NotParticipantException`, `NotYourTurnException`, `IllegalMoveException`, `NotAdminException`.
- **RMI interfaces:** `AuthService` (register/login/keepAlive — shared by both client types), `PlayerService` (game-loop calls), `AdminService` (admin actions).
- Full design rationale: `docs/specs/2026-08-05-contracts-design.md`.

### Milestone 2 — RMI server skeleton (`server` package)
- **`SessionManager`** — in-memory `token → userId` map; issues and resolves session tokens.
- **`AuthServiceImpl`** — real (not stubbed) auth logic against one hardcoded test user (`username="test"`, `password="test1234"`) — register/login/keepAlive all have genuine success *and* failure paths.
- **`PlayerServiceImpl` / `AdminServiceImpl`** — structurally complete (implement every interface method, correctly bound in the registry), but every method currently throws `UnsupportedOperationException` naming the future roadmap step that implements it — deliberate stubs, not fakes.
- **`ServerMain`** — starts a real RMI registry on port 1099 and binds all three services.
- **Tests:** unit tests for `SessionManager`/`AuthServiceImpl`/the two stub classes (no networking involved), plus two *real* RMI integration tests — `AuthServiceRmiIntegrationTest` and `ServerMainTest` — that stand up an actual registry, look up genuine stubs, and call through them.
- Full design rationale: `docs/superpowers/specs/2026-08-05-rmi-server-skeleton-design.md`.

### Milestone 3 — JDBC/DAO layer (`server.dao` package)
- **`db/schema.sql` + `docker-compose.yml`** — the real MySQL schema (6 tables per `../MatchMaker_Spec_EN.md`: `User`, `GameType`, `GameSession`, `Move`, `MatchmakingQueue`, `ChatMessage`), plus a `docker compose` service for a local dev MySQL that auto-applies the schema on first boot.
- **`DataSourceFactory`** — builds a HikariCP-pooled `DataSource`; deliberately lazy-init, so constructing it never blocks or fails even without a live database — connections are only attempted when a DAO actually runs a query.
- **`UserDao`/`JdbcUserDao`, `GameTypeDao`/`JdbcGameTypeDao`, `GameSessionDao`/`JdbcGameSessionDao`** — real JDBC DAOs (all in `com.matchmaker.server.dao`) backing users, game types, and game sessions.
- **`AuthServiceImpl.register()`/`.login()`** are now real: DAO-backed, with passwords bcrypt-hashed via jBCrypt — the old hardcoded test user is gone entirely.
- **`PlayerServiceImpl.listGameTypes()` and `.getHistory()`** are now real and DAO-backed — the first two `PlayerServiceImpl` methods to graduate off the `UnsupportedOperationException` stub pattern.
- **`ServerMain`** wired to the real JDBC DAOs via a single shared `DataSourceFactory`-built connection pool.
- **Tests:** a fourth test tier — DB-integration tests (`UserDaoTest`, `GameTypeDaoTest`, `GameSessionDaoTest`) that run real SQL against a real Docker MySQL. `ServerMainTest` and `AuthServiceRmiIntegrationTest` remain Docker-free (in-memory fake DAOs + Hikari's lazy pool init), so the "Docker-free except 3 DAO tests" design goal was achieved.
- Full design rationale: `docs/superpowers/specs/2026-08-07-jdbc-dao-layer-design.md` and `docs/superpowers/plans/2026-08-07-jdbc-dao-layer-implementation.md`.

### Milestone 4 — Matchmaking queue (`server.matchmaking` package)
- **`MatchmakingQueue`** (interface) / **`JdbcMatchmakingQueue`** — pairs a caller with the longest-waiting opponent of the same game type and creates the `GameSession` row, all inside a single JDBC transaction spanning the `MatchmakingQueue` and `GameSession` tables. `join()`/`cancel()` are also `synchronized`, so pairing is protected by one JVM-level lock on top of that transaction — not by DB constraints alone.
- **Atomicity is proven, not just asserted:** `MatchmakingQueueTest` includes a real multi-threaded test — three concurrent `join()` calls fired via `ExecutorService` + `CountDownLatch` — that asserts exactly one pairing happens and exactly one caller is left waiting.
- **`join()` is idempotent** — a user calling it twice before an opponent shows up doesn't create a duplicate `MatchmakingQueue` row (its own dedicated test).
- **`PlayerServiceImpl.joinQueue()` and `.cancelQueue()`** are now real, DAO-backed — the 3rd and 4th `PlayerServiceImpl` methods to graduate off the `UnsupportedOperationException` stub pattern, after `listGameTypes()`/`getHistory()` from Milestone 3.
- **A breaking RMI interface change:** `PlayerService.joinQueue()`'s return type changed from `void` to a nullable `GameStateDTO` — `null` means queued with no opponent yet, non-null means matched immediately. This is necessary because JMS (the async server→client push mechanism) doesn't exist yet, so this return value is currently the *only* way a client learns it was matched instantly; the player who was already waiting has no equivalent notification yet — closing that gap is exactly what step 6 (JMS) is for.
- **`ServerMain`** wired to a real `JdbcMatchmakingQueue` off the same shared `DataSourceFactory` pool.
- **Tests:** `MatchmakingQueueTest` runs real SQL against Docker MySQL (queue-then-wait, opponent-already-waiting, idempotent double-join, cancel, and the 3-way concurrent join). `PlayerServiceImplTest` covers `joinQueue`/`cancelQueue` against an `InMemoryMatchmakingQueue` test fixture, so that tier stays Docker-free.
- Full design rationale: `docs/superpowers/specs/2026-08-08-matchmaking-queue-design.md` and `docs/superpowers/plans/2026-08-08-matchmaking-queue-implementation.md`.

**Current state:** 54/54 tests passing (with `docker compose up -d` running), `mvn compile`/`mvn test` both clean. See `docs/project-structure.md` for the full file-by-file layout.

## Next Steps

**Immediate next focus — step 6, JMS setup:**
- ActiveMQ connection, one topic per game session, a server-side producer.
- A minimal standalone consumer to prove messages arrive before touching the UI.
- First real use: notifying the player who was already queued the moment `MatchmakingQueue.join()` pairs them with someone else — closing the gap Milestone 4 deliberately left open (the already-waiting player currently has no way to learn about a match, since their own `joinQueue()` call already returned `null` before the match happened).

**After that, in roadmap order** (steps 7–11 above): the game engine (`GameEngine` interface + `CheckersEngine`, wired into `makeMove`), the JavaFX player client, the JavaFX admin client, then the edge-case handling (disconnect detection, turn timeouts, Rematch, authorization checks), and finally testing/polish.

Each of these gets the same treatment the first four milestones did: a design doc (brainstorming), an implementation plan (writing-plans), then subagent-driven execution with per-task review and a final whole-branch review before merge.

## Verification
- `mvn compile` succeeds with no errors.
- `mvn test` passes (54/54, with `docker compose up -d` running), including `AuthServiceRmiIntegrationTest` and `ServerMainTest`, which prove the RMI round-trip works end to end (registry lookup, real stub, real method call) without any manual two-process run required.
- `docker compose up -d` must be running before `UserDaoTest`, `GameTypeDaoTest`, `GameSessionDaoTest`, or `MatchmakingQueueTest` — these four run real SQL against a real MySQL. Every other test (including `ServerMainTest` and `AuthServiceRmiIntegrationTest`) remains Docker-free.
- `ServerMain` remains a real, manually-runnable entry point (console confirms the registry started and all three services are bound) for demoing against a real client later. Run it with `mvn exec:java` (via `exec-maven-plugin`, configured in `pom.xml`) — not `java -cp target/classes ...`, which no longer works now that runtime deps (HikariCP, mysql-connector-j, jbcrypt) aren't on that bare classpath. `ServerMain.main()` blocks on `Thread.currentThread().join()` after printing its banner, so the process (and port 1099) stays up until you Ctrl-C it rather than exiting the moment `main()` returns.
- `db/schema.sql` now seeds one `GameType` row (Checkers, 2 players, 8x8 board) on first boot of a fresh Docker volume, so `PlayerServiceImpl.listGameTypes()` has something to return out of the box.
