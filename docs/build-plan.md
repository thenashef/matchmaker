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

**Written before this rule was set (still needs review, not yet agreed-on):** `pom.xml`, `GameStatus.java`, `QueueStatus.java`. These should be walked through with the user before being treated as final — fields, style (e.g. records vs. classes), or shape may change.

`UserDTO.java`, `MoveDTO.java`, and `GameStateDTO.java` have since gone through that review as part of the contracts design pass — see `docs/specs/2026-08-05-contracts-design.md`, which documents all three as already on disk and confirmed as final.

## Full Roadmap (in build order)

1. **Project setup** — Maven project, folder/package skeleton, dependencies (JavaFX, MySQL Connector/J, ActiveMQ client), `.gitignore`, git init.
2. **Shared contracts (`common` package)** — DTOs that will travel over RMI and JMS (`UserDTO`, `MoveDTO`, `GameStateDTO`, `ChatMessageDTO`), enums (`GameStatus`, `QueueStatus`), and the RMI remote interfaces (`AuthService`, `PlayerService`, `AdminService`).
3. **RMI server skeleton** — implement the remote interfaces, start an RMI registry, bind the services, and prove connectivity with a real RMI integration test (no real logic yet, just echo/ping-style calls).
4. **JDBC/DAO layer** — create the MySQL schema (6 tables per spec), write `UserDao` first, wire real `register`/`login` behavior into the RMI service. ← **next focus**
5. **Matchmaking logic** — `MatchmakingQueue` handling with synchronized/atomic pairing; creates a `GameSession` row when two players match.
6. **JMS setup** — ActiveMQ connection, one topic per game session, server-side producer; a minimal standalone consumer to prove messages arrive before touching the UI.
7. **Game engine** — `GameEngine` interface (`isLegalMove`, `applyMove`, `checkWinner`) with `CheckersEngine` as the first implementation; wire into the RMI `makeMove` call; persist `Move` rows and `BoardState`.
8. **Player client (JavaFX)** — Login/Register → Lobby → Matchmaking wait → Game board, wired to RMI (commands) + JMS (push updates).
9. **Admin client (JavaFX)** — Dashboard, Add Game Type, Live Session Monitor; RMI for actions, read-only JMS subscription for live monitoring.
10. **Edge cases** — `keepAlive`/disconnect handling → `ABANDONED` state, turn timeout, Rematch, per-session authorization checks (only participants + only the player whose turn it is can act).
11. **Testing & polish** — manual multi-client runs, error handling, demo/packaging prep.

## Completed Milestone: RMI Foundation (steps 1–3)

**What "done" looks like:** a server process is running, exposes bound RMI remote objects, and a separate client can look them up over RMI and successfully call a method on them — proving the client/server wiring works before any real feature is built on top.

The design and implementation here ended up superseding the plan originally sketched for this milestone: instead of a single `MatchmakerService.java` interface, the actual contract split into three interfaces (`AuthService`, `PlayerService`, `AdminService`) — see `docs/specs/2026-08-05-contracts-design.md` for that design and `docs/superpowers/plans/2026-08-05-contracts-implementation.md` for how it was implemented. Instead of a throwaway `RmiTestClient.java` `main` method, RMI connectivity is proven by `AuthServiceRmiIntegrationTest` — a real, automated integration test that stands up an RMI registry, binds a real `AuthServiceImpl`, and calls it through a genuine looked-up stub — see `docs/superpowers/specs/2026-08-05-rmi-server-skeleton-design.md` for that design and `docs/superpowers/plans/2026-08-05-rmi-server-skeleton-implementation.md` for how it was implemented.

## Verification
- `mvn compile` succeeds with no errors.
- `mvn test` passes, including `AuthServiceRmiIntegrationTest` and `ServerMainTest`, which prove the RMI round-trip works end to end (registry lookup, real stub, real method call) without any manual two-process run required.
- `ServerMain` remains a real, manually-runnable entry point (console confirms the registry started and all three services are bound) for demoing against a real client later.
