# Project Structure

A map of what lives where in this repository, and why. For *what's been built and what's next*, see `build-plan.md`. For the *why* behind specific design decisions, see the docs under `specs/` and `superpowers/`.

## Top level

```
matchmaker/
├── pom.xml                    Maven build file (Java 21, JUnit 5)
├── .gitignore                 target/, .superpowers/, IDE files
├── MatchMaker.v1 (1).docx     Original course-provided spec (Hebrew)
├── MatchMaker_Spec_EN.md      English translation of the spec
├── docker-compose.yml         Local MySQL for dev + the 4 DB-integration test classes
├── db/                        SQL schema/seed scripts applied to the Dockerized MySQL
├── docs/                      All project documentation (see below)
├── src/main/java/...          Production code
├── src/main/resources/        db.properties (JDBC URL/credentials for DataSourceFactory)
└── src/test/java/...          Tests (mirrors the main tree, package-for-package)
```

Build/run commands, from the project root (requires `JAVA_HOME` pointed at a JDK 21):
```bash
mvn compile     # compile only
mvn test        # compile + run all tests
```
`ServerMain` is a real runnable entry point once compiled. A bare `java -cp target/classes ...` no longer works — this branch added runtime dependencies (HikariCP, mysql-connector-j, jbcrypt) that aren't on that classpath, and there's no shade/assembly plugin bundling them. Instead, run it through Maven, which already knows the full dependency classpath:
```bash
mvn exec:java
```
(`exec-maven-plugin` is configured in `pom.xml` with `com.matchmaker.server.ServerMain` as the default main class, so no `-Dexec.mainClass` is needed.) Starts an RMI registry on port 1099 and binds `AuthService`, `PlayerService`, `AdminService`.

## `src/main/java/com/matchmaker/` — production code

### `common/` — shared between server and every client
Nothing in `common` has any real behavior — it's the *contract* both the server and every client (player, admin) compile against. Neither client exists yet (steps 8–9), but the contract is written so they can be built independently once the server side is ready.

- **`common/dto/`** — plain, `Serializable` data-holder classes that cross the network via RMI (and later JMS). Each mirrors either a database table (per the spec) or a request/response shape:
  - `UserDTO` — a user's public profile (no password field — that never leaves the server).
  - `MoveDTO` — one row of move history.
  - `GameStateDTO` — the live snapshot of a game session (board state, whose turn, winner if finished).
  - `LoginResultDTO` — bundles a `UserDTO` with a session token; the one deliberate exception to "no request/response wrapper objects," because `login()` genuinely returns two distinct things.
  - `GameTypeDTO` — a game type's catalog entry (name, board dimensions, etc.).
  - `ChatMessageDTO` — one in-game chat message.
- **`common/enums/`** — `GameStatus` (`ACTIVE`/`FINISHED`/`ABANDONED`), `QueueStatus` (`WAITING`/`MATCHED`/`CANCELLED`).
- **`common/exceptions/`** — the checked-exception hierarchy every RMI call can throw. `MatchmakerException` is the base; `AuthenticationException`, `UsernameTakenException`, `NotParticipantException`, `NotYourTurnException`, `IllegalMoveException`, `NotAdminException` all extend it. Checked (not `RuntimeException`) so they fit naturally alongside RMI's own mandatory `RemoteException`.
- **`common/rmi/`** — the three RMI remote interfaces, split by *who calls them*, not by internal concern:
  - `AuthService` — `register`/`login`/`keepAlive`. Shared by both the player and admin client (an admin is just a `User` row with `IsAdmin=true`).
  - `PlayerService` — the game loop: `listGameTypes`, `joinQueue`, `cancelQueue`, `makeMove`, `sendChatMessage`, `resign`, `rematch`, `getHistory`.
  - `AdminService` — admin actions: `listGameTypes`, `addGameType`, `listUsers`, `listActiveSessions`, `forceEndSession`.

### `server/` — the server process (the only side implemented so far)
- **`server/SessionManager.java`** — in-memory `token → userId` map. `AuthServiceImpl` issues tokens here on login; every other authenticated method resolves the caller's identity through it. Constructor-injected into every `*ServiceImpl` rather than a static/global map, so it's independently unit-testable.
- **`server/ServerMain.java`** — the real entry point. Wires one `SessionManager` into all three service implementations, starts an RMI registry, binds all three under their interface names.
- **`server/rmi/`** — the actual implementations of the three `common/rmi` interfaces:
  - `AuthServiceImpl` — genuinely working (not stubbed), backed by real DAO calls: `register()`/`login()` go through `UserDao` against MySQL, with `jbcrypt` hashing the password (never stored or compared in plaintext). The hardcoded test user from the RMI-skeleton milestone is gone. Real success *and* failure paths, since proving RMI's exception-crossing behavior mattered as much as proving a successful call.
  - `PlayerServiceImpl` — a split, not a uniform stub: `listGameTypes()`, `getHistory()`, `joinQueue()`, and `cancelQueue()` are real methods (the first two DAO-backed via `GameTypeDao`/`GameSessionDao`; the latter two delegate to `MatchmakingQueue`). The other four methods (`makeMove`, `sendChatMessage`, `resign`, `rematch`) are still deliberate stubs — each throws `UnsupportedOperationException` naming the future roadmap step that will implement it for real.
  - `AdminServiceImpl` — untouched by this branch; every method is still a deliberate stub, fully wired into the registry (proving the whole three-interface structure works) but throwing `UnsupportedOperationException`. Not a fake — nothing pretends to work that doesn't.
- **`server/dao/`** — the JDBC/DAO layer added in this branch: `UserDao`/`GameTypeDao`/`GameSessionDao` interfaces with `Jdbc*` implementations, a `DataSourceFactory` that lazily builds a shared HikariCP connection pool (so Docker-free tests never trigger a connection attempt), and `DaoException` (an unchecked wrapper around `SQLException` for anything that isn't a handled case like a duplicate key). Talks to the MySQL schema in `db/schema.sql` via plain JDBC — no ORM.
- **`server/matchmaking/`** — the matchmaking queue layer added in this (`matchmaking-queue`) branch, following the same interface + `Jdbc*` implementation pattern as `server/dao/`: `MatchmakingQueue` (interface) / `JdbcMatchmakingQueue` — pairs a caller with the longest-waiting opponent of the same game type and creates the `GameSession` row, all inside a single JDBC transaction, with `join()`/`cancel()` both `synchronized` on the instance so pairing is also protected by a JVM-level lock on top of that transaction. A test-only fake, `InMemoryMatchmakingQueue` (in `src/test/java/...`), lets `PlayerServiceImplTest` exercise `joinQueue`/`cancelQueue` without Docker, mirroring how `server/dao/` has `InMemory*Dao` fakes for the same reason.

Not present yet (future roadmap steps): `server/jms/` (step 6), `server/game/` (the `GameEngine`/`CheckersEngine` — step 7). `client/` (player, JavaFX — step 8) and `admin/` (admin client, JavaFX — step 9) packages don't exist yet either.

## `src/test/java/com/matchmaker/` — tests

Mirrors the main tree package-for-package. Four different kinds of test appear, deliberately:

1. **Plain unit tests** (`SessionManagerTest`, `AuthServiceImplTest`, `PlayerServiceImplTest`, `AdminServiceImplTest`, the exception hierarchy test) — call methods directly as ordinary Java objects, no networking at all. Fast, and they isolate business logic from RMI plumbing.
2. **Serialization round-trip tests** (`NewDtoSerializationTest`, `ExistingDtoSerializationTest`) — actually serialize a DTO to bytes and back (`ObjectOutputStream`/`ObjectInputStream`), the same mechanism RMI uses internally. Proves the DTOs are RMI-safe, not just that they compile.
3. **Real RMI integration tests** (`AuthServiceRmiIntegrationTest`, `ServerMainTest`) — start an actual `Registry` on a test-only port, look up a genuine stub via `Registry.lookup()`, and call through it. These are the tests that prove RMI itself works end-to-end, automatically, on every `mvn test` run — no manual two-terminal demo required to trust it. Both stay Docker-free: `AuthServiceRmiIntegrationTest` runs `AuthServiceImpl` against in-memory fake DAOs, and `ServerMainTest` relies on Hikari's lazy pool initialization (`DataSourceFactory` never opens a real connection just from being constructed).
4. **DB-integration tests** (`UserDaoTest`, `GameTypeDaoTest`, `GameSessionDaoTest`, `MatchmakingQueueTest`) — run real SQL against a real MySQL, started with `docker compose up -d` (see `docker-compose.yml` / `db/schema.sql`), clearing every table in `@BeforeEach` via a shared `TestDatabase.cleanAll(DataSource)` helper (`src/test/java/com/matchmaker/server/TestDatabase.java`) and inserting whatever fixture rows each test needs. These are the *only* tests in the whole suite that require Docker; everything else above (including the RMI integration tier and `ServerMainTest`) stays Docker-free.

## `docs/` — documentation

```
docs/
├── build-plan.md                                          Overall roadmap + current status (read this first)
├── project-structure.md                                   This file
├── specs/
│   └── 2026-08-05-contracts-design.md                     Design doc: the contracts milestone
└── superpowers/
    ├── plans/
    │   ├── 2026-08-05-contracts-implementation.md          Implementation plan: contracts
    │   ├── 2026-08-05-rmi-server-skeleton-implementation.md   Implementation plan: RMI server skeleton
    │   ├── 2026-08-07-jdbc-dao-layer-implementation.md     Implementation plan: JDBC/DAO layer
    │   └── 2026-08-08-matchmaking-queue-implementation.md  Implementation plan: matchmaking queue
    └── specs/
        ├── 2026-08-05-rmi-server-skeleton-design.md        Design doc: the RMI server skeleton milestone
        ├── 2026-08-07-jdbc-dao-layer-design.md             Design doc: the JDBC/DAO layer milestone
        └── 2026-08-08-matchmaking-queue-design.md          Design doc: the matchmaking queue milestone
```

Each completed (or in-progress) milestone has this same pair: a **design doc** (`specs/` — the *why*, decisions and rationale, written and agreed before any code) and an **implementation plan** (`superpowers/plans/` — the *how*, broken into bite-sized, TDD-ordered tasks). The `superpowers/` subfolder name comes from the workflow used to produce those docs (brainstorm → plan → subagent-driven implementation with review); it's just a naming artifact of that process, not a meaningful structural distinction from `specs/` at the top level.

## Quick orientation: "where do I add X?"

| Adding... | Goes in |
|---|---|
| A new field that needs to travel over RMI/JMS | `common/dto/` |
| A new kind of failure a remote call can throw | `common/exceptions/` (extend `MatchmakerException`) |
| A new remote method | The relevant interface in `common/rmi/`, then its implementation in `server/rmi/` |
| Real (non-stub) logic for an existing `PlayerService`/`AdminService` method | `server/rmi/PlayerServiceImpl.java` or `AdminServiceImpl.java` — replace the `UnsupportedOperationException` |
| Database access | `server/dao/` — add a `*Dao` interface + `Jdbc*Dao` implementation, following `UserDao`/`JdbcUserDao` |
| Matchmaking/queue logic | `server/matchmaking/` — `MatchmakingQueue`/`JdbcMatchmakingQueue`, following the same interface + `Jdbc*` implementation pattern as `server/dao/` |
| Game rule logic | Not yet created — will be `server/game/` (step 7) |
| Player-facing UI | Not yet created — will be a `client/` package (step 8, JavaFX) |
| Admin-facing UI | Not yet created — will be an `admin/` package (step 9, JavaFX) |
