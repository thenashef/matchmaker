# Legal-Move / Capture-Chain Highlighting Implementation Plan

**Goal:** Manual testing of Milestone 9 surfaced three related client UX complaints: clicking a square that isn't part of a legal move gets silently accepted into the selection and only punished (whole selection wiped) on submit; there's no indication at the start of a turn which pieces can move, or which must capture; and a multi-jump capture chain gives no step-by-step guidance. This plan closes all three with one new capability.

**Design, worked through in chat before this plan (no separate design doc — same pattern as Milestone 6.5's per-session JMS topic):**

1. **No new rules logic needed.** `CheckersEngine` already has a private `legalMoves(board, isPlayer1Turn)` that enumerates *every* complete legal move for the current player, and already enforces mandatory capture (`return captures.isEmpty() ? steps : captures;`). The new capability is just a prefix-filter over that existing list: given a partial path (possibly empty), find every legal move whose path starts with exactly that prefix, and return each one extended by exactly one more step.
   - Empty path → every legal move's origin → "which pieces can move right now" (already capture-only if a capture is mandatory, for free).
   - `[b6]` → every legal first step from b6.
   - `[b6, d4]` (mid-capture-chain) → every legal next jump from there; empty result means the chain is exhausted and the path is already a complete, submittable move.
2. **Returns opaque move-payload JSON, not raw square strings.** First draft of this design returned bare algebraic squares (`List<String>` of `"c5"`, `"d4"`, ...) — rejected in chat because it leaks checkers vocabulary into the otherwise fully-opaque `GameEngine` interface (`isLegalMove`/`applyMove` never do this). Revised to return each continuation as a **full `{"path":[...]}` JSON string**, i.e. the input path extended by one step, exactly the same shape `movePayloadJson` already has everywhere else in the system. The client (already unapologetically checkers-specific) parses each returned JSON and takes the last square for highlighting.
3. **New `GameEngine` interface method**: `List<String> legalContinuations(String stateJson, boolean isPlayer1Turn, String partialMovePayloadJson)`.
4. **New `PlayerService.legalContinuations(sessionToken, gameSessionId, partialMovePayloadJson)` RMI method**, mirroring `makeMove()`'s existing authorization shape (participant + it's their turn → `NotParticipantException`/`NotYourTurnException`) and the same `initialState()` fallback for a freshly-matched session with a still-null `BoardState`.
5. **Client**: `GameBoardController` queries this with an empty path the instant it becomes the player's turn (alongside the existing sound/countdown), storing the result as the current highlight set. Every click re-queries with the grown path. `onSquareClicked` now **rejects** a click on a square outside the current highlight set instead of blindly accepting it — this is the actual fix for "shouldn't just auto-deselect": illegal picks are refused in real time, not accepted-then-punished. Still fully non-authoritative — `makeMove()`'s server-side validation is unchanged and remains the real boundary.

**Tech stack:** unchanged — no new dependencies.

## Global constraints

- `GameEngine` gains a new interface method — `CheckersEngine` is the only implementation, so no other production code needs updating, but check for any test doubles implementing `GameEngine` directly (none currently exist per `server.game`'s "no test-fake needed" note in `project-structure.md` — `CheckersEngineTest` calls the real engine directly).
- `Move` gains `toJson()` — purely additive, existing `fromJson()`/callers unaffected.
- No DB/JMS/session changes at all — this is engine + RMI + client only.

---

### Task 1: `Move.toJson()` and `GameEngine.legalContinuations()`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/game/checkers/Move.java`
- Modify: `src/main/java/com/matchmaker/server/game/GameEngine.java`
- Modify: `src/main/java/com/matchmaker/server/game/checkers/CheckersEngine.java`
- Modify: `src/test/java/com/matchmaker/server/game/checkers/MoveTest.java`
- Modify: `src/test/java/com/matchmaker/server/game/checkers/CheckersEngineTest.java`

**Steps:**
1. `Move.toJson()`: build `{"path": [...]}` from `path`, mirroring `CheckersBoard.toJson()`'s style. Add a round-trip test in `MoveTest` (`fromJson(toJson(move))` equals the original path).
2. Add `List<String> legalContinuations(String stateJson, boolean isPlayer1Turn, String partialMovePayloadJson);` to `GameEngine`.
3. Implement in `CheckersEngine`:
   ```java
   @Override
   public List<String> legalContinuations(String stateJson, boolean isPlayer1Turn, String partialMovePayloadJson) {
       List<Square> pathSoFar;
       try {
           pathSoFar = Move.fromJson(partialMovePayloadJson).getPath();
       } catch (RuntimeException e) {
           return List.of();
       }
       CheckersBoard board = CheckersBoard.fromJson(stateJson);
       Set<List<Square>> seen = new LinkedHashSet<>();
       List<String> continuations = new ArrayList<>();
       for (Move legal : legalMoves(board, isPlayer1Turn)) {
           List<Square> path = legal.getPath();
           if (path.size() > pathSoFar.size() && path.subList(0, pathSoFar.size()).equals(pathSoFar)) {
               List<Square> extended = path.subList(0, pathSoFar.size() + 1);
               if (seen.add(extended)) {
                   continuations.add(new Move(extended).toJson());
               }
           }
       }
       return continuations;
   }
   ```
   (`seen` matters for multi-jump chains that share a prefix but diverge later — without it the same next square would appear once per diverging full-length move.)
4. `CheckersEngineTest` cases: empty-path origins on the starting position (should return one continuation per piece with a legal opening step); empty-path origins when a capture is mandatory (only capture-capable origins come back — build a small board fixture with one forced-capture piece and confirm non-capturing pieces are excluded); a single-step continuation (`[b6]` → its one legal destination); a multi-jump continuation (`[origin, firstLanding]` → the next jump square, using a fixture already similar to the existing multi-jump tests); a complete-path input returning empty (no further legal extension); malformed JSON input returning empty.
5. Run `mvn test -Dtest=MoveTest,CheckersEngineTest`, confirm green.
6. Commit.

---

### Task 2: `PlayerService.legalContinuations()`

**Files:**
- Modify: `src/main/java/com/matchmaker/common/rmi/PlayerService.java`
- Modify: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`
- Modify: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`

**Steps:**
1. `PlayerService`: `List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayloadJson) throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException;`
2. `PlayerServiceImpl`: resolve token, `findActiveById()`, check participant/turn (same shape as `makeMove()`'s guard — consider factoring the shared "resolve + participant + turn" preamble into a small private helper both methods call, matching this codebase's low-duplication style, if it comes out cleanly), fall back to `gameEngine.initialState()` for a null `BoardState`, delegate to `gameEngine.legalContinuations(board, isPlayer1Turn, partialMovePayloadJson)`.
3. `PlayerServiceImplTest`: happy path (matches a hand-verified expected continuation list for a known fixture board), `NotParticipantException`, `NotYourTurnException`, null-`BoardState` fallback (mirrors the equivalent `makeMove()` test).
4. Run `mvn test -Dtest=PlayerServiceImplTest`, confirm green.
5. Commit.

---

### Task 3: Client wiring

**Files:**
- Modify: `src/main/java/com/matchmaker/client/communication/ServerConnection.java`
- Modify: `src/main/java/com/matchmaker/client/communication/RmiJmsServerConnection.java`
- Modify: `src/test/java/com/matchmaker/client/communication/InMemoryServerConnection.java`
- Modify: `src/main/java/com/matchmaker/client/logic/GameClientService.java`
- Modify: `src/test/java/com/matchmaker/client/logic/GameClientServiceTest.java`

**Steps:**
1. `ServerConnection`: `List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayloadJson) throws AuthenticationException, NotParticipantException, NotYourTurnException;`
2. `RmiJmsServerConnection`: delegate to `playerService.legalContinuations(...)`, wrapping `RemoteException` as `ServerCommunicationException` like every other method here.
3. `InMemoryServerConnection`: configurable result (`setLegalContinuationsResult(List<String>)`), returned from the fake, plus recording calls if useful for assertions (mirrors `makeMoveCalls()`'s existing pattern).
4. `GameClientService.legalContinuations(int gameSessionId, String partialMovePayloadJson, Consumer<List<String>> onSuccess, Consumer<Throwable> onError)`, same `runAsync` background-thread-plus-`Platform.runLater` shape as every other method.
5. `GameClientServiceTest`: success path, error path.
6. Run `mvn test -Dtest=GameClientServiceTest`, confirm green.
7. Commit.

---

### Task 4: Highlighting UI in `GameBoardController`

**Files:**
- Modify: `src/main/java/com/matchmaker/client/presentation/GameBoardController.java`

**Steps:**
1. New field: `private Set<String> highlightedSquares = Set.of();`
2. New method `refreshHighlights()`: builds the current partial-path JSON from `selectedPath` (`{"path": selectedPath}`), calls `gameClientService.legalContinuations(...)`, and on success parses each returned JSON's last path element into `highlightedSquares`, then re-renders the board. Called: from `applyState()` right after determining `isMyTurn() && !ended` (with an empty path, alongside starting the sound/countdown — and cleared to `Set.of()` in the `else` branch alongside `stopTurnCountdown()`); from `onSquareClicked()` after a click is accepted; from `onClearSelection()` after clearing back to an empty path.
3. `onSquareClicked()`: before adding to `selectedPath`, check `highlightedSquares.contains(algebraic)` — if not, ignore the click (return). Keep the existing `isMyTurn()`/status/`isOwnPiece` guards for the very first click of a turn (empty-path case), since `highlightedSquares` already reflects legal origins by the time any click can happen (populated by `applyState()`/`refreshHighlights()` before the board is interactive).
4. `buildCell()`: add a second border style for "in `highlightedSquares` but not yet in `selectedPath`" (e.g. a lighter/different-colored border than the existing gold "selected" one), so a player can see both what's already picked and what's legally clickable next.
5. No automated test (consistent with the rest of `client.presentation`) — verified manually per the checklist already in this conversation, re-run against this feature specifically (confirm highlights appear at turn start, narrow to capture-only origins when a capture is mandatory, update step-by-step through a multi-jump chain, and that clicking a non-highlighted square does nothing).
6. Run `mvn compile` to confirm it builds.
7. Commit.

---

### Task 5: Full suite + docs

**Steps:**
1. `docker compose up -d && mvn test` — full suite green.
2. Update `docs/build-plan.md` (fold into Milestone 9's write-up as a follow-on addendum, or a short new entry — whoever's judgment at the time) and `docs/project-structure.md` (`server/game/`, `common/rmi/PlayerService`, `client/communication/`, `client/logic/`, `client/presentation/GameBoardController` bullets) for the new capability.
3. Commit.
