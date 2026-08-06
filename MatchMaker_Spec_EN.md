# Technical Design Document
## MatchMaker – Game Server
Final Project – Advanced Java Programming Course
Name: Mahmoud Nashef  ID: 200482198

---

## 1. Functional Description

MatchMaker is a system that lets two remote players play board games (such as checkers) against each other over the network, in real time. A player who wants to play notifies the server, and the server pairs them with another waiting player. Once the two players are connected, a game session runs between them, fully managed by the server.

- The system will support creating a new user as well as logging in.
- The system will let a player request to join a game. If another player is already waiting for the same game type, the server pairs them and opens a game session. If no opponent is available, the player waits in a queue until an opponent is found (matchmaking).
- Before each player, the game board is displayed, and the server manages play according to the game's rules: it verifies that every move is legal, alternates turns, and updates both sides after every move.
- The game ends when one of the players wins. The server informs both players of the game's end and the result (win / loss / draw).
- The game can also end when a player resigns or disconnects. In that case, the server informs the other player that the game has ended and awards them the win.
- The system supports multiple pairs of players playing simultaneously, independently of one another.
- The system stores game history and results, and calculates a Rating for each player based on results.
- The system lets a player send chat messages to their opponent during the game, and search for an opponent by game type.
- The system supports a user who is an admin, who can add new game types, manage users, and monitor active game sessions.

## 2. Technologies

MatchMaker will be implemented as a desktop system using JavaFX. Communication between client and server will use two complementary communication methods taught in the course, each suited to a different kind of communication:

- **RMI** – for synchronous command calls from the client to the server (login, joining the queue, making a move, fetching history). The server exposes remote objects that the client calls and waits for a response from.
- **JMS** – for asynchronous messages from the server to the client (opponent found, opponent made a move, opponent resigned, game ended). Turn-based gameplay requires a "push" capability from server to client: the waiting player must be woken up the moment an opponent is found, and a player must be updated the moment their opponent makes a move. A message queue for each player and a topic for each game session provide this naturally.

The JMS infrastructure will be implemented using the ActiveMQ broker. For each game session a dedicated topic is opened, which both players of the session subscribe to; the topic is created at the moment of pairing and closed when the game ends. This way, every move, chat message, and status update is broadcast only to the two players of that particular session.

Communication with the database will be done via JDBC, and the chosen database is MySQL.

## 3. System Modules

- **Client module (player)** – handles the player's display: shows the lobby, game board, game history, and profile. Communicates with the server to send moves and receive updates.
- **Admin module** – handles the admin's display: dashboard, user management, game type management, and monitoring active sessions. Communicates with the server to fetch and manage data.
- **Server module** – handles user creation and login, pairing opponents (matchmaking), managing game sessions (rules engine, move validation, turn management, and end detection), saving and loading data from the DB, and sending asynchronous messages to players.

**Important note:** Game logic and rules are implemented on the server only (an authoritative server). The client draws the board and sends a requested move, and the server is the one that validates it, applies it, and updates both sides. This prevents cheating by the client, and both players always see the same, consistent board state.

## 4. System Layers

### Client module

- **Presentation layer** – displays the player's screens using JavaFX, receives data to display from the logic layer, and passes it the player's requests and entered moves.
- **Logic layer** – receives data from the server via the communication layer, processes it and passes it to the presentation layer, and vice versa: receives input from the presentation layer and passes it to the communication layer.
- **Server communication layer** – sends commands to the server via RMI, and listens for asynchronous messages from the server as a JMS consumer; passes both to the logic layer.

### Admin module

- **Presentation layer** – displays the admin's screens using JavaFX, receives data to display from the logic layer, and passes it the admin's requests.
- **Logic layer** – receives data from the server via the communication layer, processes it and passes it to the presentation layer, and vice versa.
- **Server communication layer** – performs the actual communication with the server (RMI and JMS) and passes it to the logic layer.

### Server module

- **Client/admin communication layer** – implements the RMI remote objects and the JMS message producers/consumers; performs the actual sending and receiving.
- **Logic layer** – handles authentication and login, opponent pairing, and the game engine (rule enforcement, turn management, board-state calculation, and win/end detection), and manages the active game sessions running simultaneously.
- **Data layer** – performs queries via JDBC to save data to the database and to fetch data from it (DAO layer).

## 5. Handling Edge Cases and Consistency

- **Atomic pairing** – pairing is carried out by a single, synchronized matchmaking component. Within a single transaction it takes the waiting player, creates the game session, and removes both records from the queue. This prevents a situation where two threads pair the same player twice, and guarantees correct support for multiple pairs playing simultaneously.
- **Authorization** – before accepting a move, or registering and sending to a session's topic, the server verifies that the player is indeed a participant in that session, and that the one making the move is the one whose turn it is (per `CurrentTurnUserID`). This prevents viewing or interfering with other players' games.
- **Disconnect detection** – since RMI and JMS do not notify about disconnection on their own, the client periodically calls a `keepAlive` method on the server. If a player is silent beyond the allotted time, the server ends the game in an `ABANDONED` state and awards the opponent the win.
- **Timeout and rematch** – when the turn time limit is exceeded (per `TurnStartedAt`), the server ends the turn automatically. A Rematch button creates a new session with the same two players, with the turn order swapped.
- **Admin monitoring** – the admin subscribes read-only to the topics of active sessions, thereby following them in real time without the ability to influence the game.

## 6. System Architecture

The following diagram describes the three modules and the communication between them. Each client module is built from three layers (presentation, logic, communication), the server is built from three layers (communication, logic, data), and each client communicates with the server at the communication layer via RMI and JMS. The server accesses the MySQL database via JDBC.

**[Diagram: System Architecture]**
- **Player Client** box (three stacked layers, top to bottom): Presentation (JavaFX) → Logic → Communication.
- **Admin Client** box (three stacked layers, top to bottom): Presentation (JavaFX) → Logic → Communication.
- **Server** box (three stacked layers, top to bottom): Communication → Logic → Data.
- Server ↔ Player Client, labeled "RMI + JMS".
- Server ↔ Admin Client, labeled "RMI + JMS".
- Server ↔ MySQL Database (cylinder), labeled "JDBC".

## 7. Database Description

The database includes 6 tables:

### 7.1 Table `User` – user details (players and admins)
Stores user details, including login data, admin permission, and rating data.

| Field | Type | Key | Description |
|---|---|---|---|
| ID | INT | PK | Unique user identifier, primary key of the table |
| Username | VARCHAR | Unique | The username used to log in; must be unique |
| Password | VARCHAR | | The user's password, stored as a hash with salt (e.g., bcrypt) |
| IsAdmin | BOOLEAN | | Boolean field: whether the user is also an admin |
| Wins | INT | | Number of wins for the player |
| Losses | INT | | Number of losses for the player |
| Draws | INT | | Number of draws for the player |
| Rating | INT | | The player's rating (ELO), updated at the end of each game |
| CreatedAt | DATETIME | | The user's registration date/time |

### 7.2 Table `GameType` – catalog of supported game types
Lets the server manage several different kinds of games at once. Adding a new game type is done by adding a record to this table.

| Field | Type | Key | Description |
|---|---|---|---|
| ID | INT | PK | Unique identifier of the game type |
| Name | VARCHAR | | The game's name (e.g., Checkers) |
| Description | TEXT | | Description of the game and its rules |
| MinPlayers | INT | | Minimum number of players required |
| MaxPlayers | INT | | Maximum number of players |
| BoardRows | INT | | Number of board rows |
| BoardCols | INT | | Number of board columns |

### 7.3 Table `GameSession` – a single game session between two players
Represents a single game: who is playing, its status, whose turn it is, the current board state, and who the winner is. The `BoardState` field is the authoritative source for the live game state, while the `Move` table is an append-only move log for reconstruction and record-keeping.

| Field | Type | Key | Description |
|---|---|---|---|
| ID | INT | PK | Unique identifier of the game session |
| GameTypeID | INT | FK | Points to `GameType`, the game's type |
| Player1ID | INT | FK | Points to `User`, the first player |
| Player2ID | INT | FK | Points to `User`, the second player |
| Status | ENUM | | Session status: `ACTIVE` / `FINISHED` / `ABANDONED` |
| CurrentTurnUserID | INT | FK | Points to `User`, whose turn it currently is |
| TurnStartedAt | DATETIME | | Start time of the current turn (used to calculate the move timeout) |
| WinnerID | INT | FK | Points to `User` (can be empty); the winner, if the game has ended |
| BoardState | TEXT | | Current board state, in a serialized representation, for reconstruction and reconnection |
| StartTime | DATETIME | | Start time of the session |
| EndTime | DATETIME | | End time of the session |

### 7.4 Table `Move` – record of every move in a session
Stores every move made in a session, in order, for validation, record-keeping, and game reconstruction.

| Field | Type | Key | Description |
|---|---|---|---|
| ID | INT | PK | Unique identifier of the move |
| SessionID | INT | FK | Points to `GameSession`, the session in which the move was made |
| UserID | INT | FK | Points to `User`, the player who made the move |
| MoveNumber | INT | | Sequential number of the move within the session |
| Payload | TEXT | | Description of the move in a serialized format (JSON). A flexible structure describing the full move, including sequences of jumps and captures in checkers, and also suitable for other game types |
| CreatedAt | DATETIME | | Time the move was made |

### 7.5 Table `MatchmakingQueue` – queue of players waiting to be paired
Manages the players waiting for an opponent. When a second player enters for the same game type, the server pairs them and removes both from the queue.

| Field | Type | Key | Description |
|---|---|---|---|
| ID | INT | PK | Unique identifier of the waiting record |
| UserID | INT | FK | Points to `User`, the waiting player |
| GameTypeID | INT | FK | Points to `GameType`, the requested game type |
| Status | ENUM | | Record status: `WAITING` / `MATCHED` / `CANCELLED` |
| JoinedAt | DATETIME | | Time joined the queue (also used to determine pairing order) |

### 7.6 Table `ChatMessage` – chat messages during a session
Stores the chat messages exchanged between the two players during a game session.

| Field | Type | Key | Description |
|---|---|---|---|
| ID | INT | PK | Unique identifier of the message |
| SessionID | INT | FK | Points to `GameSession`, the session the message belongs to |
| UserID | INT | FK | Points to `User`, the sender of the message |
| Content | TEXT | | The message content |
| SentAt | DATETIME | | Time the message was sent |

**Note:** The `Wins`, `Losses`, `Draws`, and `Rating` fields in the `User` table are updated in the same transaction in which `WinnerID` and `EndTime` are set on the session, so that the counters never drift from the actual game results.

## 8. Database Relationships

**[Diagram: Entity Relationship Diagram (ERD)]** — entities: `GameType`, `GameSession`, `Move`, `MatchmakingQueue`, `User`, `ChatMessage`.

- `GameType` to `GameSession` (1:N) – a game type can have many sessions, and each session belongs to one game type.
- `User` to `GameSession` (1:N, twice) – via `Player1ID` and `Player2ID`. A player can take part in many sessions, and each session links two players.
- `GameSession` to `Move` (1:N) – a session has many moves, and each move belongs to one session.
- `User` to `Move` (1:N) – a player makes many moves over the course of games.
- `User` to `MatchmakingQueue` (1:N) – in practice each player has at most one active waiting record at any given moment.
- `GameType` to `MatchmakingQueue` (1:N) – each game type can have its own waiting queue.
- `GameSession` to `ChatMessage` (1:N) – a session can have many chat messages.
- `User` to `ChatMessage` (1:N) – a player can send many messages.

## 9. User Screens

The following are wireframes of the player's screens. They can be created in PowerPoint or drawn on paper and photographed.

### 9.1 Login / Registration screen
Nav bar: Home | Login | Register.
- **Login** box: Username field, Password field, "Login" button.
- **Register** box: Username field, Password field, Repeat Password field, "Register" button.
- A branding panel on the side reading "PlayPair Game Server" *(note: this wireframe uses the name "PlayPair" rather than "MatchMaker" — likely a leftover/placeholder name; worth reconciling with the product name before implementation)*.

### 9.2 Lobby screen – choose game, find opponent, stats, and leaderboard
Nav bar: Home | Lobby | Profile | Logout.
- **Choose a game** box: game buttons (Checkers, Chess, Battleship) and a "Find Opponent" button.
- **Your stats** box: Player name, Rating, Wins/Losses/Draws, number of online players.
- **Leaderboard** table: columns Rank, Player, Rating, W/L, listing top-ranked players.

### 9.3 Find Opponent screen (Matchmaking)
Nav bar: Home | Lobby | Profile | Logout.
- Shows the selected game (e.g., "Checkers"), the text "Searching for an opponent…", the player's position in the queue, elapsed time, a progress/loading indicator, and a "Cancel" button.

### 9.4 Game board screen – board, opponent info, turn indicator, chat, and resign button
Nav bar: Home | Lobby | Resign.
- The game board (checkers grid with pieces) and a "Your pieces: [color]" label.
- **Match info** box: opponent name and rating, whose turn it is, time left for the move, capture count (you vs. opponent), and a "Resign" button.
- **Chat** box: message history between the two players, a text input field, and a "Send" button.

### 9.5 Game end / result screen
Nav bar: Home | Lobby | Profile.
- Result box: outcome (e.g., "You Win!"), opponent's name, rating change (before → after, with delta), and "Rematch" / "Back to Lobby" buttons.

## 10. Admin Screens

### 10.1 Admin dashboard – live stats and active sessions
Nav bar: Admin Home | Games | Users | Sessions.
- Summary tiles: online players, active games, games played today, players open in queue.
- **Active sessions** table: columns Session, Game, Player 1, Player 2, Turn, Started.

### 10.2 Add new game type screen
Nav bar: Admin Home | Games | Users | Sessions.
- A game icon placeholder panel.
- A form with fields: Name, Description, Min Players, Max Players, Board Rows, Board Cols, and a "Submit" button.

> *(In the source document, this section contains an out-of-place English editor's note: "Adding this tentatively" — appears to be a leftover draft comment rather than part of the spec content.)*

### 10.3 Monitor an active session (Live Game Monitor)
Nav bar: Admin Home | Games | Users | Sessions.
- Title: "Live Game Monitor (Session #…)".
- The live board state for the selected session.
- **Session details** box: Game type, Player 1 (with rating), Player 2 (with rating), Status, whose turn, moves played, start time, duration.
- Actions: "Force End Session" and "Export Log" buttons.

---

### Notes on translation
This spec is a line-by-line English translation/transcription of the original Hebrew document `MatchMaker.v1 (1).docx`, preserving section order, all table contents, and descriptions of all embedded diagrams/wireframes. Two minor inconsistencies in the source document are flagged inline above: the product is titled "MatchMaker" throughout the text but the wireframe images are branded "PlayPair Game Server," and the "Add Game Type" screen section contains a stray editorial note.
