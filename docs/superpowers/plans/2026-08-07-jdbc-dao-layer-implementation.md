# JDBC/DAO Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement roadmap step 4 exactly as designed in `docs/superpowers/specs/2026-08-07-jdbc-dao-layer-design.md` — a MySQL schema + Docker Compose, a HikariCP-pooled connection layer, `UserDao`/`GameSessionDao`/`GameTypeDao`, and real (not stubbed) `AuthServiceImpl.register/login` and `PlayerServiceImpl.getHistory/listGameTypes`.

**Architecture:** New package `com.matchmaker.server.dao` holds one interface + one `Jdbc`-prefixed implementation per table this milestone touches (`User`, `GameType`, `GameSession`), plus `DataSourceFactory` (builds a shared `HikariDataSource` from `db.properties`) and `DaoException` (unchecked wrapper for `SQLException`). `AuthServiceImpl` and `PlayerServiceImpl` take DAOs via constructor injection, same pattern as `SessionManager` today. Existing tier-1 unit tests (`AuthServiceImplTest`, `PlayerServiceImplTest`) get rewritten to use small in-memory fake DAOs instead of hitting real SQL, keeping them fast and DB-free; three new tier-4 DAO tests run real SQL against a Docker MySQL.

**Tech Stack:** Java 21, Maven, JUnit 5, `mysql-connector-j`, `com.zaxxer:HikariCP`, `org.mindrot:jbcrypt`, Docker Compose (`mysql:8`).

## Global Constraints

- New package: `com.matchmaker.server.dao` (interfaces, `Jdbc*` implementations, `DataSourceFactory`, `DaoException`, `UserRecord`). Test doubles (`InMemory*Dao`) live in the mirrored test package `src/test/java/com/matchmaker/server/dao/`.
- DAO interface methods never declare `throws SQLException`. Unexpected DB failures are wrapped in `DaoException extends RuntimeException` inside the `Jdbc*` implementation.
- `UserDao` returns `UserRecord` (new type in `server.dao`, carries `passwordHash`) — never `UserDTO` — since `UserDTO` must never carry a password hash across RMI.
- `UserDao.insert(...)` returns `Optional.empty()` on a duplicate username by catching `SQLIntegrityConstraintViolationException`, not by checking-then-inserting.
- `DataSourceFactory.create()` builds its `HikariConfig` with `setInitializationFailTimeout(-1)` — pool construction must never block or fail even if MySQL isn't reachable yet; failures surface lazily, only when a DAO actually executes a query. This is what keeps `ServerMainTest` and `AuthServiceRmiIntegrationTest` Docker-free (verified per-task below).
- Password hashing: `org.mindrot.jbcrypt.BCrypt.hashpw(password, BCrypt.gensalt())` on register, `BCrypt.checkpw(password, storedHash)` on login. Only inside `AuthServiceImpl` — DAOs never see a plaintext password.
- The three new DAO test classes (`UserDaoTest`, `GameTypeDaoTest`, `GameSessionDaoTest`) require `docker compose up -d` to be running first; they connect via `DataSourceFactory.create()`, same as production code. Every other test in this plan (including the rewritten `AuthServiceImplTest`, `PlayerServiceImplTest`, `AuthServiceRmiIntegrationTest`, and the untouched `ServerMainTest`) stays Docker-free.
- Each DAO test's `@BeforeEach` clears state in FK-safe order: `DELETE FROM GameSession` before `DELETE FROM User` / `DELETE FROM GameType` — every DAO test class does this same cleanup regardless of which tables it personally uses, since tests share one running MySQL instance across classes.
- Existing stub methods in `PlayerServiceImpl` keep their current `UnsupportedOperationException` messages and step numbers (`joinQueue`/`cancelQueue` → step 5, `sendChatMessage` → step 6, `makeMove`/`resign` → step 7, `rematch` → step 10) — only `listGameTypes` and `getHistory` graduate off the stub pattern in this plan.

---

## File Structure

**Created:**
- `docker-compose.yml` (Task 1)
- `db/schema.sql` (Task 1)
- `src/main/resources/db.properties` (Task 2)
- `src/main/java/com/matchmaker/server/dao/DataSourceFactory.java` (Task 2)
- `src/test/java/com/matchmaker/server/dao/DataSourceFactoryTest.java` (Task 2)
- `src/main/java/com/matchmaker/server/dao/DaoException.java` (Task 3)
- `src/main/java/com/matchmaker/server/dao/UserRecord.java` (Task 3)
- `src/main/java/com/matchmaker/server/dao/UserDao.java` (Task 3)
- `src/main/java/com/matchmaker/server/dao/JdbcUserDao.java` (Task 3)
- `src/test/java/com/matchmaker/server/dao/UserDaoTest.java` (Task 3)
- `src/main/java/com/matchmaker/server/dao/GameTypeDao.java` (Task 4)
- `src/main/java/com/matchmaker/server/dao/JdbcGameTypeDao.java` (Task 4)
- `src/test/java/com/matchmaker/server/dao/GameTypeDaoTest.java` (Task 4)
- `src/main/java/com/matchmaker/server/dao/GameSessionDao.java` (Task 5)
- `src/main/java/com/matchmaker/server/dao/JdbcGameSessionDao.java` (Task 5)
- `src/test/java/com/matchmaker/server/dao/GameSessionDaoTest.java` (Task 5)
- `src/test/java/com/matchmaker/server/dao/InMemoryUserDao.java` (Task 6)
- `src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java` (Task 7)
- `src/test/java/com/matchmaker/server/dao/InMemoryGameTypeDao.java` (Task 7)

**Modified:**
- `pom.xml` (Task 2 — add `mysql-connector-j`, `HikariCP`, `jbcrypt`)
- `src/main/java/com/matchmaker/server/rmi/AuthServiceImpl.java` (Task 6)
- `src/test/java/com/matchmaker/server/rmi/AuthServiceImplTest.java` (Task 6)
- `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java` (Task 7)
- `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java` (Task 7)
- `src/main/java/com/matchmaker/server/ServerMain.java` (Task 8)
- `src/test/java/com/matchmaker/server/rmi/AuthServiceRmiIntegrationTest.java` (Task 9)
- `docs/build-plan.md` (Task 10)

---

### Task 1: Schema + Docker Compose

**Files:**
- Create: `db/schema.sql`
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: a running local MySQL reachable at `localhost:3306`, database `matchmaker`, with all 6 tables from spec §7 already created. Every later task's `db.properties`/`DataSourceFactory`/DAO tests depend on this being up via `docker compose up -d`.

No Java code in this task — verification is manual (a real, running database), same category of check as `ServerMain`'s own manual sanity run in the previous milestone.

- [ ] **Step 1: Write `db/schema.sql`**

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

- [ ] **Step 2: Write `docker-compose.yml`**

```yaml
services:
  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: matchmaker
      MYSQL_USER: matchmaker
      MYSQL_PASSWORD: matchmaker
    ports:
      - "3306:3306"
    volumes:
      - ./db/schema.sql:/docker-entrypoint-initdb.d/schema.sql:ro
      - matchmaker-mysql-data:/var/lib/mysql

volumes:
  matchmaker-mysql-data:
```

- [ ] **Step 3: Start it and verify the schema loaded**

Run: `docker compose up -d`
Then: `docker compose exec mysql mysql -umatchmaker -pmatchmaker matchmaker -e "SHOW TABLES;"`
Expected: 6 rows — `ChatMessage`, `GameSession`, `GameType`, `MatchmakingQueue`, `Move`, `User`.

If you'd previously started a container from an older `docker-compose.yml`, `docker compose down -v` first — the init script only runs against a fresh (empty) data volume.

- [ ] **Step 4: Commit**

```bash
git add db/schema.sql docker-compose.yml
git commit -m "Add MySQL schema and docker-compose for local dev DB"
```

---

### Task 2: Connection layer — `db.properties` + `DataSourceFactory`

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/db.properties`
- Create: `src/main/java/com/matchmaker/server/dao/DataSourceFactory.java`
- Test: `src/test/java/com/matchmaker/server/dao/DataSourceFactoryTest.java`

**Interfaces:**
- Consumes: `db.properties` on the classpath (this task).
- Produces: `DataSourceFactory.create()` → `javax.sql.DataSource` — every later DAO task (3, 4, 5) and `ServerMain` (Task 8) call this to get their connection pool.

- [ ] **Step 1: Add dependencies to `pom.xml`**

Add inside `<dependencies>`, alongside the existing `junit-jupiter` entry:

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.4.0</version>
</dependency>
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
<dependency>
    <groupId>org.mindrot</groupId>
    <artifactId>jbcrypt</artifactId>
    <version>0.4</version>
</dependency>
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `mvn compile`
Expected: `BUILD SUCCESS` (Maven downloads the three new jars).

- [ ] **Step 3: Write `db.properties`**

```properties
db.url=jdbc:mysql://localhost:3306/matchmaker
db.username=matchmaker
db.password=matchmaker
```

- [ ] **Step 4: Write the failing test**

```java
package com.matchmaker.server.dao;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DataSourceFactoryTest {

    @Test
    void create_buildsHikariDataSourceFromDbProperties() {
        DataSource dataSource = DataSourceFactory.create();

        HikariDataSource hikari = assertInstanceOf(HikariDataSource.class, dataSource);
        assertEquals("jdbc:mysql://localhost:3306/matchmaker", hikari.getJdbcUrl());
        assertEquals("matchmaker", hikari.getUsername());

        hikari.close();
    }
}
```

This test does **not** require MySQL to be running — `create()` must succeed even with no reachable DB, since `initializationFailTimeout(-1)` (Step 5) makes pool construction lazy.

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn test -Dtest=DataSourceFactoryTest`
Expected: compilation failure — `DataSourceFactory` doesn't exist yet.

- [ ] **Step 6: Write minimal implementation**

```java
package com.matchmaker.server.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class DataSourceFactory {

    public static DataSource create() {
        Properties props = loadProperties();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(props.getProperty("db.url"));
        config.setUsername(props.getProperty("db.username"));
        config.setPassword(props.getProperty("db.password"));
        config.setInitializationFailTimeout(-1);

        return new HikariDataSource(config);
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream in = DataSourceFactory.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in == null) {
                throw new IllegalStateException("db.properties not found on classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load db.properties", e);
        }
        return props;
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn test -Dtest=DataSourceFactoryTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0` — passes with or without `docker compose up -d` running.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/resources/db.properties src/main/java/com/matchmaker/server/dao/DataSourceFactory.java src/test/java/com/matchmaker/server/dao/DataSourceFactoryTest.java
git commit -m "Add HikariCP-backed DataSourceFactory and DB dependencies"
```

---

### Task 3: `UserDao`

**Files:**
- Create: `src/main/java/com/matchmaker/server/dao/DaoException.java`
- Create: `src/main/java/com/matchmaker/server/dao/UserRecord.java`
- Create: `src/main/java/com/matchmaker/server/dao/UserDao.java`
- Create: `src/main/java/com/matchmaker/server/dao/JdbcUserDao.java`
- Test: `src/test/java/com/matchmaker/server/dao/UserDaoTest.java`

**Interfaces:**
- Consumes: `DataSourceFactory.create()` (Task 2), the `User` table (Task 1).
- Produces: `UserDao` (`insert(String, String) -> Optional<UserRecord>`, `findByUsername(String) -> Optional<UserRecord>`), `UserRecord` (fields: `id, username, passwordHash, admin, wins, losses, draws, rating, createdAt`), `DaoException` — Task 6 (`AuthServiceImpl` rewire) constructs a `JdbcUserDao` and calls both methods; Task 8 (`ServerMain`) also constructs one.

**Requires `docker compose up -d` running** (from Task 1) before running this task's test.

- [ ] **Step 1: Write `DaoException`**

```java
package com.matchmaker.server.dao;

public class DaoException extends RuntimeException {
    public DaoException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: Write `UserRecord`**

```java
package com.matchmaker.server.dao;

import java.time.LocalDateTime;

public record UserRecord(int id, String username, String passwordHash, boolean admin,
                          int wins, int losses, int draws, int rating, LocalDateTime createdAt) {
}
```

- [ ] **Step 3: Write `UserDao`**

```java
package com.matchmaker.server.dao;

import java.util.Optional;

public interface UserDao {
    Optional<UserRecord> insert(String username, String passwordHash);
    Optional<UserRecord> findByUsername(String username);
}
```

- [ ] **Step 4: Write the failing test**

```java
package com.matchmaker.server.dao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UserDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final UserDao userDao = new JdbcUserDao(DATA_SOURCE);

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM GameSession");
            stmt.execute("DELETE FROM User");
            stmt.execute("DELETE FROM GameType");
        }
    }

    @Test
    void insert_newUsername_returnsRecordWithDefaults() {
        Optional<UserRecord> result = userDao.insert("alice", "hashed-password");

        assertTrue(result.isPresent());
        UserRecord record = result.get();
        assertTrue(record.id() > 0);
        assertEquals("alice", record.username());
        assertEquals("hashed-password", record.passwordHash());
        assertFalse(record.admin());
        assertEquals(0, record.wins());
        assertEquals(0, record.losses());
        assertEquals(0, record.draws());
        assertEquals(1200, record.rating());
        assertNotNull(record.createdAt());
    }

    @Test
    void insert_duplicateUsername_returnsEmpty() {
        userDao.insert("bob", "hash1");

        Optional<UserRecord> result = userDao.insert("bob", "hash2");

        assertTrue(result.isEmpty());
    }

    @Test
    void findByUsername_existingUser_returnsRecord() {
        userDao.insert("carol", "hash");

        Optional<UserRecord> result = userDao.findByUsername("carol");

        assertTrue(result.isPresent());
        assertEquals("carol", result.get().username());
    }

    @Test
    void findByUsername_unknownUser_returnsEmpty() {
        Optional<UserRecord> result = userDao.findByUsername("nobody");

        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn test -Dtest=UserDaoTest`
Expected: compilation failure — `JdbcUserDao` doesn't exist yet.

- [ ] **Step 6: Write minimal implementation**

```java
package com.matchmaker.server.dao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Optional;

public class JdbcUserDao implements UserDao {

    private final DataSource dataSource;

    public JdbcUserDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<UserRecord> insert(String username, String passwordHash) {
        String sql = "INSERT INTO User (Username, Password) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, passwordHash);
            stmt.executeUpdate();
            return findByUsername(username);
        } catch (SQLIntegrityConstraintViolationException e) {
            return Optional.empty();
        } catch (SQLException e) {
            throw new DaoException("Failed to insert user '" + username + "'", e);
        }
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        String sql = "SELECT ID, Username, Password, IsAdmin, Wins, Losses, Draws, Rating, CreatedAt "
                + "FROM User WHERE Username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRecord(
                        rs.getInt("ID"),
                        rs.getString("Username"),
                        rs.getString("Password"),
                        rs.getBoolean("IsAdmin"),
                        rs.getInt("Wins"),
                        rs.getInt("Losses"),
                        rs.getInt("Draws"),
                        rs.getInt("Rating"),
                        rs.getTimestamp("CreatedAt").toLocalDateTime()));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find user '" + username + "'", e);
        }
    }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn test -Dtest=UserDaoTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0` (requires `docker compose up -d`).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matchmaker/server/dao/DaoException.java src/main/java/com/matchmaker/server/dao/UserRecord.java src/main/java/com/matchmaker/server/dao/UserDao.java src/main/java/com/matchmaker/server/dao/JdbcUserDao.java src/test/java/com/matchmaker/server/dao/UserDaoTest.java
git commit -m "Add UserDao backed by real MySQL via JDBC"
```

---

### Task 4: `GameTypeDao`

**Files:**
- Create: `src/main/java/com/matchmaker/server/dao/GameTypeDao.java`
- Create: `src/main/java/com/matchmaker/server/dao/JdbcGameTypeDao.java`
- Test: `src/test/java/com/matchmaker/server/dao/GameTypeDaoTest.java`

**Interfaces:**
- Consumes: `DataSourceFactory.create()` (Task 2), `DaoException` (Task 3), the `GameType` table (Task 1), `com.matchmaker.common.dto.GameTypeDTO` (already exists).
- Produces: `GameTypeDao` (`findAll() -> List<GameTypeDTO>`) — Task 7 (`PlayerServiceImpl` rewire) and Task 8 (`ServerMain`) both construct a `JdbcGameTypeDao`.

**Requires `docker compose up -d` running.**

- [ ] **Step 1: Write `GameTypeDao`**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import java.util.List;

public interface GameTypeDao {
    List<GameTypeDTO> findAll();
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTypeDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final GameTypeDao gameTypeDao = new JdbcGameTypeDao(DATA_SOURCE);

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM GameSession");
            stmt.execute("DELETE FROM User");
            stmt.execute("DELETE FROM GameType");
        }
    }

    @Test
    void findAll_noGameTypes_returnsEmptyList() {
        assertTrue(gameTypeDao.findAll().isEmpty());
    }

    @Test
    void findAll_withGameTypes_returnsThemInInsertOrder() throws Exception {
        insertGameType("Checkers", "Classic checkers", 2, 2, 8, 8);
        insertGameType("Chess", "Classic chess", 2, 2, 8, 8);

        List<GameTypeDTO> result = gameTypeDao.findAll();

        assertEquals(2, result.size());
        assertEquals("Checkers", result.get(0).getName());
        assertEquals("Chess", result.get(1).getName());
    }

    private void insertGameType(String name, String description, int minPlayers, int maxPlayers,
                                 int rows, int cols) throws Exception {
        String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DATA_SOURCE.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, description);
            stmt.setInt(3, minPlayers);
            stmt.setInt(4, maxPlayers);
            stmt.setInt(5, rows);
            stmt.setInt(6, cols);
            stmt.executeUpdate();
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=GameTypeDaoTest`
Expected: compilation failure — `JdbcGameTypeDao` doesn't exist yet.

- [ ] **Step 4: Write minimal implementation**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcGameTypeDao implements GameTypeDao {

    private final DataSource dataSource;

    public JdbcGameTypeDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<GameTypeDTO> findAll() {
        String sql = "SELECT ID, Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols "
                + "FROM GameType ORDER BY ID";
        List<GameTypeDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new GameTypeDTO(
                        rs.getInt("ID"),
                        rs.getString("Name"),
                        rs.getString("Description"),
                        rs.getInt("MinPlayers"),
                        rs.getInt("MaxPlayers"),
                        rs.getInt("BoardRows"),
                        rs.getInt("BoardCols")));
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to list game types", e);
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=GameTypeDaoTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0` (requires `docker compose up -d`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matchmaker/server/dao/GameTypeDao.java src/main/java/com/matchmaker/server/dao/JdbcGameTypeDao.java src/test/java/com/matchmaker/server/dao/GameTypeDaoTest.java
git commit -m "Add GameTypeDao backed by real MySQL via JDBC"
```

---

### Task 5: `GameSessionDao`

**Files:**
- Create: `src/main/java/com/matchmaker/server/dao/GameSessionDao.java`
- Create: `src/main/java/com/matchmaker/server/dao/JdbcGameSessionDao.java`
- Test: `src/test/java/com/matchmaker/server/dao/GameSessionDaoTest.java`

**Interfaces:**
- Consumes: `DataSourceFactory.create()` (Task 2), `DaoException` (Task 3), the `GameSession`/`User`/`GameType` tables (Task 1), `com.matchmaker.common.dto.GameStateDTO`, `com.matchmaker.common.enums.GameStatus` (both already exist).
- Produces: `GameSessionDao` (`findFinishedSessionsForUser(int) -> List<GameStateDTO>`) — Task 7 (`PlayerServiceImpl` rewire) and Task 8 (`ServerMain`) both construct a `JdbcGameSessionDao`.

**Requires `docker compose up -d` running.** This test also seeds its own `GameType`/`User` fixture rows directly via JDBC in `@BeforeEach`, since `GameSession` rows can't exist without them and no insert methods exist yet on `GameTypeDao`/`UserDao` beyond what Tasks 3–4 already added (`UserDao.insert`, but not a matching one for `GameType` — this test uses raw SQL for fixtures instead of reaching for a DAO method that doesn't exist for this purpose).

- [ ] **Step 1: Write `GameSessionDao`**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;

import java.util.List;

public interface GameSessionDao {
    List<GameStateDTO> findFinishedSessionsForUser(int userId);
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameSessionDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final GameSessionDao gameSessionDao = new JdbcGameSessionDao(DATA_SOURCE);

    private int gameTypeId;
    private int player1Id;
    private int player2Id;

    @BeforeEach
    void cleanTablesAndInsertFixtures() throws Exception {
        try (Connection conn = DATA_SOURCE.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM GameSession");
            stmt.execute("DELETE FROM User");
            stmt.execute("DELETE FROM GameType");
        }
        gameTypeId = insertGameType("Checkers");
        player1Id = insertUser("player1");
        player2Id = insertUser("player2");
    }

    @Test
    void findFinishedSessionsForUser_returnsOnlyFinishedSessionsInvolvingUser() throws Exception {
        int finishedSessionId = insertGameSession(gameTypeId, player1Id, player2Id, "FINISHED", player1Id);
        insertGameSession(gameTypeId, player1Id, player2Id, "ACTIVE", null);

        List<GameStateDTO> history = gameSessionDao.findFinishedSessionsForUser(player1Id);

        assertEquals(1, history.size());
        assertEquals(finishedSessionId, history.get(0).getSessionId());
        assertEquals(GameStatus.FINISHED, history.get(0).getStatus());
        assertEquals(player1Id, history.get(0).getWinnerId());
    }

    @Test
    void findFinishedSessionsForUser_userNotInAnySession_returnsEmptyList() {
        List<GameStateDTO> history = gameSessionDao.findFinishedSessionsForUser(player1Id);

        assertTrue(history.isEmpty());
    }

    private int insertGameType(String name) throws Exception {
        String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
                + "VALUES (?, 'desc', 2, 2, 8, 8)";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int insertUser(String username) throws Exception {
        String sql = "INSERT INTO User (Username, Password) VALUES (?, 'hash')";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int insertGameSession(int gameTypeId, int player1Id, int player2Id, String status,
                                   Integer winnerId) throws Exception {
        String sql = "INSERT INTO GameSession (GameTypeID, Player1ID, Player2ID, Status, WinnerID, EndTime) "
                + "VALUES (?, ?, ?, ?, ?, NOW())";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setString(4, status);
            if (winnerId != null) {
                stmt.setInt(5, winnerId);
            } else {
                stmt.setNull(5, Types.INTEGER);
            }
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=GameSessionDaoTest`
Expected: compilation failure — `JdbcGameSessionDao` doesn't exist yet.

- [ ] **Step 4: Write minimal implementation**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcGameSessionDao implements GameSessionDao {

    private final DataSource dataSource;

    public JdbcGameSessionDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
        String sql = "SELECT ID, GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, WinnerID, BoardState "
                + "FROM GameSession WHERE (Player1ID = ? OR Player2ID = ?) AND Status = 'FINISHED' "
                + "ORDER BY EndTime DESC";
        List<GameStateDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Integer currentTurnUserId = (Integer) rs.getObject("CurrentTurnUserID");
                    Integer winnerId = (Integer) rs.getObject("WinnerID");
                    result.add(new GameStateDTO(
                            rs.getInt("ID"),
                            rs.getInt("GameTypeID"),
                            rs.getInt("Player1ID"),
                            rs.getInt("Player2ID"),
                            GameStatus.valueOf(rs.getString("Status")),
                            currentTurnUserId,
                            winnerId,
                            rs.getString("BoardState")));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to find finished sessions for user " + userId, e);
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=GameSessionDaoTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0` (requires `docker compose up -d`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matchmaker/server/dao/GameSessionDao.java src/main/java/com/matchmaker/server/dao/JdbcGameSessionDao.java src/test/java/com/matchmaker/server/dao/GameSessionDaoTest.java
git commit -m "Add GameSessionDao backed by real MySQL via JDBC"
```

---

### Task 6: Rewire `AuthServiceImpl` onto `UserDao`

**Files:**
- Create: `src/test/java/com/matchmaker/server/dao/InMemoryUserDao.java`
- Modify: `src/main/java/com/matchmaker/server/rmi/AuthServiceImpl.java`
- Modify: `src/test/java/com/matchmaker/server/rmi/AuthServiceImplTest.java`

**Interfaces:**
- Consumes: `UserDao`/`UserRecord` (Task 3), `SessionManager` (existing), `org.mindrot.jbcrypt.BCrypt` (Task 2 dependency).
- Produces: `AuthServiceImpl(SessionManager, UserDao)` — **constructor signature changes** from the current `(SessionManager)`. Task 8 (`ServerMain`) and Task 9 (`AuthServiceRmiIntegrationTest`) both use the new signature. `InMemoryUserDao` (test-only, implements `UserDao` from Task 3) — Task 9 also uses it.

This task removes `AuthServiceImpl`'s hardcoded `TEST_USER_ID`/`TEST_USERNAME`/`TEST_PASSWORD` entirely — `register`/`login` become genuinely DAO-backed. No Docker required: this task's own test uses `InMemoryUserDao`.

- [ ] **Step 1: Write `InMemoryUserDao`**

```java
package com.matchmaker.server.dao;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryUserDao implements UserDao {

    private final Map<String, UserRecord> usersByUsername = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    @Override
    public synchronized Optional<UserRecord> insert(String username, String passwordHash) {
        if (usersByUsername.containsKey(username)) {
            return Optional.empty();
        }
        UserRecord record = new UserRecord(nextId.getAndIncrement(), username, passwordHash,
                false, 0, 0, 0, 1200, LocalDateTime.now());
        usersByUsername.put(username, record);
        return Optional.of(record);
    }

    @Override
    public synchronized Optional<UserRecord> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }
}
```

- [ ] **Step 2: Rewrite the failing test**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryUserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceImplTest {

    private AuthServiceImpl authService;

    @BeforeEach
    void createAuthService() throws Exception {
        authService = new AuthServiceImpl(new SessionManager(), new InMemoryUserDao());
    }

    @AfterEach
    void unexportAuthService() {
        if (authService != null) {
            try { UnicastRemoteObject.unexportObject(authService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void register_withNewUsername_returnsUserWithDefaults() throws Exception {
        UserDTO user = authService.register("alice", "password123");

        assertEquals("alice", user.getUsername());
        assertFalse(user.isAdmin());
        assertEquals(0, user.getWins());
        assertEquals(1200, user.getRating());
    }

    @Test
    void register_withTakenUsername_throwsUsernameTakenException() throws Exception {
        authService.register("bob", "password123");

        assertThrows(UsernameTakenException.class, () -> authService.register("bob", "different-password"));
    }

    @Test
    void login_withCorrectCredentials_returnsTokenAndUser() throws Exception {
        authService.register("carol", "password123");

        LoginResultDTO result = authService.login("carol", "password123");

        assertEquals("carol", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withWrongPassword_throwsAuthenticationException() throws Exception {
        authService.register("dave", "password123");

        assertThrows(AuthenticationException.class, () -> authService.login("dave", "wrongpassword"));
    }

    @Test
    void login_withUnknownUsername_throwsAuthenticationException() throws Exception {
        assertThrows(AuthenticationException.class, () -> authService.login("nobody", "whatever"));
    }

    @Test
    void keepAlive_withValidToken_doesNotThrow() throws Exception {
        authService.register("erin", "password123");
        LoginResultDTO result = authService.login("erin", "password123");

        assertDoesNotThrow(() -> authService.keepAlive(result.getSessionToken()));
    }

    @Test
    void keepAlive_withInvalidToken_throwsAuthenticationException() throws Exception {
        assertThrows(AuthenticationException.class, () -> authService.keepAlive("bogus-token"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=AuthServiceImplTest`
Expected: compilation failure — `AuthServiceImpl(SessionManager, UserDao)` constructor doesn't exist yet (current one only takes `SessionManager`).

- [ ] **Step 4: Rewrite `AuthServiceImpl`**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.dao.UserRecord;
import org.mindrot.jbcrypt.BCrypt;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Optional;

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {

    private final SessionManager sessionManager;
    private final UserDao userDao;

    public AuthServiceImpl(SessionManager sessionManager, UserDao userDao) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.userDao = userDao;
    }

    @Override
    public UserDTO register(String username, String password) throws RemoteException, UsernameTakenException {
        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
        Optional<UserRecord> inserted = userDao.insert(username, passwordHash);
        if (inserted.isEmpty()) {
            throw new UsernameTakenException("Username '" + username + "' is already taken");
        }
        return toUserDTO(inserted.get());
    }

    @Override
    public LoginResultDTO login(String username, String password) throws RemoteException, AuthenticationException {
        Optional<UserRecord> found = userDao.findByUsername(username);
        if (found.isEmpty() || !BCrypt.checkpw(password, found.get().passwordHash())) {
            throw new AuthenticationException("Invalid username or password");
        }
        UserRecord record = found.get();
        String token = sessionManager.createSession(record.id());
        return new LoginResultDTO(toUserDTO(record), token);
    }

    @Override
    public void keepAlive(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
    }

    private static UserDTO toUserDTO(UserRecord record) {
        return new UserDTO(record.id(), record.username(), record.admin(),
                record.wins(), record.losses(), record.draws(), record.rating());
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=AuthServiceImplTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0` — no Docker required.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matchmaker/server/rmi/AuthServiceImpl.java src/test/java/com/matchmaker/server/rmi/AuthServiceImplTest.java src/test/java/com/matchmaker/server/dao/InMemoryUserDao.java
git commit -m "Wire AuthServiceImpl to UserDao with real bcrypt-hashed register/login"
```

---

### Task 7: Rewire `PlayerServiceImpl` — `getHistory` and `listGameTypes`

**Files:**
- Create: `src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java`
- Create: `src/test/java/com/matchmaker/server/dao/InMemoryGameTypeDao.java`
- Modify: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`
- Modify: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`

**Interfaces:**
- Consumes: `GameSessionDao` (Task 5), `GameTypeDao` (Task 4), `SessionManager` (existing).
- Produces: `PlayerServiceImpl(SessionManager, GameSessionDao, GameTypeDao)` — **constructor signature changes** from the current `(SessionManager)`. Task 8 (`ServerMain`) uses the new signature. `InMemoryGameSessionDao`/`InMemoryGameTypeDao` (test-only).

Every other `PlayerServiceImpl` method (`joinQueue`, `cancelQueue`, `makeMove`, `sendChatMessage`, `resign`, `rematch`) is untouched — same `UnsupportedOperationException` messages as today.

- [ ] **Step 1: Write `InMemoryGameSessionDao`**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameStateDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameSessionDao implements GameSessionDao {

    private final List<GameStateDTO> sessions = new ArrayList<>();

    public void addFinishedSession(GameStateDTO session) {
        sessions.add(session);
    }

    @Override
    public List<GameStateDTO> findFinishedSessionsForUser(int userId) {
        List<GameStateDTO> result = new ArrayList<>();
        for (GameStateDTO session : sessions) {
            if (session.getPlayer1Id() == userId || session.getPlayer2Id() == userId) {
                result.add(session);
            }
        }
        return result;
    }
}
```

- [ ] **Step 2: Write `InMemoryGameTypeDao`**

```java
package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameTypeDao implements GameTypeDao {

    private final List<GameTypeDTO> gameTypes = new ArrayList<>();

    public void add(GameTypeDTO gameType) {
        gameTypes.add(gameType);
    }

    @Override
    public List<GameTypeDTO> findAll() {
        return new ArrayList<>(gameTypes);
    }
}
```

- [ ] **Step 3: Rewrite the failing test**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerServiceImplTest {

    private InMemoryGameSessionDao gameSessionDao;
    private InMemoryGameTypeDao gameTypeDao;
    private PlayerServiceImpl playerService;
    private String sessionToken;

    @BeforeEach
    void createPlayerService() throws Exception {
        SessionManager sessionManager = new SessionManager();
        gameSessionDao = new InMemoryGameSessionDao();
        gameTypeDao = new InMemoryGameTypeDao();
        playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao);
        sessionToken = sessionManager.createSession(1);
    }

    @AfterEach
    void unexportPlayerService() {
        if (playerService != null) {
            try { UnicastRemoteObject.unexportObject(playerService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void listGameTypes_returnsWhatDaoReturns() throws Exception {
        gameTypeDao.add(new GameTypeDTO(1, "Checkers", "desc", 2, 2, 8, 8));

        List<GameTypeDTO> result = playerService.listGameTypes(sessionToken);

        assertEquals(1, result.size());
        assertEquals("Checkers", result.get(0).getName());
    }

    @Test
    void listGameTypes_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.listGameTypes("bogus-token"));
    }

    @Test
    void getHistory_returnsFinishedSessionsForCaller() throws Exception {
        GameStateDTO finished = new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board");
        gameSessionDao.addFinishedSession(finished);

        List<GameStateDTO> history = playerService.getHistory(sessionToken);

        assertEquals(1, history.size());
        assertEquals(1, history.get(0).getSessionId());
    }

    @Test
    void getHistory_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.getHistory("bogus-token"));
    }

    @Test
    void remainingMethods_stillThrowUnsupportedOperationException() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> playerService.joinQueue(sessionToken, 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.cancelQueue(sessionToken));
        assertThrows(UnsupportedOperationException.class, () -> playerService.makeMove(sessionToken, 1, "{}"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.sendChatMessage(sessionToken, 1, "hi"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.resign(sessionToken, 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.rematch(sessionToken, 1));
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: compilation failure — `PlayerServiceImpl(SessionManager, GameSessionDao, GameTypeDao)` constructor doesn't exist yet.

- [ ] **Step 5: Rewrite `PlayerServiceImpl`**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.rmi.PlayerService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class PlayerServiceImpl extends UnicastRemoteObject implements PlayerService {

    private final SessionManager sessionManager;
    private final GameSessionDao gameSessionDao;
    private final GameTypeDao gameTypeDao;

    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao)
            throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public void joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("joinQueue not implemented yet -- see build-plan.md step 5");
    }

    @Override
    public void cancelQueue(String sessionToken) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("cancelQueue not implemented yet -- see build-plan.md step 5");
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException {
        throw new UnsupportedOperationException("makeMove not implemented yet -- see build-plan.md step 7");
    }

    @Override
    public void sendChatMessage(String sessionToken, int gameSessionId, String content)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("sendChatMessage not implemented yet -- see build-plan.md step 6");
    }

    @Override
    public void resign(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("resign not implemented yet -- see build-plan.md step 7");
    }

    @Override
    public GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("rematch not implemented yet -- see build-plan.md step 10");
    }

    @Override
    public List<GameStateDTO> getHistory(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        return gameSessionDao.findFinishedSessionsForUser(userId);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0` — no Docker required.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java src/test/java/com/matchmaker/server/dao/InMemoryGameTypeDao.java
git commit -m "Wire PlayerServiceImpl.listGameTypes/getHistory to real DAOs"
```

---

### Task 8: Wire `ServerMain` to the real DAOs

**Files:**
- Modify: `src/main/java/com/matchmaker/server/ServerMain.java`

**Interfaces:**
- Consumes: `DataSourceFactory` (Task 2), `JdbcUserDao` (Task 3), `JdbcGameTypeDao` (Task 4), `JdbcGameSessionDao` (Task 5), the new `AuthServiceImpl`/`PlayerServiceImpl` constructors (Tasks 6–7).
- Produces: nothing later tasks depend on — this is the runnable entry point. `ServerMainTest` (existing, untouched by this task) exercises this method and must keep passing without Docker running, since `DataSourceFactory.create()`'s pool construction is lazy (Task 2's `initializationFailTimeout(-1)`).

- [ ] **Step 1: Rewrite `ServerMain`**

```java
package com.matchmaker.server;

import com.matchmaker.server.dao.DataSourceFactory;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.JdbcGameSessionDao;
import com.matchmaker.server.dao.JdbcGameTypeDao;
import com.matchmaker.server.dao.JdbcUserDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;

import javax.sql.DataSource;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {

    public static final int PORT = 1099;

    public static void main(String[] args) throws Exception {
        start(PORT);

        System.out.println("MatchMaker RMI registry started on port " + PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");
    }

    public static Registry start(int port) throws RemoteException {
        return startWithImpls(port).registry();
    }

    static Started startWithImpls(int port) throws RemoteException {
        SessionManager sessionManager = new SessionManager();

        DataSource dataSource = DataSourceFactory.create();
        UserDao userDao = new JdbcUserDao(dataSource);
        GameSessionDao gameSessionDao = new JdbcGameSessionDao(dataSource);
        GameTypeDao gameTypeDao = new JdbcGameTypeDao(dataSource);

        Registry registry = LocateRegistry.createRegistry(port);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);

        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        return new Started(registry, authService, playerService, adminService);
    }

    record Started(Registry registry, AuthServiceImpl authService, PlayerServiceImpl playerService,
                    AdminServiceImpl adminService) {
    }
}
```

- [ ] **Step 2: Run `ServerMainTest` to confirm it still passes without Docker**

Run: `mvn test -Dtest=ServerMainTest` (with `docker compose down` — deliberately confirm this passes even with MySQL **not** running, proving the lazy-pool design decision actually holds)
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/matchmaker/server/ServerMain.java
git commit -m "Wire ServerMain to real JDBC DAOs via DataSourceFactory"
```

---

### Task 9: Update `AuthServiceRmiIntegrationTest` for the new `AuthServiceImpl` signature

**Files:**
- Modify: `src/test/java/com/matchmaker/server/rmi/AuthServiceRmiIntegrationTest.java`

**Interfaces:**
- Consumes: `AuthServiceImpl(SessionManager, UserDao)` (Task 6), `InMemoryUserDao` (Task 6).
- Produces: nothing later tasks depend on.

This test still proves genuine RMI mechanics (real registry, real stub, real network call, real exceptions crossing the wire) — it uses `InMemoryUserDao` rather than a real DB connection, since real-SQL correctness is already proven by `UserDaoTest` (Task 3). No Docker required.

- [ ] **Step 1: Rewrite the test**

```java
package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryUserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceRmiIntegrationTest {

    private static final int TEST_PORT = 21099;

    private Registry registry;
    private AuthServiceImpl authServiceImpl;

    @BeforeEach
    void startRegistryAndBindService() throws Exception {
        registry = LocateRegistry.createRegistry(TEST_PORT);
        authServiceImpl = new AuthServiceImpl(new SessionManager(), new InMemoryUserDao());
        registry.rebind("AuthService", authServiceImpl);
    }

    @AfterEach
    void tearDownRegistry() throws Exception {
        registry.unbind("AuthService");
        UnicastRemoteObject.unexportObject(authServiceImpl, true);
        UnicastRemoteObject.unexportObject(registry, true);
    }

    @Test
    void registerThenLogin_throughRealRmiStub_returnsRealResult() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");

        stub.register("frank", "password123");
        LoginResultDTO result = stub.login("frank", "password123");

        assertEquals("frank", result.getUser().getUsername());
        assertNotNull(result.getSessionToken());
    }

    @Test
    void login_withBadCredentials_throwsAuthenticationExceptionAcrossRmi() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");
        stub.register("grace", "password123");

        assertThrows(AuthenticationException.class, () -> stub.login("grace", "wrongpassword"));
    }

    @Test
    void register_takenUsername_throwsUsernameTakenExceptionAcrossRmi() throws Exception {
        Registry clientRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        AuthService stub = (AuthService) clientRegistry.lookup("AuthService");
        stub.register("henry", "password123");

        assertThrows(UsernameTakenException.class, () -> stub.register("henry", "different-password"));
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `mvn test -Dtest=AuthServiceRmiIntegrationTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0` — no Docker required. (It will fail to *compile* before Task 6 lands, since it needs the new two-arg `AuthServiceImpl` constructor — that's expected; this task only runs after Task 6.)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/matchmaker/server/rmi/AuthServiceRmiIntegrationTest.java
git commit -m "Update AuthServiceRmiIntegrationTest for real register/login over RMI"
```

---

### Task 10: Update `build-plan.md` and full verification

**Files:**
- Modify: `docs/build-plan.md`

**Interfaces:** N/A — documentation + verification only.

- [ ] **Step 1: Update `docs/build-plan.md`**

In the "What's Implemented So Far" section, add a new subsection after Milestone 2 documenting this milestone (mirror the existing Milestone 1/2 write-up style): what `UserDao`/`GameSessionDao`/`GameTypeDao` do, that `AuthServiceImpl`/`PlayerServiceImpl.getHistory`/`listGameTypes` are now real, and a link to `docs/superpowers/specs/2026-08-07-jdbc-dao-layer-design.md` and this plan file.

In "Next Steps", update the immediate focus to step 5 (matchmaking queue logic) and update the "Current state" test count.

In "Verification", add this line documenting the new setup requirement:
```
- `docker compose up -d` must be running before `UserDaoTest`, `GameTypeDaoTest`, or `GameSessionDaoTest` — these three run real SQL against a real MySQL. Every other test (including `ServerMainTest` and `AuthServiceRmiIntegrationTest`) remains Docker-free.
```

- [ ] **Step 2: Run the full test suite with Docker up**

Run: `docker compose up -d && mvn test`
Expected: all tests pass, `BUILD SUCCESS`.

- [ ] **Step 3: Run the full test suite with Docker down, confirm only the 3 DAO tests fail**

Run: `docker compose down && mvn test`
Expected: `UserDaoTest`, `GameTypeDaoTest`, `GameSessionDaoTest` fail with a connection error; every other test class still passes. This confirms the "Docker-free except for DAO tests" design constraint actually holds.

- [ ] **Step 4: Bring MySQL back up and do a manual sanity run**

Run: `docker compose up -d`, then `mvn compile && java -cp target/classes com.matchmaker.server.ServerMain`
Expected: console prints the registry-started message; process keeps running (Ctrl+C to stop). Confirms the real entry point works against a real, running MySQL.

- [ ] **Step 5: Confirm working tree is clean**

Run: `git status`
Expected: nothing to commit except the `build-plan.md` update from Step 1.

- [ ] **Step 6: Commit**

```bash
git add docs/build-plan.md
git commit -m "Update build-plan.md: JDBC/DAO layer complete, next focus is matchmaking queue"
```

---

## What comes after this plan

Roadmap step 5 (`docs/build-plan.md`) is next: `MatchmakingQueue` handling with synchronized/atomic pairing, creating a `GameSession` row when two players match — the first code to actually write to the `GameSession` and `MatchmakingQueue` tables. That's a separate, later plan.
