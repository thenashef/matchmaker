# MatchMaker – Build Plan

## Context
This is a from-scratch final project for an Advanced Java course (see `../MatchMaker_Spec_EN.md` for the full functional/DB/UI spec). No code exists yet. The goal here is twofold: (1) lay out the full sequence of steps to build the system, and (2) start actual coding with the RMI contract layer, since RMI (together with JMS) is the architectural backbone the whole system — and likely the course grading — depends on. Getting the client/server "contract" right first lets every later piece (DB, JMS, UI) be built against a stable interface instead of guessed at.

## Assumptions (flag if wrong)
- Build tool: **Maven**, single project (not multi-module) — simplest to manage/submit for a course project.
- Java: JDK 21 (installed via Homebrew) + Maven 3.9 — confirmed working (`java -version`, `mvn -v`).
- One Maven project containing multiple runnable `main` classes: `ServerMain`, `ClientMain` (JavaFX), `AdminMain` (JavaFX), rather than splitting into separate Maven modules. Keeps the course submission simple; can be split later if it gets unwieldy.
- MySQL and ActiveMQ are not required to be running yet for the first milestone (RMI only, no DB/JMS wiring yet).

## Working style (important)
This is the user's own course project — they need to understand and agree with every piece of code, not receive a batch of files they didn't review. From here on: **one file/concept at a time.** For each piece, explain what it does and why and show the intended content in chat first; the user reviews/pushes back/asks questions; only after agreement does it get written to disk. No writing a run of files in one go without checkpoints in between.

**Written before this rule was set (still needs review, not yet agreed-on):** `pom.xml`, `GameStatus.java`, `QueueStatus.java`, `UserDTO.java`, `MoveDTO.java`, `GameStateDTO.java`. These should be walked through with the user before being treated as final — fields, style (e.g. records vs. classes), or shape may change.

## Full Roadmap (in build order)

1. **Project setup** — Maven project, folder/package skeleton, dependencies (JavaFX, MySQL Connector/J, ActiveMQ client), `.gitignore`, git init.
2. **Shared contracts (`common` package)** — DTOs that will travel over RMI and JMS (`UserDTO`, `MoveDTO`, `GameStateDTO`, `ChatMessageDTO`), enums (`GameStatus`, `QueueStatus`), and the RMI remote interface (`MatchmakerService`). ← **current focus**
3. **RMI server skeleton** — implement the remote interface, start an RMI registry, bind the service, and prove connectivity with a throwaway test client (no real logic yet, just echo/ping-style calls).
4. **JDBC/DAO layer** — create the MySQL schema (6 tables per spec), write `UserDao` first, wire real `register`/`login` behavior into the RMI service.
5. **Matchmaking logic** — `MatchmakingQueue` handling with synchronized/atomic pairing; creates a `GameSession` row when two players match.
6. **JMS setup** — ActiveMQ connection, one topic per game session, server-side producer; a minimal standalone consumer to prove messages arrive before touching the UI.
7. **Game engine** — `GameEngine` interface (`isLegalMove`, `applyMove`, `checkWinner`) with `CheckersEngine` as the first implementation; wire into the RMI `makeMove` call; persist `Move` rows and `BoardState`.
8. **Player client (JavaFX)** — Login/Register → Lobby → Matchmaking wait → Game board, wired to RMI (commands) + JMS (push updates).
9. **Admin client (JavaFX)** — Dashboard, Add Game Type, Live Session Monitor; RMI for actions, read-only JMS subscription for live monitoring.
10. **Edge cases** — `keepAlive`/disconnect handling → `ABANDONED` state, turn timeout, Rematch, per-session authorization checks (only participants + only the player whose turn it is can act).
11. **Testing & polish** — manual multi-client runs, error handling, demo/packaging prep.

## Current Milestone: RMI Foundation (steps 1–3)

**What "done" looks like:** a server process is running, exposes a bound RMI remote object, and a separate test-client process can look it up over RMI and successfully call a method on it — proving the client/server wiring works before any real feature is built on top.

**Pieces to work through together, in order (each one discussed and agreed before it's written):**
1. Review the existing DTOs/enums/`pom.xml` — confirm fields and style are what's wanted.
2. `ChatMessageDTO` — the one remaining DTO from the spec, not yet created.
3. `MatchmakerService.java` — the RMI remote interface itself (`extends Remote`); this is the main design conversation, since every method signature here is a contract decision (what does `login` take/return? what does `makeMove` need?).
4. `MatchmakerServiceImpl.java` — server-side implementation of that interface, stub method bodies for now.
5. `ServerMain.java` — starts the RMI registry and binds the service.
6. `RmiTestClient.java` — throwaway client `main` to prove the round-trip works; removed/replaced once the real client exists.

**Alongside the code**, RMI concepts get explained as they come up (remote interfaces, stubs, the registry, `Serializable` requirements, `RemoteException`) rather than just handed over as finished files.

## Verification
- `mvn compile` succeeds with no errors.
- Run `ServerMain` — console confirms the registry started and the service is bound.
- Run `RmiTestClient` in a second process — it connects, calls a method on the remote service, and prints a real (not null/error) response back, proving the RMI round-trip works end to end.
