# Design: JDBC/DAO Layer (Roadmap Step 4)

## Context

Milestones 1–2 (contracts, RMI server skeleton) are merged. `AuthServiceImpl` currently authenticates against one hardcoded test user (`test`/`test1234`); `PlayerServiceImpl` and `AdminServiceImpl` are structurally complete but every method throws `UnsupportedOperationException`. This is roadmap step 4: replace the hardcoded auth with a real MySQL-backed implementation, and graduate `PlayerServiceImpl.getHistory()` and `PlayerServiceImpl.listGameTypes()` off the stub pattern.

`listGameTypes()`'s existing stub message pointed at "step 4" even though the build-plan's step-4 description didn't originally list it. Confirmed with the user this was a mislabeling, not intentional — `listGameTypes()` is folded into this milestone. (`AdminServiceImpl.listGameTypes()`/`addGameType()` correctly point at step 9 and are unaffected.)

Database choice: **MySQL**, per `MatchMaker_Spec_EN.md` §2 and §6 (explicit in the spec, not just an assumption). The user will run MySQL locally via Docker.

## Decisions

These were worked through with the user one at a time; each includes the reasoning, not just the choice.

1. **Schema creation: a plain `schema.sql` file**, not a migration tool (Flyway, etc.). Simplest option, zero new concepts on top of RMI/JMS/JDBC already in flight this project. Migration tooling was rejected as unnecessary process for a single-developer, single-environment course project.
2. **Connections: HikariCP connection pool**, not raw `DriverManager` per call. The user chose the "correct production pattern" over the simpler option; it's one well-known dependency.
3. **Config: a checked-in `db.properties`** (not env vars, not hardcoded constants). Standard Java pattern. Checked in directly (no `.example` template / gitignore split) because the credentials aren't a real secret — they only unlock a local, solo-dev Docker container, matching whatever `docker-compose.yml` sets.
4. **Password hashing: jBCrypt** (`org.mindrot:jbcrypt`), matching the spec's own suggestion ("hashed with salt, e.g. bcrypt"). Rejected hand-rolled `javax.crypto`/PBKDF2 — more code, no benefit, easier to get subtly wrong.
5. **DAO integration tests run against real Docker MySQL**, not Testcontainers. The user preferred this over the more "self-contained" Testcontainers option, accepting the tradeoff documented below in Testing.
6. **`GameSessionDao` is minimal for this milestone**: one method, only what `getHistory()` needs. No speculative CRUD. Matches the project's existing pattern of `PlayerServiceImpl`/`AdminServiceImpl` methods being deliberately left as stubs until their roadmap step arrives.
7. **`docker-compose.yml`** added at the repo root (user's request, mid-design) — auto-runs `schema.sql` on first container start via MySQL's `/docker-entrypoint-initdb.d/` convention, so there's no separate manual schema-load step.

## Schema (`db/schema.sql`)

All 6 tables per spec §7, with real foreign keys and `ENUM` status columns:

```sql
CREATE TABLE User (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    Username VARCHAR(50) NOT NULL UNIQUE,
    Password VARCHAR(255) NOT NULL,
    IsAdmin BOOLEAN NOT NULL DEFAULT FALSE,
    Wins INT NOT NULL DEFAULT 0,
    Losses INT NOT NULL DEFAULT 0,
    Draws INT NOT NULL DEFAULT 0,
    Rating INT NOT NULL DEFAULT 1200,
    CreatedAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE GameType (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    Name VARCHAR(100) NOT NULL,
    Description TEXT,
    MinPlayers INT NOT NULL,
    MaxPlayers INT NOT NULL,
    BoardRows INT NOT NULL,
    BoardCols INT NOT NULL
);

CREATE TABLE GameSession (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    GameTypeID INT NOT NULL,
    Player1ID INT NOT NULL,
    Player2ID INT NOT NULL,
    Status ENUM('ACTIVE','FINISHED','ABANDONED') NOT NULL DEFAULT 'ACTIVE',
    CurrentTurnUserID INT,
    TurnStartedAt DATETIME,
    WinnerID INT,
    BoardState TEXT,
    StartTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    EndTime DATETIME,
    FOREIGN KEY (GameTypeID) REFERENCES GameType(ID),
    FOREIGN KEY (Player1ID) REFERENCES User(ID),
    FOREIGN KEY (Player2ID) REFERENCES User(ID),
    FOREIGN KEY (CurrentTurnUserID) REFERENCES User(ID),
    FOREIGN KEY (WinnerID) REFERENCES User(ID)
);

CREATE TABLE Move (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    SessionID INT NOT NULL,
    UserID INT NOT NULL,
    MoveNumber INT NOT NULL,
    Payload TEXT NOT NULL,
    CreatedAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (SessionID) REFERENCES GameSession(ID),
    FOREIGN KEY (UserID) REFERENCES User(ID)
);

CREATE TABLE MatchmakingQueue (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    UserID INT NOT NULL,
    GameTypeID INT NOT NULL,
    Status ENUM('WAITING','MATCHED','CANCELLED') NOT NULL DEFAULT 'WAITING',
    JoinedAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (UserID) REFERENCES User(ID),
    FOREIGN KEY (GameTypeID) REFERENCES GameType(ID)
);

CREATE TABLE ChatMessage (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    SessionID INT NOT NULL,
    UserID INT NOT NULL,
    Content TEXT NOT NULL,
    SentAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (SessionID) REFERENCES GameSession(ID),
    FOREIGN KEY (UserID) REFERENCES User(ID)
);
```

Only `User`, `GameType`, and `GameSession` are read/written by code in this milestone. `Move`, `MatchmakingQueue`, and `ChatMessage` are created now (so the schema matches the spec in full from the start) but stay empty/unused until steps 5–7 build the code that touches them.

## Docker Compose (`docker-compose.yml`, repo root)

A single `mysql:8` service:
- Environment: `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE=matchmaker`, `MYSQL_USER`, `MYSQL_PASSWORD` — values matched by `db.properties`.
- Port `3306:3306`.
- Bind-mounts `db/schema.sql` (read-only) into `/docker-entrypoint-initdb.d/schema.sql`, which MySQL executes automatically the first time the container initializes its data volume.
- A named volume for data persistence across `docker compose down`/`up` (schema only re-runs if the volume is removed, e.g. `docker compose down -v`).

Usage: `docker compose up -d` once; MySQL is then reachable at `localhost:3306` with the schema already loaded.

## Connection Layer

- **`src/main/resources/db.properties`** — host, port, database name, user, password, matching the compose file's defaults.
- **`server/dao/DataSourceFactory.java`** (new) — reads `db.properties` once, builds and returns a shared `HikariDataSource`. `ServerMain` owns the single instance and passes it (or the DAOs built from it) down to the service implementations; nothing else constructs its own pool.
- **`pom.xml`** gains three new dependencies: `mysql-connector-j`, `com.zaxxer:HikariCP`, `org.mindrot:jbcrypt`.

## DAO Layer (`server/dao/`, new package)

Each DAO is an interface + a `Jdbc`-prefixed implementation, mirroring how `AuthService`/`PlayerService` are already interfaces — this lets unit tests substitute an in-memory fake instead of hitting real SQL.

### `UserDao`
```java
Optional<UserRecord> insert(String username, String passwordHash);
Optional<UserRecord> findByUsername(String username);
```
- `UserRecord` (new, `server/dao` package — **not** `common/dto`) carries every `User` column including the password hash: `id`, `username`, `passwordHash`, `admin`, `wins`, `losses`, `draws`, `rating`, `createdAt`. It must never be placed on an RMI-crossing type; `AuthServiceImpl` converts it to a `UserDTO` (which has no password field) before returning anything to a client.
- `insert()` returns `Optional.empty()` on a duplicate username. Implementation attempts the `INSERT` directly and catches the DB's own unique-constraint violation, rather than checking-then-inserting — check-then-insert has a race window where two concurrent registrations for the same username could both pass the check before either commits; relying on the DB constraint closes that window.
- `findByUsername()` returns `Optional.empty()` if no such user exists.

### `GameSessionDao`
```java
List<GameStateDTO> findFinishedSessionsForUser(int userId);
```
One method only. Queries `GameSession` where `(Player1ID = ? OR Player2ID = ?) AND Status = 'FINISHED'`, ordered by `EndTime DESC`. Maps straight to `GameStateDTO` (unlike `UserDao`, there's no sensitive field to keep off the wire). No insert/update methods yet — those arrive in steps 5–7 when matchmaking and the game engine need to create/mutate sessions.

### `GameTypeDao`
```java
List<GameTypeDTO> findAll();
```
One method. Backs `PlayerServiceImpl.listGameTypes()`.

### Error handling
Unexpected failures (connection lost, syntax error, etc.) are wrapped in a new unchecked `DaoException extends RuntimeException` inside the `Jdbc*` implementations. DAO interface methods don't declare `throws SQLException` — that would leak a JDBC-specific checked exception into every RMI service method signature for a failure mode none of them can meaningfully recover from.

## Wiring

- **`ServerMain`** builds one `HikariDataSource` via `DataSourceFactory`, constructs `JdbcUserDao`, `JdbcGameSessionDao`, `JdbcGameTypeDao` from it, and passes the relevant DAOs into `AuthServiceImpl`'s and `PlayerServiceImpl`'s constructors — same constructor-injection style already used for `SessionManager`.
- **`AuthServiceImpl.register(username, password)`**: `BCrypt.hashpw(password, BCrypt.gensalt())`, then `userDao.insert(username, hash)`. Empty result → `UsernameTakenException`. Otherwise builds a `UserDTO` from the returned `UserRecord` and returns it. The hardcoded `TEST_USERNAME`/`TEST_PASSWORD`/`TEST_USER_ID` constants and their special-case logic are removed entirely.
- **`AuthServiceImpl.login(username, password)`**: `userDao.findByUsername(username)`; if absent, or `BCrypt.checkpw(password, record.passwordHash())` is false, throw `AuthenticationException` (both cases produce the same message — not revealing whether the username exists). Otherwise `sessionManager.createSession(record.id())` and return a `LoginResultDTO` built from the record.
- **`AuthServiceImpl.keepAlive()`** is unchanged — purely in-memory via `SessionManager`, no DB involved.
- **`PlayerServiceImpl.getHistory(sessionToken)`**: `sessionManager.resolve(sessionToken)` to get the caller's user id, then `gameSessionDao.findFinishedSessionsForUser(userId)`.
- **`PlayerServiceImpl.listGameTypes(sessionToken)`**: `sessionManager.resolve(sessionToken)` (still requires a valid session — this is a real player action, not public), then `gameTypeDao.findAll()`.
- All other `PlayerServiceImpl`/`AdminServiceImpl` methods are untouched, still throwing `UnsupportedOperationException` with their existing (correct) step references.

## Testing

Extends the project's existing three-tier test pattern with a fourth tier:

1. Plain unit tests (no networking, no DB) — unchanged pattern.
2. Serialization round-trip tests — unchanged, not affected by this milestone.
3. Real RMI integration tests — unchanged.
4. **New: DB integration tests** (`UserDaoTest`, `GameSessionDaoTest`, `GameTypeDaoTest`) — run real SQL against the Docker MySQL described above, using the same `db.properties` the app uses. Each test truncates the relevant tables in `@BeforeEach` for a clean, deterministic starting state (truncation order respects foreign keys: child tables before `User`/`GameType`).

`AuthServiceImplTest` and `PlayerServiceImplTest` (existing, tier 1) are updated to use small in-memory fake implementations of `UserDao`/`GameSessionDao`/`GameTypeDao` rather than real DAOs — this keeps them fast and DB-independent, testing `AuthServiceImpl`'s/`PlayerServiceImpl`'s own logic (hashing calls, exception translation, session handling) in isolation from JDBC.

**Explicit tradeoff, called out rather than left implicit:** this changes the project's current "fresh clone → `mvn test` → all green, no external setup" guarantee. The four new DB-integration tests require `docker compose up -d` to have been run first; without it, those specific tests fail with a connection error (everything else still passes). The build-plan's Verification section will be updated to state this plainly.

## Out of scope for this milestone

- `Move`, `MatchmakingQueue`, `ChatMessage` DAOs — created in schema, not touched in code (steps 5–7).
- `GameSessionDao` insert/update methods — steps 5 (matchmaking creates sessions) and 7 (game engine mutates them).
- `AdminServiceImpl.listGameTypes()`/`addGameType()` — correctly scoped to step 9, unaffected by this change.
- Rating/ELO calculation logic — not needed until a game can actually finish (step 7+).
