# Design: Game Engine (Roadmap Step 7)

## Context

Steps 1–6 are merged to `main`: contracts, RMI server skeleton, JDBC/DAO layer, matchmaking queue, and JMS notification for `MATCH_FOUND`. `PlayerServiceImpl.makeMove()` is still a stub throwing `UnsupportedOperationException`. This is roadmap step 7: a real `GameEngine` interface with a `CheckersEngine` implementation, wired into `makeMove`, persisting `Move` rows and `GameSession.BoardState`.

Spec section 2 describes checkers as the first supported game type, with move payloads as "a flexible structure describing the full move, including sequences of jumps and captures." Spec section 5 requires per-move authorization: the caller must be a participant in the session, and it must be their turn. Spec section 7.3's note requires `Wins`/`Losses`/`Draws`/`Rating` to update in the same transaction as `WinnerID`/`EndTime`.

## Decisions

Worked through with the user, compressed but real:

1. **Both `BoardState` and `Move.Payload` are JSON, using chess-style algebraic square names (`a1`–`h8`)**, via a new `org.json:json` dependency — the same "one small single-purpose library" pattern as `jbcrypt`. Rejected a plain positional string for `BoardState` (opaque, inconsistent with `Move.Payload`, hardcodes 8×8) and rejected standard checkers' own 1–32 square numbering (real, but has multiple regional counting-direction conventions — algebraic is unambiguous and, since the lobby wireframe already lists Chess as a future game type, directly reusable later). File `a`–`h` = column 0–7, rank `1`–`8` = row 0–7 — same mapping real chess uses, and it falls out of the orientation in decision 2 below for free.
   - `BoardState`: a **sparse** map of only the occupied squares (checkers only ever uses one color of square, and pieces get captured, so most squares are empty): `{"rows":8,"cols":8,"pieces":{"b6":"b","d6":"b","f6":"b","c3":"w","e3":"w",...}}`. A square with no key is empty — no need to store 40+ always-empty entries.
   - `Move.Payload`: a path of algebraic squares: `{"path":["b6","c5"]}` for a simple step, `{"path":["b6","d4","f2"]}` for a multi-jump chain. A path of length 2 is a simple step; length > 2 is a capture chain. One shape covers both move kinds, so the wire format never needs a "move type" discriminator.
2. **Board orientation is a fixed convention, not configurable.** Player1 (the earlier-queued player in a match — already the "moves first" player per `JdbcMatchmakingQueue`'s existing convention) starts on rank 1 (`b`, rows 0–2), Player2 starts on the rank 8 side (`w`, rows 5–7) — rank 1 is Player1's back rank, exactly like white's back rank in real chess. Player1's men move toward increasing ranks, Player2's toward decreasing; kings move either direction. A man promotes to a king on reaching the far rank (rank 8 for Player1, rank 1 for Player2).
3. **Mandatory capture + multi-jump chaining, matching real checkers.** If any of the mover's pieces has a legal capture available anywhere on the board, a non-capturing move is illegal — the player must play a capture. After a capture, if the same piece has a further capture available from its new square, the chain must continue as part of the same move (encoded as one longer `path`). This is enumerated once and reused for two purposes: validating the submitted move, and checking whether the player *about to move* has any legal move at all (used by `checkWinner`).
4. **Draw detection is out of scope.** Real checkers draw conditions (repetition, move-count-without-capture limits) add real complexity for a rare outcome. A player loses when they have zero pieces left, or have pieces but no legal move on their turn (mandatory-capture-aware). No draw path exists yet; `GameResult` still has a `DRAW` case reserved for a later step rather than removed, so it doesn't require another breaking change when draw detection eventually lands.
5. **JMS ("opponent made a move" push) is explicitly deferred to its own step**, not bundled into this one. The spec's per-session Topic (distinct from step 6's per-player Queue, which only exists because no session exists yet at `MATCH_FOUND` time) is real scope, but step 7 stays RMI-only: the mover gets their own updated `GameStateDTO` back synchronously from `makeMove`; the opponent isn't proactively notified yet. This mirrors how step 5 (matchmaking) explicitly deferred its own notification gap to step 6 rather than growing step 5 to cover it.
6. **`GameSessionDao` gains real write methods**, rather than a separate DAO-bypassing component. The matchmaking queue design doc explicitly flagged this DAO's lack of `insert()`/update methods as a deliberate, temporary gap ("this pairing operation is a genuinely self-contained atomic unit; routing it through a cross-DAO transaction seam would be more machinery than a two-table insert-and-delete needs"). Step 7's work — updating `GameSession` fields, inserting a `Move` row, updating `User` rating counters — is squarely DAO-layer CRUD, unlike matchmaking's two-table pairing operation, so it belongs on `GameSessionDao` itself.
7. **"Session isn't ACTIVE" (e.g. a stale or already-finished session id) reuses `IllegalMoveException`** rather than adding a new checked exception to the `PlayerService` RMI interface. Keeps the interface unchanged for this step.
8. **Rating uses standard ELO, K=32, updated in the same transaction as `WinnerID`/`EndTime`/the win-loss-draw counters** — required by spec section 7.3's consistency note, and a game just ending (via `checkWinner`) makes this unavoidable to implement correctly rather than deferrable.

## Architecture

New package `com.matchmaker.server.game` — already reserved for this in `project-structure.md`:

- **`GameEngine`** (interface):
  - `String initialBoardState()` — the standard starting position, as the sparse-pieces JSON string described in decision 1.
  - `boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move)` — mandatory-capture-aware.
  - `String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move)` — assumes the move was already validated; returns the new board JSON string (including promotions).
  - `GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext)` — evaluated against the player who is now due to move; returns `CONTINUE`, `PLAYER1_WINS`, `PLAYER2_WINS`, or the reserved-but-unused `DRAW`.
- **`Square`** (small value type, `server.game` package): converts between an algebraic square name (`"b6"`) and internal `(row, col)` coordinates — the one place the file/rank ↔ row/col mapping from decision 2 is implemented, so `CheckersEngine`, `Move`, and `BoardState` parsing all go through the same conversion rather than each re-deriving it.
- **`Move`** (small value type, `server.game` package — not a DTO, never crosses RMI/JMS): wraps the parsed `path` as a `List<Square>`. `MoveDTO.payload` (the JSON string) is parsed into a `Move` by `PlayerServiceImpl` before calling the engine.
- **`CheckersEngine`** (the real `GameEngine` implementation) — internally uses a private grid helper (parses the sparse `pieces` JSON object into an 8×8 array via `Square`, generates legal moves per piece including capture chains, applies a validated move, re-serializes back to the sparse JSON form). Pure logic, no I/O.
- No test-fake needed for this package, unlike the DAO/matchmaking/JMS packages — `CheckersEngine` has no I/O or external dependency to fake around; tests use the real thing directly.

### `GameSessionDao` additions

- `Optional<GameStateDTO> findActiveById(int sessionId)` — used to load the session `makeMove` is acting on.
- `GameStateDTO recordMove(int sessionId, int movingUserId, int moveNumber, String movePayloadJson, String newBoardState, GameResult result, int player1Id, int player2Id)` (exact signature to be finalized in the implementation plan) — one transaction:
  1. `INSERT` the `Move` row.
  2. `UPDATE GameSession` — always sets `BoardState`, flips `CurrentTurnUserID` to the *other* player, resets `TurnStartedAt`; if `result != CONTINUE`, additionally sets `Status='FINISHED'`, `WinnerID`, `EndTime`.
  3. If the game ended: `UPDATE User` for both players — increment `Wins`/`Losses` (or `Draws`, once that path exists) and apply the ELO delta to `Rating` for each.
  4. Commit; return the freshly-updated `GameStateDTO`.

Any `SQLException` rolls back and is wrapped in the existing `DaoException`, matching every other DAO.

## `makeMove()` logic

In `PlayerServiceImpl.makeMove(sessionToken, gameSessionId, movePayload)`:

1. Resolve `userId` from `sessionToken` (existing `AuthenticationException` path, unchanged).
2. `gameSessionDao.findActiveById(gameSessionId)` → if absent or `Status != ACTIVE`, throw `IllegalMoveException`.
3. If `userId` isn't `Player1ID` or `Player2ID` of the session, throw `NotParticipantException`.
4. If `userId != CurrentTurnUserID`, throw `NotYourTurnException`.
5. Parse `movePayload` (JSON) into a `Move`; malformed JSON → `IllegalMoveException`.
6. `gameEngine.isLegalMove(session.getBoardState(), isPlayer1Turn, move)` → false → `IllegalMoveException`.
7. `String newBoard = gameEngine.applyMove(...)`.
8. `GameResult result = gameEngine.checkWinner(newBoard, isPlayer1ToMoveNext=!isPlayer1Turn)`.
9. `gameSessionDao.recordMove(...)` — one transaction, as above.
10. Return the updated `GameStateDTO`.

## Wiring

- `pom.xml`: add `org.json:json`.
- New `server/game/GameEngine.java`, `server/game/CheckersEngine.java`, `server/game/Move.java`, `server/game/GameResult.java` (or similar small value types — exact file boundaries finalized in the implementation plan).
- `server/dao/GameSessionDao.java` / `JdbcGameSessionDao.java`: add `findActiveById()` and `recordMove()`.
- `PlayerServiceImpl`: constructor gains a `GameEngine` parameter; `makeMove()` implemented per the logic above.
- `ServerMain`: constructs one `CheckersEngine`, passes it into `PlayerServiceImpl`.

## Testing

- **`CheckersEngine` unit tests** (no DB, no JMS — pure logic, the bulk of this step's test coverage): initial board layout; single-step legality (including illegal backward-for-a-man moves); simple capture; multi-jump chain legality and application; mandatory capture (a non-capturing move is illegal when a capture exists anywhere for that player); king promotion; `checkWinner` for "no pieces left" and "no legal move" losses; illegal-move rejection for occupied destinations, non-diagonal moves, out-of-bounds paths, and moving the opponent's piece.
- **`GameSessionDao` DB-integration tests** (real MySQL, joins the existing four Docker-required test classes): `findActiveById` found/absent; `recordMove` mid-game (board/turn update, `Move` row inserted, no rating change); `recordMove` game-ending (Status/WinnerID/EndTime set, both players' Wins/Losses/Rating updated correctly in one transaction).
- **`PlayerServiceImplTest`** (Docker-free, real `CheckersEngine` + an in-memory `GameSessionDao` fake extended with the new methods): `makeMove` happy path; `NotParticipantException`/`NotYourTurnException`/`IllegalMoveException` paths; `makeMove` graduates off the remaining-stubs aggregate test.

## Out of scope (deferred, not forgotten)

- **JMS "opponent made a move" push and the per-session Topic it needs** — its own step, mirroring 5→6.
- **Turn timeout enforcement** — `TurnStartedAt` is set/reset by `recordMove` but nothing proactively checks it yet; that's roadmap step 10.
- **Resign, rematch** — `PlayerServiceImpl.resign()`/`.rematch()` remain stubs; roadmap steps 7 (resign, per build-plan's original step 7 line) / 10 (rematch) — actual step assignment to confirm when those are picked up.
- **Draw detection** — `GameResult.DRAW` exists as a reserved case but nothing produces it.
- **Chat** (`sendChatMessage`) — untouched by this step.
