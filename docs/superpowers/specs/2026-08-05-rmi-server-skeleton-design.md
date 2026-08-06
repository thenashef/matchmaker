# RMI Server Skeleton — Design

Date: 2026-08-05
Status: Design agreed in discussion; awaiting user's review of this written doc before writing-plans

## Context

The contracts (`common.exceptions`, `common.dto`, `common.rmi` — `AuthService`/`PlayerService`/`AdminService`) are implemented, tested, and committed on `feature/contracts` (see `docs/specs/2026-08-05-contracts-design.md`). Per `docs/build-plan.md` step 3, the next milestone is proving those interfaces actually work over real RMI: implementations, a running registry, and a real client successfully calling a real server method across process boundaries. This is deliberately still before the database (step 4) or any real game/matchmaking logic (steps 5, 7) — the goal is to prove the *wiring*, not build features.

## Decision 1: Fake data via a hardcoded test user, not an in-memory store

**Chosen:** one fixed test user (`id=1, username="test", password="test1234"`) hardcoded directly in `AuthServiceImpl`, rather than building a small in-memory user-store class.

**Why:** this milestone's job is to prove RMI plumbing, not to anticipate the DAO layer. A hardcoded user is the minimum needed to exercise real login/failure logic; building a store class now risks shaping it around guesses about what the real `UserDao` (step 4) will need, then having to reshape it anyway once JDBC is actually designed. YAGNI: build the store when the database step actually requires one.

## Decision 2: Shared `SessionManager` class, constructor-injected

**Chosen:** a single `SessionManager` class holding the `token → userId` map, instantiated once in `ServerMain`, passed into `AuthServiceImpl`, `PlayerServiceImpl`, and `AdminServiceImpl` via their constructors.

**Why:** `AuthService` creates sessions, `PlayerService`/`AdminService` need to validate them — that state must be shared across all three implementation objects. A static/global map would work too, but hides the dependency and is harder to test in isolation. Constructor injection makes the dependency explicit and lets `SessionManager` be unit-tested completely on its own, with no RMI or the other two services involved.

## Design

### File/package structure

- `com.matchmaker.server.SessionManager` — the shared token store
- `com.matchmaker.server.rmi.AuthServiceImpl`
- `com.matchmaker.server.rmi.PlayerServiceImpl`
- `com.matchmaker.server.rmi.AdminServiceImpl`
- `com.matchmaker.server.ServerMain` — creates the `SessionManager`, all three impls, starts the registry, binds all three
- Tests: `SessionManagerTest`, `AuthServiceImplTest` (both plain unit tests, no RMI), `AuthServiceRmiIntegrationTest` (real RMI, see below)

### `SessionManager`

```java
String createSession(int userId);      // generates a random UUID token, stores token -> userId, returns the token
int resolve(String token) throws AuthenticationException;  // looks up userId; throws if token is unknown
```

Backed by a `ConcurrentHashMap<String, Integer>` — thread-safe, since RMI calls from different clients can arrive concurrently even in this early milestone.

### `AuthServiceImpl` — real behavior, both success and failure paths

Constructor takes a `SessionManager`. Behavior against the one hardcoded user:

- `login("test", "test1234")` → succeeds; returns `LoginResultDTO` containing a `UserDTO` for the test user and a real token from `SessionManager.createSession()`.
- `login("test", <any other password>)` or `login(<any other username>, ...)` → throws `AuthenticationException` for real.
- `register("test", ...)` → throws `UsernameTakenException` for real — "test" genuinely is taken.
- `register(<any other username>, ...)` → throws `UnsupportedOperationException("registering new users requires the database layer — see build-plan.md step 4")`. This is a deliberate, clearly-labeled temporary limitation, not a bug — there's nowhere to durably persist a new user yet.
- `keepAlive(token)` → calls `SessionManager.resolve(token)`; succeeds (returns normally) if valid, throws `AuthenticationException` if not.

Rationale for exercising *both* success and failure paths here (not just the happy path): RMI's ability to carry a real checked exception back across the network is itself something worth proving works, not just assuming — this milestone is explicitly about proving the RMI mechanics, and exceptions are part of that mechanic (per Decision 3 in the contracts spec).

### `PlayerServiceImpl` / `AdminServiceImpl` — stubs, not fakes

Every method body is:
```java
throw new UnsupportedOperationException("<methodName> not implemented yet — see build-plan.md step <N>");
```

Each message points at the specific future roadmap step that implements it (step 5 for matchmaking/moves, step 7 for the game engine, step 9 for admin features). Both classes are still fully constructed and bound in the registry under their real names — this proves the complete three-interface registry wiring works structurally right now, so later milestones only ever need to fill in one method body at a time and never have to touch `ServerMain` again.

### `ServerMain`

1. Construct one `SessionManager`.
2. Construct `AuthServiceImpl`, `PlayerServiceImpl`, `AdminServiceImpl`, each given the same `SessionManager` instance.
3. `LocateRegistry.createRegistry(port)` — create an RMI registry on a fixed port (1099, RMI's conventional default).
4. `registry.rebind("AuthService", authServiceImpl)`, and likewise for `"PlayerService"` and `"AdminService"`.
5. Print confirmation to console (registry started, services bound) so a human running it can see it worked.

This remains a real, manually-runnable entry point — useful later for actually demoing the running server against a real client — but it is not itself how correctness gets verified (see Testing below).

### Testing strategy

- **`SessionManagerTest`** — plain JUnit, no RMI: create a session and resolve it back to the same userId; resolving an unknown token throws `AuthenticationException`.
- **`AuthServiceImplTest`** — plain JUnit, calling the impl's methods directly as a Java object (not through RMI): proves the hardcoded login/register/keepAlive logic itself is correct, independent of any networking concern.
- **`AuthServiceRmiIntegrationTest`** — the one that actually proves RMI works: inside a single JUnit test, call `LocateRegistry.createRegistry()` on a test port, `rebind()` a real `AuthServiceImpl`, then `registry.lookup("AuthService")` to obtain a genuine RMI stub, and call methods **through that stub** (real serialization, real network loopback, real `RemoteException` declaration on every call). Runs automatically with `mvn test` — no manual two-terminal process needed to confirm RMI actually works on every run.

## Explicitly Out of Scope

- Real user persistence / `UserDao` — step 4 (JDBC).
- Any real `PlayerService`/`AdminService` behavior (matchmaking, moves, chat, admin actions) — steps 5, 7, 9.
- Session expiry / timeout handling (the `keepAlive`-based disconnect detection from the functional spec's edge-cases section) — step 10.
- A real JavaFX client — this milestone only needs a JUnit integration test proving RMI mechanics work; the real player/admin clients are steps 8–9.

## Next Step

Invoke writing-plans to produce a bite-sized, TDD-ordered implementation plan for: `SessionManager` → `AuthServiceImpl` → `PlayerServiceImpl`/`AdminServiceImpl` stubs → `ServerMain` → the RMI integration test.
