# Game Engine (Roadmap Step 7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A real `GameEngine`/`CheckersEngine` wired into `PlayerServiceImpl.makeMove()`, with full checkers rules (mandatory capture, multi-jump, king promotion), persisted `Move` rows and `GameSession.BoardState`, and ELO rating updates on game end — all synchronous over RMI, no JMS push yet.

Full design rationale: `docs/superpowers/specs/2026-08-11-game-engine-design.md`.

## Global Constraints

- `BoardState` and `Move.Payload` are both JSON using chess-style algebraic square names (`a1`–`h8`), via a new `org.json:json` dependency.
- File `a`–`h` = column 0–7, rank `1`–`8` = row 0–7. Player1 starts on rank 1 (`b`/`B`), moves toward increasing ranks. Player2 starts on rank 8 side (`w`/`W`), moves toward decreasing ranks. Kings move either direction.
- Playable (dark) squares are exactly those where `(row + col) % 2 == 1` — the standard checkerboard pattern where each starting row has pieces on every other square.
- Mandatory capture: if any of the mover's pieces has a legal capture available anywhere on the board, only capture moves are legal. A capture chain must continue as long as further jumps are available from the landing square with the same piece — **except that promotion ends the chain immediately**, even if the newly-crowned king could jump again. This is a real, common simplified-rule variant (avoids the ambiguity of whether a piece captures as a man or a king mid-chain).
- No "majority capture" rule: if a player has a choice between two non-overlapping capture chains of different lengths, either is legal — the engine doesn't force the longer one. Only "you must capture, and must keep capturing with this piece while a further jump is available" is enforced.
- Kings do not "fly" (no long-range diagonal sliding, unlike international draughts) — they move exactly one square per step/jump, same as men, just in either direction.
- `CheckersEngine` is pure logic (no I/O). No test-fake needed for `GameEngine` — tests use `CheckersEngine` directly.

---

### Task 1: `org.json` dependency + `Square` value type

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/matchmaker/server/game/Square.java`
- Test: `src/test/java/com/matchmaker/server/game/SquareTest.java`

**Interfaces:**
- Produces: `Square(int row, int col)` record — `Square.fromAlgebraic(String)`, `.toAlgebraic()`, `.isInBounds()`. Every later task's board/move parsing goes through this.

- [ ] **Step 1: Add the `org.json` dependency to `pom.xml`**

Add inside the existing `<dependencies>` block (after the ActiveMQ dependencies):

```xml
        <dependency>
            <groupId>org.json</groupId>
            <artifactId>json</artifactId>
            <version>20240303</version>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

```java
package com.matchmaker.server.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquareTest {

    @Test
    void fromAlgebraic_parsesFileAndRankIntoRowAndCol() {
        assertEquals(new Square(0, 0), Square.fromAlgebraic("a1"));
        assertEquals(new Square(0, 1), Square.fromAlgebraic("b1"));
        assertEquals(new Square(5, 1), Square.fromAlgebraic("b6"));
        assertEquals(new Square(7, 7), Square.fromAlgebraic("h8"));
    }

    @Test
    void toAlgebraic_isTheInverseOfFromAlgebraic() {
        assertEquals("a1", new Square(0, 0).toAlgebraic());
        assertEquals("b6", new Square(5, 1).toAlgebraic());
        assertEquals("h8", new Square(7, 7).toAlgebraic());
    }

    @Test
    void isInBounds_trueForZeroToSeven_falseOutsideThatRange() {
        assertTrue(new Square(0, 0).isInBounds());
        assertTrue(new Square(7, 7).isInBounds());
        assertFalse(new Square(-1, 0).isInBounds());
        assertFalse(new Square(0, 8).isInBounds());
        assertFalse(new Square(8, 0).isInBounds());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn test -Dtest=SquareTest`
Expected: compile error — `Square` does not exist yet.

- [ ] **Step 4: Write the implementation**

```java
package com.matchmaker.server.game;

public record Square(int row, int col) {

    public static Square fromAlgebraic(String algebraic) {
        char file = algebraic.charAt(0);
        char rank = algebraic.charAt(1);
        return new Square(rank - '1', file - 'a');
    }

    public String toAlgebraic() {
        char file = (char) ('a' + col);
        char rank = (char) ('1' + row);
        return "" + file + rank;
    }

    public boolean isInBounds() {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=SquareTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/matchmaker/server/game/Square.java src/test/java/com/matchmaker/server/game/SquareTest.java
git commit -m "Add org.json dependency and Square algebraic-coordinate value type"
```

---

### Task 2: `Move` value type + `GameResult` enum + `GameEngine` interface

**Files:**
- Create: `src/main/java/com/matchmaker/server/game/Move.java`
- Create: `src/main/java/com/matchmaker/server/game/GameResult.java`
- Create: `src/main/java/com/matchmaker/server/game/GameEngine.java`
- Test: `src/test/java/com/matchmaker/server/game/MoveTest.java`

**Interfaces:**
- Consumes: `Square` (Task 1).
- Produces: `Move.fromJson(String)`, `Move.getPath()` returning `List<Square>`. `GameResult` enum (`CONTINUE`, `PLAYER1_WINS`, `PLAYER2_WINS`, `DRAW`). `GameEngine` interface — `CheckersEngine` (Tasks 4–9) implements it; `PlayerServiceImpl` (Task 11) depends on it.

- [ ] **Step 1: Write the failing test**

```java
package com.matchmaker.server.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveTest {

    @Test
    void fromJson_parsesASimpleTwoSquarePath() {
        Move move = Move.fromJson("{\"path\":[\"b6\",\"c5\"]}");

        assertEquals(List.of(new Square(5, 1), new Square(4, 2)), move.getPath());
    }

    @Test
    void fromJson_parsesAMultiJumpPath() {
        Move move = Move.fromJson("{\"path\":[\"b6\",\"d4\",\"f2\"]}");

        assertEquals(
                List.of(new Square(5, 1), new Square(3, 3), new Square(1, 5)),
                move.getPath());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=MoveTest`
Expected: compile error — `Move` does not exist yet.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/matchmaker/server/game/Move.java`:

```java
package com.matchmaker.server.game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Move {

    private final List<Square> path;

    public Move(List<Square> path) {
        this.path = path;
    }

    public List<Square> getPath() {
        return path;
    }

    public static Move fromJson(String json) {
        JSONObject obj = new JSONObject(json);
        JSONArray pathArray = obj.getJSONArray("path");
        List<Square> path = new ArrayList<>();
        for (int i = 0; i < pathArray.length(); i++) {
            path.add(Square.fromAlgebraic(pathArray.getString(i)));
        }
        return new Move(path);
    }
}
```

`src/main/java/com/matchmaker/server/game/GameResult.java`:

```java
package com.matchmaker.server.game;

public enum GameResult {
    CONTINUE,
    PLAYER1_WINS,
    PLAYER2_WINS,
    DRAW
}
```

`src/main/java/com/matchmaker/server/game/GameEngine.java`:

```java
package com.matchmaker.server.game;

public interface GameEngine {

    String initialBoardState();

    boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move);

    String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move);

    GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=MoveTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/game/Move.java src/main/java/com/matchmaker/server/game/GameResult.java src/main/java/com/matchmaker/server/game/GameEngine.java src/test/java/com/matchmaker/server/game/MoveTest.java
git commit -m "Add Move, GameResult, and the GameEngine interface"
```

---

### Task 3: `CheckersEngine` — initial board and board JSON round-trip

**Files:**
- Create: `src/main/java/com/matchmaker/server/game/CheckersBoard.java`
- Create: `src/main/java/com/matchmaker/server/game/CheckersEngine.java`
- Test: `src/test/java/com/matchmaker/server/game/CheckersEngineTest.java`

**Interfaces:**
- Consumes: `Square` (Task 1), `Move`/`GameResult`/`GameEngine` (Task 2).
- Produces: `CheckersEngine implements GameEngine` — only `initialBoardState()` is real in this task; the other three methods are added incrementally in Tasks 4–7. `CheckersBoard` (package-private) — the internal grid representation every later task builds on.

This task establishes the board representation itself, tested through the public `initialBoardState()` method plus a direct round-trip check (parse what you just serialized and get the same pieces back) — both go through `CheckersBoard`, so this is really testing that class via the one method of `CheckersEngine` that exists so far.

- [ ] **Step 1: Write the failing test**

```java
package com.matchmaker.server.game;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckersEngineTest {

    private final CheckersEngine engine = new CheckersEngine();

    @Test
    void initialBoardState_hasTwelvePiecesPerSideOnTheStandardSquares() {
        JSONObject board = new JSONObject(engine.initialBoardState());

        assertEquals(8, board.getInt("rows"));
        assertEquals(8, board.getInt("cols"));

        JSONObject pieces = board.getJSONObject("pieces");
        assertEquals(24, pieces.length());

        // Spot-check a few known starting squares (rank 1-3 = player1 'b', rank 6-8 = player2 'w').
        assertEquals("b", pieces.getString("b1"));
        assertEquals("b", pieces.getString("a2"));
        assertEquals("b", pieces.getString("d3"));
        assertEquals("w", pieces.getString("a6"));
        assertEquals("w", pieces.getString("h7"));
        assertEquals("w", pieces.getString("b8"));

        // The two middle ranks (4 and 5) start empty -- spot-check one square from each.
        assertEquals(false, pieces.has("a4"));
        assertEquals(false, pieces.has("b5"));

        // Light squares are never occupied, even on the starting ranks.
        assertEquals(false, pieces.has("a1"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: compile error — `CheckersEngine` does not exist yet.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/matchmaker/server/game/CheckersBoard.java`:

```java
package com.matchmaker.server.game;

import org.json.JSONObject;

import java.util.Arrays;

class CheckersBoard {

    private final char[][] grid = new char[8][8];

    private CheckersBoard() {
        for (char[] row : grid) {
            Arrays.fill(row, '.');
        }
    }

    static CheckersBoard initial() {
        CheckersBoard board = new CheckersBoard();
        for (int row = 0; row <= 2; row++) {
            for (int col = 0; col < 8; col++) {
                if (isDarkSquare(row, col)) {
                    board.grid[row][col] = 'b';
                }
            }
        }
        for (int row = 5; row <= 7; row++) {
            for (int col = 0; col < 8; col++) {
                if (isDarkSquare(row, col)) {
                    board.grid[row][col] = 'w';
                }
            }
        }
        return board;
    }

    static CheckersBoard fromJson(String json) {
        CheckersBoard board = new CheckersBoard();
        JSONObject obj = new JSONObject(json);
        JSONObject pieces = obj.getJSONObject("pieces");
        for (String squareName : pieces.keySet()) {
            Square square = Square.fromAlgebraic(squareName);
            board.grid[square.row()][square.col()] = pieces.getString(squareName).charAt(0);
        }
        return board;
    }

    String toJson() {
        JSONObject pieces = new JSONObject();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                char piece = grid[row][col];
                if (piece != '.') {
                    pieces.put(new Square(row, col).toAlgebraic(), String.valueOf(piece));
                }
            }
        }
        JSONObject obj = new JSONObject();
        obj.put("rows", 8);
        obj.put("cols", 8);
        obj.put("pieces", pieces);
        return obj.toString();
    }

    CheckersBoard copy() {
        CheckersBoard copy = new CheckersBoard();
        for (int row = 0; row < 8; row++) {
            copy.grid[row] = grid[row].clone();
        }
        return copy;
    }

    char get(Square square) {
        return grid[square.row()][square.col()];
    }

    void set(Square square, char piece) {
        grid[square.row()][square.col()] = piece;
    }

    boolean isEmpty(Square square) {
        return get(square) == '.';
    }

    private static boolean isDarkSquare(int row, int col) {
        return (row + col) % 2 == 1;
    }

    static boolean isPlayer1Piece(char piece) {
        return piece == 'b' || piece == 'B';
    }

    static boolean isPlayer2Piece(char piece) {
        return piece == 'w' || piece == 'W';
    }

    static boolean isKing(char piece) {
        return piece == 'B' || piece == 'W';
    }

    static boolean ownedBy(char piece, boolean isPlayer1) {
        return isPlayer1 ? isPlayer1Piece(piece) : isPlayer2Piece(piece);
    }
}
```

`src/main/java/com/matchmaker/server/game/CheckersEngine.java` (skeleton — Tasks 4–7 fill in the three `throw` lines):

```java
package com.matchmaker.server.game;

public class CheckersEngine implements GameEngine {

    @Override
    public String initialBoardState() {
        return CheckersBoard.initial().toJson();
    }

    @Override
    public boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        throw new UnsupportedOperationException("isLegalMove not implemented yet -- see game-engine-implementation.md Task 4");
    }

    @Override
    public String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        throw new UnsupportedOperationException("applyMove not implemented yet -- see game-engine-implementation.md Task 7");
    }

    @Override
    public GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext) {
        throw new UnsupportedOperationException("checkWinner not implemented yet -- see game-engine-implementation.md Task 8");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/game/CheckersBoard.java src/main/java/com/matchmaker/server/game/CheckersEngine.java src/test/java/com/matchmaker/server/game/CheckersEngineTest.java
git commit -m "Add CheckersBoard and CheckersEngine.initialBoardState()"
```

---

### Task 4: `CheckersEngine` — legal-move enumeration (non-capture steps only)

**Files:**
- Modify: `src/main/java/com/matchmaker/server/game/CheckersEngine.java`
- Modify: `src/test/java/com/matchmaker/server/game/CheckersEngineTest.java`

**Interfaces:**
- Produces: a private `List<Move> legalMoves(CheckersBoard board, boolean isPlayer1Turn)` on `CheckersEngine` — the core enumeration every later task (mandatory capture in Task 5, `checkWinner` in Task 8) reuses. `isLegalMove()` becomes real for the non-capture case.

This task deliberately ignores captures entirely — a board with no captures available anywhere, testing only: diagonal one-step legality, forward-only for men, both directions for kings, rejecting occupied destinations, rejecting non-diagonal or out-of-bounds moves, and rejecting a player trying to move a piece they don't own.

- [ ] **Step 1: Write the failing tests**

Add to `CheckersEngineTest`:

```java
    @Test
    void isLegalMove_manMovingOneStepDiagonallyForwardOntoEmptySquare_isLegal() {
        // Player1's man on b1 moving forward to a2 or c2 (both empty at game start).
        Move move = Move.fromJson("{\"path\":[\"b1\",\"a2\"]}");

        assertTrue(engine.isLegalMove(engine.initialBoardState(), true, move));
    }

    @Test
    void isLegalMove_manMovingBackward_isIllegal() {
        // Player1's man has no piece on a3 to move from at game start, but b3 does have one
        // for player1... actually b3 is empty at start (rank 3 is 'b' pieces on dark squares:
        // a3, c3, e3, g3). Use a3 (player1 man) trying to move backward to b2 (illegal
        // direction for a man).
        Move move = Move.fromJson("{\"path\":[\"a3\",\"b2\"]}");

        assertFalse(engine.isLegalMove(engine.initialBoardState(), true, move));
    }

    @Test
    void isLegalMove_movingOntoOccupiedSquare_isIllegal() {
        // b1 (player1) can't move to c2 if c2 is already occupied -- but c2 is empty at
        // start, so instead check a3 (player1) can't move to b4... b4 is empty too. Use a
        // same-side blocked case: b1 -> a2 is legal (checked above); c1 -> b2 is also legal
        // since b2 is empty. To get a genuinely occupied target, move a player2 man
        // "backward" into player1's own occupied square: c3 (player1) is occupied, so
        // player1's own a3 man cannot move onto it if adjacency allowed -- but a3 and c3
        // aren't adjacent. Use the direct case: e1 (player1) -> d2 is legal (d2 empty);
        // reject by moving onto a square we know is occupied at game start, e.g. player1
        // piece at c1 "moving" onto b1 is not diagonal-adjacent by one step in a legal
        // direction anyway. Simplest genuine case: attempt e3 (player1) -> f4, then again
        // e3 -> d4 twice in a row is not how single-move legality works -- just assert
        // moving onto a starting player2 piece is illegal because it's occupied AND out of
        // reach; use a constructed mid-game board instead for clarity.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"c3\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_nonDiagonalStep_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"b3\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_movingOpponentsPiece_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"c3\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"c3\",\"b4\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_kingMovesEitherDiagonalDirection() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"d4\":\"B\"}}";

        assertTrue(engine.isLegalMove(board, true, Move.fromJson("{\"path\":[\"d4\",\"e5\"]}")));
        assertTrue(engine.isLegalMove(board, true, Move.fromJson("{\"path\":[\"d4\",\"c3\"]}")));
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertFalse;` and `import static org.junit.jupiter.api.Assertions.assertTrue;` to the test file's imports.

*(Note on the deliberately over-explained `isLegalMove_movingOntoOccupiedSquare_isIllegal` test above: rather than hunt for a same-color-adjacency collision on the crowded starting board, it just builds a small explicit board — the same pattern every later task uses for capture/chain/promotion tests, since the starting position only has room for the simplest single-step cases.)*

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: failures — `isLegalMove` still throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `legalMoves()` and wire `isLegalMove()` to it (non-capture case only)**

Replace `CheckersEngine`'s `isLegalMove` method and add the private helpers:

```java
    private static final int[][] MAN_DIRECTIONS_PLAYER1 = {{1, -1}, {1, 1}};
    private static final int[][] MAN_DIRECTIONS_PLAYER2 = {{-1, -1}, {-1, 1}};
    private static final int[][] KING_DIRECTIONS = {{1, -1}, {1, 1}, {-1, -1}, {-1, 1}};

    @Override
    public boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        CheckersBoard board = CheckersBoard.fromJson(boardStateJson);
        for (Move legal : legalMoves(board, isPlayer1Turn)) {
            if (legal.getPath().equals(move.getPath())) {
                return true;
            }
        }
        return false;
    }

    private List<Move> legalMoves(CheckersBoard board, boolean isPlayer1Turn) {
        List<Move> steps = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Square from = new Square(row, col);
                char piece = board.get(from);
                if (piece == '.' || !CheckersBoard.ownedBy(piece, isPlayer1Turn)) {
                    continue;
                }
                for (int[] dir : directionsFor(piece, isPlayer1Turn)) {
                    Square to = new Square(row + dir[0], col + dir[1]);
                    if (to.isInBounds() && board.isEmpty(to)) {
                        steps.add(new Move(List.of(from, to)));
                    }
                }
            }
        }
        return steps;
    }

    private static int[][] directionsFor(char piece, boolean isPlayer1Turn) {
        if (CheckersBoard.isKing(piece)) {
            return KING_DIRECTIONS;
        }
        return isPlayer1Turn ? MAN_DIRECTIONS_PLAYER1 : MAN_DIRECTIONS_PLAYER2;
    }
```

Add the needed imports to `CheckersEngine.java`: `java.util.ArrayList`, `java.util.List`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: PASS (all tests in the file)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/game/CheckersEngine.java src/test/java/com/matchmaker/server/game/CheckersEngineTest.java
git commit -m "Add non-capture legal-move enumeration to CheckersEngine"
```

---

### Task 5: `CheckersEngine` — single captures and mandatory capture

**Files:**
- Modify: `src/main/java/com/matchmaker/server/game/CheckersEngine.java`
- Modify: `src/test/java/com/matchmaker/server/game/CheckersEngineTest.java`

**Interfaces:**
- Extends `legalMoves()` (Task 4) to also enumerate single-jump captures and apply the mandatory-capture rule (if any capture exists for this player, only captures are legal). Multi-jump chaining is Task 6 — this task's captures are exactly two squares long (`{from, landing}`).

- [ ] **Step 1: Write the failing tests**

Add to `CheckersEngineTest`:

```java
    @Test
    void isLegalMove_jumpingOverAdjacentOpponentOntoEmptySquare_isLegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"d4\"]}");

        assertTrue(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_jumpingWithNoOpponentToCapture_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"d4\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_jumpingOntoOccupiedLandingSquare_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\",\"d4\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"d4\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_captureIsMandatory_nonCaptureMoveIsIllegalWhenACaptureExists() {
        // b2 could step to a3/c3, but c3 is capturable via a jump to d4, so ONLY the
        // capture is legal -- the simple step is not, even though it's otherwise valid.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\"}}";
        Move simpleStep = Move.fromJson("{\"path\":[\"b2\",\"a3\"]}");

        assertFalse(engine.isLegalMove(board, true, simpleStep));
    }

    @Test
    void isLegalMove_captureIsMandatory_appliesAcrossAllOfThePlayersPieces() {
        // f2 has no capture available itself, but b2 does (over c3 to d4) -- since a
        // capture exists SOMEWHERE for player1, f2's simple step is illegal too.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\",\"f2\":\"b\"}}";
        Move otherPieceStep = Move.fromJson("{\"path\":[\"f2\",\"e3\"]}");

        assertFalse(engine.isLegalMove(board, true, otherPieceStep));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: failures — captures aren't enumerated yet, so both the "legal capture" and "mandatory capture" tests fail (the mandatory-capture tests currently pass as "legal" incorrectly, since nothing yet forbids the simple step).

- [ ] **Step 3: Extend `legalMoves()` to enumerate captures and enforce the mandatory rule**

Replace the `legalMoves` method body:

```java
    private List<Move> legalMoves(CheckersBoard board, boolean isPlayer1Turn) {
        List<Move> captures = new ArrayList<>();
        List<Move> steps = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Square from = new Square(row, col);
                char piece = board.get(from);
                if (piece == '.' || !CheckersBoard.ownedBy(piece, isPlayer1Turn)) {
                    continue;
                }
                int[][] directions = directionsFor(piece, isPlayer1Turn);
                for (int[] dir : directions) {
                    Square over = new Square(row + dir[0], col + dir[1]);
                    Square landing = new Square(row + 2 * dir[0], col + 2 * dir[1]);
                    if (landing.isInBounds() && over.isInBounds()
                            && !board.isEmpty(over) && !CheckersBoard.ownedBy(board.get(over), isPlayer1Turn)
                            && board.isEmpty(landing)) {
                        captures.add(new Move(List.of(from, landing)));
                    }
                    Square to = new Square(row + dir[0], col + dir[1]);
                    if (to.isInBounds() && board.isEmpty(to)) {
                        steps.add(new Move(List.of(from, to)));
                    }
                }
            }
        }
        return captures.isEmpty() ? steps : captures;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: PASS (all tests in the file, including Task 4's)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/game/CheckersEngine.java src/test/java/com/matchmaker/server/game/CheckersEngineTest.java
git commit -m "Add single-capture legality and mandatory-capture enforcement"
```

---

### Task 6: `CheckersEngine` — multi-jump capture chains

**Files:**
- Modify: `src/main/java/com/matchmaker/server/game/CheckersEngine.java`
- Modify: `src/test/java/com/matchmaker/server/game/CheckersEngineTest.java`

**Interfaces:**
- Replaces Task 5's single-jump-only capture enumeration with a recursive version that keeps chaining jumps for the same piece while further captures are available from its landing square, stopping immediately on promotion.

- [ ] **Step 1: Write the failing tests**

Add to `CheckersEngineTest`:

```java
    @Test
    void isLegalMove_multiJumpChain_isLegalAsOnePath() {
        // b2 jumps c3 landing d4, then must continue: jumps e5 landing f6.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\",\"e5\":\"w\"}}";
        Move chain = Move.fromJson("{\"path\":[\"b2\",\"d4\",\"f6\"]}");

        assertTrue(engine.isLegalMove(board, true, chain));
    }

    @Test
    void isLegalMove_multiJumpChain_stoppingEarlyWhenAFurtherJumpIsAvailable_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\",\"e5\":\"w\"}}";
        Move stoppedEarly = Move.fromJson("{\"path\":[\"b2\",\"d4\"]}");

        assertFalse(engine.isLegalMove(board, true, stoppedEarly));
    }

    @Test
    void isLegalMove_multiJumpChain_choosingAShorterNonOverlappingCaptureInstead_isLegal() {
        // Two separate, non-overlapping capture options for different player1 pieces:
        // b2 over c3 to d4 (a two-jump chain continuing to f6), and f2 over g3 to h4 (a
        // one-jump capture, no further jump available from h4). Both are legal choices --
        // majority-capture is not enforced.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\",\"e5\":\"w\",\"f2\":\"b\",\"g3\":\"w\"}}";
        Move shorterOption = Move.fromJson("{\"path\":[\"f2\",\"h4\"]}");

        assertTrue(engine.isLegalMove(board, true, shorterOption));
    }

    @Test
    void isLegalMove_chainStopsImmediatelyOnPromotion_evenIfAFurtherJumpWouldExist() {
        // b6 (player1 man) jumps c7 landing d8 -- d8 is the promotion rank, so the chain
        // ends there even though a king at d8 could otherwise jump e7 to f6.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b6\":\"b\",\"c7\":\"w\",\"e7\":\"w\"}}";
        Move stopsAtPromotion = Move.fromJson("{\"path\":[\"b6\",\"d8\"]}");
        Move triesToContinuePastPromotion = Move.fromJson("{\"path\":[\"b6\",\"d8\",\"f6\"]}");

        assertTrue(engine.isLegalMove(board, true, stopsAtPromotion));
        assertFalse(engine.isLegalMove(board, true, triesToContinuePastPromotion));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: failures — capture enumeration is still single-jump-only from Task 5.

- [ ] **Step 3: Replace single-jump capture enumeration with recursive chain-building**

Replace the `legalMoves` method body again:

```java
    private List<Move> legalMoves(CheckersBoard board, boolean isPlayer1Turn) {
        List<Move> captures = new ArrayList<>();
        List<Move> steps = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Square from = new Square(row, col);
                char piece = board.get(from);
                if (piece == '.' || !CheckersBoard.ownedBy(piece, isPlayer1Turn)) {
                    continue;
                }
                findCaptureChains(board, from, piece, isPlayer1Turn, List.of(from), captures);
                if (captures.isEmpty()) {
                    for (int[] dir : directionsFor(piece, isPlayer1Turn)) {
                        Square to = new Square(row + dir[0], col + dir[1]);
                        if (to.isInBounds() && board.isEmpty(to)) {
                            steps.add(new Move(List.of(from, to)));
                        }
                    }
                }
            }
        }
        return captures.isEmpty() ? steps : captures;
    }

    private void findCaptureChains(CheckersBoard board, Square current, char piece, boolean isPlayer1Turn,
                                    List<Square> pathSoFar, List<Move> results) {
        boolean foundFurtherJump = false;
        for (int[] dir : directionsFor(piece, isPlayer1Turn)) {
            Square over = new Square(current.row() + dir[0], current.col() + dir[1]);
            Square landing = new Square(current.row() + 2 * dir[0], current.col() + 2 * dir[1]);
            if (!landing.isInBounds() || !over.isInBounds()) {
                continue;
            }
            char overPiece = board.get(over);
            if (overPiece == '.' || CheckersBoard.ownedBy(overPiece, isPlayer1Turn) || !board.isEmpty(landing)) {
                continue;
            }

            foundFurtherJump = true;
            CheckersBoard scratch = board.copy();
            scratch.set(current, '.');
            scratch.set(over, '.');
            boolean promotes = promotesAt(piece, landing);
            char pieceAfterJump = promotes ? promotedForm(piece) : piece;
            scratch.set(landing, pieceAfterJump);

            List<Square> extendedPath = new ArrayList<>(pathSoFar);
            extendedPath.add(landing);

            if (promotes) {
                results.add(new Move(extendedPath));
            } else {
                findCaptureChains(scratch, landing, pieceAfterJump, isPlayer1Turn, extendedPath, results);
            }
        }
        if (!foundFurtherJump && pathSoFar.size() > 1) {
            results.add(new Move(pathSoFar));
        }
    }

    private static boolean promotesAt(char piece, Square square) {
        if (CheckersBoard.isKing(piece)) {
            return false;
        }
        return (piece == 'b' && square.row() == 7) || (piece == 'w' && square.row() == 0);
    }

    private static char promotedForm(char piece) {
        return piece == 'b' ? 'B' : 'W';
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: PASS (all tests in the file, including Tasks 4–5's)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/game/CheckersEngine.java src/test/java/com/matchmaker/server/game/CheckersEngineTest.java
git commit -m "Add multi-jump capture chains with promotion-stops-the-chain rule"
```

---

### Task 7: `CheckersEngine.applyMove()`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/game/CheckersEngine.java`
- Modify: `src/test/java/com/matchmaker/server/game/CheckersEngineTest.java`

**Interfaces:**
- Produces: a real `applyMove()` — assumes the move was already validated by `isLegalMove()` (matches the design doc; `PlayerServiceImpl` in Task 11 always calls `isLegalMove` first).

- [ ] **Step 1: Write the failing tests**

Add to `CheckersEngineTest`:

```java
    @Test
    void applyMove_simpleStep_movesThePieceAndLeavesTheOriginSquareEmpty() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"a3\"]}");

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("b", pieces.getString("a3"));
        assertFalse(pieces.has("b2"));
        assertEquals(1, pieces.length());
    }

    @Test
    void applyMove_singleCapture_removesTheCapturedPieceAndMovesToTheLandingSquare() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"d4\"]}");

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("b", pieces.getString("d4"));
        assertFalse(pieces.has("b2"));
        assertFalse(pieces.has("c3"));
        assertEquals(1, pieces.length());
    }

    @Test
    void applyMove_multiJumpChain_removesEveryCapturedPieceAlongThePath() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\",\"c3\":\"w\",\"e5\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"b2\",\"d4\",\"f6\"]}");

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("b", pieces.getString("f6"));
        assertEquals(1, pieces.length());
    }

    @Test
    void applyMove_manReachingTheFarRank_promotesToKing() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b7\":\"b\"}}";
        Move move = Move.fromJson("{\"path\":[\"b7\",\"a8\"]}");

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("B", pieces.getString("a8"));
    }

    @Test
    void applyMove_kingDoesNotChangeSymbolWhenAlreadyCrowned() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"d4\":\"B\"}}";
        Move move = Move.fromJson("{\"path\":[\"d4\",\"c3\"]}");

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("B", pieces.getString("c3"));
    }
```

Add `import org.json.JSONObject;` to the test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: failures — `applyMove` still throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `applyMove()`**

Replace the `applyMove` method:

```java
    @Override
    public String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        CheckersBoard board = CheckersBoard.fromJson(boardStateJson);
        List<Square> path = move.getPath();
        Square from = path.get(0);
        char piece = board.get(from);
        board.set(from, '.');

        for (int i = 1; i < path.size(); i++) {
            Square prev = path.get(i - 1);
            Square current = path.get(i);
            boolean isCapture = Math.abs(current.row() - prev.row()) == 2;
            if (isCapture) {
                Square captured = new Square((prev.row() + current.row()) / 2, (prev.col() + current.col()) / 2);
                board.set(captured, '.');
            }
        }

        Square finalSquare = path.get(path.size() - 1);
        if (promotesAt(piece, finalSquare)) {
            piece = promotedForm(piece);
        }
        board.set(finalSquare, piece);

        return board.toJson();
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: PASS (all tests in the file, including Tasks 4–6's)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/game/CheckersEngine.java src/test/java/com/matchmaker/server/game/CheckersEngineTest.java
git commit -m "Add CheckersEngine.applyMove()"
```

---

### Task 8: `CheckersEngine.checkWinner()`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/game/CheckersEngine.java`
- Modify: `src/test/java/com/matchmaker/server/game/CheckersEngineTest.java`

**Interfaces:**
- Produces: a real `checkWinner()`, reusing `legalMoves()` (Tasks 4–6) to check whether the player about to move has zero pieces or zero legal moves.

- [ ] **Step 1: Write the failing tests**

Add to `CheckersEngineTest`:

```java
    @Test
    void checkWinner_gameContinuesWhenBothSidesHavePiecesAndMoves() {
        assertEquals(GameResult.CONTINUE, engine.checkWinner(engine.initialBoardState(), false));
    }

    @Test
    void checkWinner_player2HasNoPiecesLeft_player1Wins() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b2\":\"b\"}}";

        assertEquals(GameResult.PLAYER1_WINS, engine.checkWinner(board, false));
    }

    @Test
    void checkWinner_player1HasNoPiecesLeft_player2Wins() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"g7\":\"w\"}}";

        assertEquals(GameResult.PLAYER2_WINS, engine.checkWinner(board, true));
    }

    @Test
    void checkWinner_playerAboutToMoveHasPiecesButNoLegalMove_thatPlayerLoses() {
        // Player2's man on a8 is boxed in by its own piece at b7 and has no other pieces
        // and no legal move (a8 -> nothing in bounds off the back rank in its own
        // direction other than b7, which is occupied by a friendly piece).
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"a8\":\"w\",\"b7\":\"w\"}}";

        assertEquals(GameResult.PLAYER1_WINS, engine.checkWinner(board, false));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: failures — `checkWinner` still throws `UnsupportedOperationException`.

- [ ] **Step 3: Implement `checkWinner()`**

Replace the `checkWinner` method:

```java
    @Override
    public GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext) {
        CheckersBoard board = CheckersBoard.fromJson(boardStateJson);
        boolean hasPieces = false;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                char piece = board.get(new Square(row, col));
                if (piece != '.' && CheckersBoard.ownedBy(piece, isPlayer1ToMoveNext)) {
                    hasPieces = true;
                }
            }
        }
        if (!hasPieces || legalMoves(board, isPlayer1ToMoveNext).isEmpty()) {
            return isPlayer1ToMoveNext ? GameResult.PLAYER2_WINS : GameResult.PLAYER1_WINS;
        }
        return GameResult.CONTINUE;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=CheckersEngineTest`
Expected: PASS (all tests in the file — `CheckersEngine` is now fully implemented)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/game/CheckersEngine.java src/test/java/com/matchmaker/server/game/CheckersEngineTest.java
git commit -m "Add CheckersEngine.checkWinner()"
```

---

### Task 9: `GameSessionDao` — `findActiveById()` and `recordMove()`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/dao/GameSessionDao.java`
- Modify: `src/main/java/com/matchmaker/server/dao/JdbcGameSessionDao.java`
- Test: `src/test/java/com/matchmaker/server/dao/GameSessionDaoTest.java` (extend existing file)

**Interfaces:**
- Consumes: `GameStateDTO` (existing), `GameStatus` (existing enum).
- Produces: `Optional<GameStateDTO> findActiveById(int sessionId)`, `GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson)`. Task 11 (`PlayerServiceImpl`) is the only caller.

**Design note on the `recordMove` signature:** rather than passing `GameResult` (a `server.game` type) into the DAO layer, the caller (`PlayerServiceImpl`, Task 11) builds the *already-updated* `GameStateDTO` itself (new `BoardState`, new `CurrentTurnUserID` or `null`, new `Status`/`WinnerID` if the game ended) and hands that plus the move's bookkeeping fields to `recordMove`. This keeps `server.dao` decoupled from `server.game` entirely, matching the existing dependency direction (DAOs don't know about game-specific types). The DAO computes the next `MoveNumber` itself (`MAX(MoveNumber)+1` inside the same transaction) rather than trusting a caller-supplied number, removing a class of caller bugs.

**ELO note:** `recordMove` detects "game just ended" by `updatedSession.getStatus() == GameStatus.FINISHED`, and if so updates both players' `Wins`/`Losses`/`Rating` using standard Elo with K=32: `expected = 1.0 / (1.0 + Math.pow(10, (opponentRating - rating) / 400.0))`, `newRating = round(rating + K * (actualScore - expected))`, where the winner's `actualScore` is `1.0` and the loser's is `0.0`. Draws aren't produced by `CheckersEngine` yet (design doc decision 4), so only the win/loss branch is implemented; `Draws` stays untouched.

- [ ] **Step 1: Write the failing tests**

Add to the existing `GameSessionDaoTest` (real MySQL, Docker-required — same tier as the file's existing tests):

```java
    @Test
    void findActiveById_returnsTheSessionWhenActive() {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);

        Optional<GameStateDTO> found = dao.findActiveById(sessionId);

        assertTrue(found.isPresent());
        assertEquals(GameStatus.ACTIVE, found.get().getStatus());
        assertEquals(player1Id, found.get().getPlayer1Id());
    }

    @Test
    void findActiveById_absentForAnUnknownId() {
        assertTrue(dao.findActiveById(999999).isEmpty());
    }

    @Test
    void recordMove_midGame_updatesBoardAndTurnAndInsertsMoveRow() {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        GameStateDTO updated = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.ACTIVE, player2Id, null, "{\"rows\":8,\"cols\":8,\"pieces\":{\"a3\":\"b\"}}");

        GameStateDTO result = dao.recordMove(updated, player1Id, "{\"path\":[\"b2\",\"a3\"]}");

        assertEquals(player2Id, result.getCurrentTurnUserId());
        assertEquals("{\"rows\":8,\"cols\":8,\"pieces\":{\"a3\":\"b\"}}", result.getBoardState());
        assertEquals(GameStatus.ACTIVE, result.getStatus());

        Optional<GameStateDTO> reloaded = dao.findActiveById(sessionId);
        assertTrue(reloaded.isPresent());
        assertEquals(player2Id, reloaded.get().getCurrentTurnUserId());
    }

    @Test
    void recordMove_gameEnding_setsStatusAndWinnerAndUpdatesBothPlayersRatings() {
        int sessionId = insertActiveSession(player1Id, player2Id, gameTypeId);
        int player1RatingBefore = ratingOf(player1Id);
        int player2RatingBefore = ratingOf(player2Id);

        GameStateDTO updated = new GameStateDTO(sessionId, gameTypeId, player1Id, player2Id,
                GameStatus.FINISHED, null, player1Id, "{\"rows\":8,\"cols\":8,\"pieces\":{}}");

        GameStateDTO result = dao.recordMove(updated, player1Id, "{\"path\":[\"g7\",\"h8\"]}");

        assertEquals(GameStatus.FINISHED, result.getStatus());
        assertEquals(Integer.valueOf(player1Id), result.getWinnerId());

        assertTrue(ratingOf(player1Id) > player1RatingBefore);
        assertTrue(ratingOf(player2Id) < player2RatingBefore);
        assertEquals(1, winsOf(player1Id));
        assertEquals(1, lossesOf(player2Id));
    }
```

Add whatever `insertActiveSession(...)`/`ratingOf(...)`/`winsOf(...)`/`lossesOf(...)` helper methods the existing test file doesn't already have, following its established style for direct-JDBC test setup/assertions (check the existing file for a similar `insert*`/query helper to match against before adding new ones).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `docker compose up -d && mvn test -Dtest=GameSessionDaoTest`
Expected: compile error — `findActiveById`/`recordMove` don't exist yet.

- [ ] **Step 3: Add the interface methods**

Add to `GameSessionDao.java`:

```java
    Optional<GameStateDTO> findActiveById(int sessionId);

    GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson);
```

Add `import java.util.Optional;`.

- [ ] **Step 4: Implement in `JdbcGameSessionDao`**

```java
    @Override
    public Optional<GameStateDTO> findActiveById(int sessionId) {
        String sql = "SELECT ID, GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, WinnerID, BoardState "
                + "FROM GameSession WHERE ID = ? AND Status = 'ACTIVE'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find active game session " + sessionId, e);
        }
    }

    @Override
    public GameStateDTO recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int nextMoveNumber = nextMoveNumber(conn, updatedSession.getSessionId());

                try (PreparedStatement insertMove = conn.prepareStatement(
                        "INSERT INTO Move (SessionID, UserID, MoveNumber, Payload) VALUES (?, ?, ?, ?)")) {
                    insertMove.setInt(1, updatedSession.getSessionId());
                    insertMove.setInt(2, movingUserId);
                    insertMove.setInt(3, nextMoveNumber);
                    insertMove.setString(4, movePayloadJson);
                    insertMove.executeUpdate();
                }

                boolean gameEnded = updatedSession.getStatus() == GameStatus.FINISHED;
                try (PreparedStatement updateSession = conn.prepareStatement(
                        "UPDATE GameSession SET BoardState = ?, CurrentTurnUserID = ?, TurnStartedAt = NOW(), "
                                + "Status = ?, WinnerID = ?, EndTime = ? WHERE ID = ?")) {
                    updateSession.setString(1, updatedSession.getBoardState());
                    if (updatedSession.getCurrentTurnUserId() != null) {
                        updateSession.setInt(2, updatedSession.getCurrentTurnUserId());
                    } else {
                        updateSession.setNull(2, Types.INTEGER);
                    }
                    updateSession.setString(3, updatedSession.getStatus().name());
                    if (updatedSession.getWinnerId() != null) {
                        updateSession.setInt(4, updatedSession.getWinnerId());
                    } else {
                        updateSession.setNull(4, Types.INTEGER);
                    }
                    if (gameEnded) {
                        updateSession.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                    } else {
                        updateSession.setNull(5, Types.TIMESTAMP);
                    }
                    updateSession.setInt(6, updatedSession.getSessionId());
                    updateSession.executeUpdate();
                }

                if (gameEnded) {
                    int winnerId = updatedSession.getWinnerId();
                    int loserId = (updatedSession.getPlayer1Id() == winnerId)
                            ? updatedSession.getPlayer2Id() : updatedSession.getPlayer1Id();
                    applyEloAndRecordResult(conn, winnerId, loserId);
                }

                conn.commit();
                return updatedSession;
            } catch (SQLException e) {
                conn.rollback();
                throw new DaoException("Failed to record move for session " + updatedSession.getSessionId(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to record move for session " + updatedSession.getSessionId(), e);
        }
    }

    private int nextMoveNumber(Connection conn, int sessionId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT COALESCE(MAX(MoveNumber), 0) + 1 FROM Move WHERE SessionID = ?")) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void applyEloAndRecordResult(Connection conn, int winnerId, int loserId) throws SQLException {
        int winnerRating = ratingOf(conn, winnerId);
        int loserRating = ratingOf(conn, loserId);

        double expectedWinner = 1.0 / (1.0 + Math.pow(10, (loserRating - winnerRating) / 400.0));
        double expectedLoser = 1.0 / (1.0 + Math.pow(10, (winnerRating - loserRating) / 400.0));
        int newWinnerRating = winnerRating + (int) Math.round(32 * (1.0 - expectedWinner));
        int newLoserRating = loserRating + (int) Math.round(32 * (0.0 - expectedLoser));

        try (PreparedStatement updateWinner = conn.prepareStatement(
                "UPDATE User SET Wins = Wins + 1, Rating = ? WHERE ID = ?")) {
            updateWinner.setInt(1, newWinnerRating);
            updateWinner.setInt(2, winnerId);
            updateWinner.executeUpdate();
        }
        try (PreparedStatement updateLoser = conn.prepareStatement(
                "UPDATE User SET Losses = Losses + 1, Rating = ? WHERE ID = ?")) {
            updateLoser.setInt(1, newLoserRating);
            updateLoser.setInt(2, loserId);
            updateLoser.executeUpdate();
        }
    }

    private int ratingOf(Connection conn, int userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT Rating FROM User WHERE ID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private GameStateDTO mapRow(ResultSet rs) throws SQLException {
        int currentTurnUserId = rs.getInt("CurrentTurnUserID");
        Integer winnerId = rs.getObject("WinnerID") != null ? rs.getInt("WinnerID") : null;
        return new GameStateDTO(
                rs.getInt("ID"), rs.getInt("GameTypeID"), rs.getInt("Player1ID"), rs.getInt("Player2ID"),
                GameStatus.valueOf(rs.getString("Status")),
                rs.wasNull() ? null : currentTurnUserId,
                winnerId, rs.getString("BoardState"));
    }
```

Add the needed imports (`java.sql.Timestamp`, `java.sql.Types`, `com.matchmaker.common.enums.GameStatus`) to `JdbcGameSessionDao.java`. Check the existing `mapRow`-equivalent logic already in the file for `findFinishedSessionsForUser` before adding a duplicate — reuse or align with whatever row-mapping helper already exists there rather than introducing a second, slightly different one.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `docker compose up -d && mvn test -Dtest=GameSessionDaoTest`
Expected: PASS (all tests in the file, including the pre-existing ones)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matchmaker/server/dao/GameSessionDao.java src/main/java/com/matchmaker/server/dao/JdbcGameSessionDao.java src/test/java/com/matchmaker/server/dao/GameSessionDaoTest.java
git commit -m "Add GameSessionDao.findActiveById() and recordMove() with ELO rating updates"
```

---

### Task 10: `InMemoryGameSessionDao` test fake — extend for the new methods

**Files:**
- Modify: `src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java`

**Interfaces:**
- Produces: `findActiveById()`/`recordMove()` implementations on the existing Docker-free test fake, so Task 11's `PlayerServiceImplTest` doesn't need Docker.

- [ ] **Step 1: Check the existing fake's shape**

Read `src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java` first — it already implements `findFinishedSessionsForUser` and presumably holds sessions in some in-memory collection (e.g. a `List<GameStateDTO>` or `Map<Integer, GameStateDTO>`). Match its existing style/field naming when adding the two new methods rather than introducing a parallel storage structure.

- [ ] **Step 2: Add `findActiveById()` and `recordMove()`**

`findActiveById(int sessionId)`: look up the stored session by ID, return `Optional.of(...)` only if found and `Status == ACTIVE`, else `Optional.empty()`.

`recordMove(GameStateDTO updatedSession, int movingUserId, String movePayloadJson)`: replace the stored session for `updatedSession.getSessionId()` with `updatedSession`, track the move (a simple `List<MoveDTO>` or similar, mirroring how the fake likely already tracks finished sessions), and return `updatedSession` unchanged (no ELO simulation needed in the fake — `PlayerServiceImplTest`, Task 11, only asserts on the returned `GameStateDTO` and on `makeMove`'s exception paths, not on rating math, which is already covered by Task 9's real DB test).

Also add a test-only helper to seed an active session directly (e.g. `addActiveSession(GameStateDTO session)`), matching whatever seeding method the fake already exposes for its other data (check how `InMemoryGameSessionDao.addFinishedSession(...)` — or equivalent — is already structured).

- [ ] **Step 3: Commit**

No new test file for this task — it's exercised indirectly by Task 11's `PlayerServiceImplTest`, same as `InMemoryMatchmakingQueue` was exercised by `PlayerServiceImplTest` in step 5 rather than having its own dedicated test.

```bash
git add src/test/java/com/matchmaker/server/dao/InMemoryGameSessionDao.java
git commit -m "Extend InMemoryGameSessionDao with findActiveById() and recordMove()"
```

---

### Task 11: Wire `PlayerServiceImpl.makeMove()`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`
- Modify: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`

**Interfaces:**
- Consumes: `GameEngine` (Task 2), `Move` (Task 2), `GameResult` (Task 2), `GameSessionDao.findActiveById()`/`recordMove()` (Tasks 9–10).
- Produces: `PlayerServiceImpl` constructor gains a `GameEngine gameEngine` parameter (now `SessionManager, GameSessionDao, GameTypeDao, MatchmakingQueue, GameEventPublisher, GameEngine`). Task 12 (`ServerMain`) is the only other caller.

- [ ] **Step 1: Write the failing tests**

Add to `PlayerServiceImplTest`'s `@BeforeEach`, changing the constructor call to pass `new CheckersEngine()` as the new last argument (real engine, not a fake — per the design doc, `CheckersEngine` needs no test double).

Add these tests (adjust helper names to match whatever `InMemoryGameSessionDao` seeding method Task 10 actually added):

```java
    @Test
    void makeMove_legalMove_appliesItAndReturnsUpdatedState() throws Exception {
        String initialBoard = new CheckersEngine().initialBoardState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        GameStateDTO result = playerService.makeMove(sessionToken, 1, "{\"path\":[\"b1\",\"a2\"]}");

        assertEquals(2, result.getCurrentTurnUserId());
        assertNotEquals(initialBoard, result.getBoardState());
    }

    @Test
    void makeMove_notAParticipant_throwsNotParticipantException() throws Exception {
        String initialBoard = new CheckersEngine().initialBoardState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 2, 3, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotParticipantException.class,
                () -> playerService.makeMove(sessionToken, 1, "{\"path\":[\"b1\",\"a2\"]}"));
    }

    @Test
    void makeMove_notYourTurn_throwsNotYourTurnException() throws Exception {
        String initialBoard = new CheckersEngine().initialBoardState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotYourTurnException.class,
                () -> playerService.makeMove(sessionToken, 1, "{\"path\":[\"b1\",\"a2\"]}"));
    }

    @Test
    void makeMove_illegalMove_throwsIllegalMoveException() throws Exception {
        String initialBoard = new CheckersEngine().initialBoardState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        assertThrows(IllegalMoveException.class,
                () -> playerService.makeMove(sessionToken, 1, "{\"path\":[\"b1\",\"b3\"]}"));
    }

    @Test
    void makeMove_unknownSession_throwsIllegalMoveException() {
        assertThrows(IllegalMoveException.class,
                () -> playerService.makeMove(sessionToken, 999, "{\"path\":[\"b1\",\"a2\"]}"));
    }
```

Update the `remainingMethods_stillThrowUnsupportedOperationException` test to drop `makeMove` from its list (it's no longer a stub).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: compile error — constructor signature mismatch, `makeMove` still throws `UnsupportedOperationException`.

- [ ] **Step 3: Update `PlayerServiceImpl`**

Add imports: `com.matchmaker.server.game.GameEngine`, `com.matchmaker.server.game.GameResult`, `com.matchmaker.server.game.Move`, `com.matchmaker.common.enums.GameStatus`, `java.util.Optional`.

Add a field and constructor parameter:

```java
    private final GameEngine gameEngine;
```

```java
    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao,
                              MatchmakingQueue matchmakingQueue, GameEventPublisher gameEventPublisher,
                              GameEngine gameEngine) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
        this.matchmakingQueue = matchmakingQueue;
        this.gameEventPublisher = gameEventPublisher;
        this.gameEngine = gameEngine;
    }
```

Replace `makeMove`:

```java
    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException {
        int userId = sessionManager.resolve(sessionToken);

        GameStateDTO session = gameSessionDao.findActiveById(gameSessionId)
                .orElseThrow(() -> new IllegalMoveException("No active game session " + gameSessionId));

        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }
        if (session.getCurrentTurnUserId() == null || session.getCurrentTurnUserId() != userId) {
            throw new NotYourTurnException("It is not user " + userId + "'s turn in session " + gameSessionId);
        }

        Move move;
        try {
            move = Move.fromJson(movePayload);
        } catch (RuntimeException e) {
            throw new IllegalMoveException("Malformed move payload: " + e.getMessage());
        }

        boolean isPlayer1Turn = session.getPlayer1Id() == userId;
        if (!gameEngine.isLegalMove(session.getBoardState(), isPlayer1Turn, move)) {
            throw new IllegalMoveException("Illegal move for session " + gameSessionId + ": " + movePayload);
        }

        String newBoardState = gameEngine.applyMove(session.getBoardState(), isPlayer1Turn, move);
        GameResult result = gameEngine.checkWinner(newBoardState, !isPlayer1Turn);

        int opponentId = isPlayer1Turn ? session.getPlayer2Id() : session.getPlayer1Id();
        GameStateDTO updatedSession;
        if (result == GameResult.CONTINUE) {
            updatedSession = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                    session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.ACTIVE, opponentId, null, newBoardState);
        } else {
            Integer winnerId = result == GameResult.PLAYER1_WINS ? session.getPlayer1Id()
                    : result == GameResult.PLAYER2_WINS ? session.getPlayer2Id() : null;
            updatedSession = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                    session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.FINISHED, null, winnerId, newBoardState);
        }

        return gameSessionDao.recordMove(updatedSession, userId, movePayload);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: PASS (all tests in the file)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java
git commit -m "Wire PlayerServiceImpl.makeMove() to GameEngine and GameSessionDao"
```

---

### Task 12: Wire `ServerMain`, run the full regression suite, update `build-plan.md`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/ServerMain.java`
- Modify: `docs/build-plan.md`

**Interfaces:**
- Consumes: `CheckersEngine` (Tasks 3–8), `PlayerServiceImpl`'s new 6-arg constructor (Task 11).
- Produces: nothing new for later steps — this is the last task in this plan.

- [ ] **Step 1: Update `ServerMain`**

Add import: `com.matchmaker.server.game.CheckersEngine`, `com.matchmaker.server.game.GameEngine`.

Inside `startWithImpls`, add before the `PlayerServiceImpl` construction:

```java
        GameEngine gameEngine = new CheckersEngine();
```

Update the `PlayerServiceImpl` construction to pass it as the 6th argument.

- [ ] **Step 2: Compile**

Run: `mvn compile`
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 3: Run the full test suite**

Run: `docker compose up -d && mvn test`
Expected: every test passes, including `ServerMainTest` and every test from Tasks 1–11.

- [ ] **Step 4: Manually confirm the server still starts**

Run: `mvn exec:java`
Expected console output: the existing startup banner, no exceptions. Stop with Ctrl-C once confirmed.

- [ ] **Step 5: Update `docs/build-plan.md`**

Move step 7 from "Immediate next focus" into "What's Implemented So Far" as **Milestone 6**, following the established per-milestone write-up style (see Milestones 1–5). Update "Next Steps" to point at step 8 (the JavaFX player client) — note in that section that the JMS "opponent made a move" push (deferred by this plan's decision 5) is still an open gap for the client to be genuinely real-time, and will need its own milestone before or alongside step 8.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matchmaker/server/ServerMain.java docs/build-plan.md
git commit -m "Wire CheckersEngine into ServerMain; update build-plan.md"
```
