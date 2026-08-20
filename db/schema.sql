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
    -- MoveNumber is computed as MAX(MoveNumber) + 1 inside recordMove()'s transaction, which is
    -- safe today only because the guarded session UPDATE that follows rejects the loser of any
    -- race. Nothing in the schema enforced it, so a future writer that doesn't go through
    -- recordMove() could silently produce duplicate move numbers for a session.
    UNIQUE KEY uq_move_session_number (SessionID, MoveNumber),
    FOREIGN KEY (SessionID) REFERENCES GameSession(ID),
    FOREIGN KEY (UserID) REFERENCES User(ID)
);

CREATE TABLE MatchmakingQueue (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    UserID INT NOT NULL,
    GameTypeID INT NOT NULL,
    -- Only 'WAITING' is ever written: cancel() and the pairing path both DELETE the row
    -- rather than transitioning it, so MATCHED/CANCELLED are vestigial. Left in place because
    -- narrowing the ENUM would need an ALTER against live data for no behavioural gain.
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

-- Seed data: one game type so listGameTypes() has something to return out of the box.
INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols)
VALUES ('Checkers', 'Classic two-player checkers on an 8x8 board.', 2, 2, 8, 8);

-- =====================================================================================
-- Test database: matchmaker_test -- identical schema to matchmaker above, but no seed
-- data (the DB-integration tests insert their own fixtures and clean up after themselves
-- via TestDatabase.cleanAll() in @BeforeEach). Kept entirely separate from matchmaker so
-- that running `mvn test` can never touch manually-created dev/demo data (see the incident
-- in docs/build-plan.md's Verification section that prompted this split).
-- src/test/resources/db.properties points DataSourceFactory at this database instead of
-- the main one whenever tests run -- Maven puts the test classpath ahead of the main one,
-- so that file overrides src/main/resources/db.properties automatically, no code involved.
--
-- NOTE: keep this block's table definitions in sync with the ones above -- SQL has no
-- import/include mechanism, so this is intentionally duplicated rather than built with
-- extra tooling, given how rarely the schema changes at this stage of the project.
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS matchmaker_test;
GRANT ALL PRIVILEGES ON matchmaker_test.* TO 'matchmaker'@'%';
FLUSH PRIVILEGES;
USE matchmaker_test;

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
    -- MoveNumber is computed as MAX(MoveNumber) + 1 inside recordMove()'s transaction, which is
    -- safe today only because the guarded session UPDATE that follows rejects the loser of any
    -- race. Nothing in the schema enforced it, so a future writer that doesn't go through
    -- recordMove() could silently produce duplicate move numbers for a session.
    UNIQUE KEY uq_move_session_number (SessionID, MoveNumber),
    FOREIGN KEY (SessionID) REFERENCES GameSession(ID),
    FOREIGN KEY (UserID) REFERENCES User(ID)
);

CREATE TABLE MatchmakingQueue (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    UserID INT NOT NULL,
    GameTypeID INT NOT NULL,
    -- Only 'WAITING' is ever written: cancel() and the pairing path both DELETE the row
    -- rather than transitioning it, so MATCHED/CANCELLED are vestigial. Left in place because
    -- narrowing the ENUM would need an ALTER against live data for no behavioural gain.
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
