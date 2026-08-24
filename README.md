# MatchMaker

A two-player online checkers matchmaker: a Java RMI/JMS server backed by MySQL, a JavaFX
player client, and a separate JavaFX admin client.

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker (for the MySQL dev database)

## 1. Start the database

```bash
docker compose up -d
```

This starts a MySQL 8 container and applies [`db/schema.sql`](db/schema.sql) on first boot,
creating both the `matchmaker` database (used by the running app) and `matchmaker_test`
(used only by `mvn test` — see [Running the tests](#running-the-tests)).

If you already had a container running from before `matchmaker_test` existed, Docker's
init scripts won't retroactively create it. Either run the `CREATE DATABASE
matchmaker_test` block from `db/schema.sql` by hand against the running container, or
start fresh:

```bash
docker compose down -v && docker compose up -d
```

## 2. Seed demo accounts (optional)

```bash
docker compose exec -T mysql mysql -uroot -proot matchmaker < db/seed-demo-users.sql
```

Creates two ordinary players, one admin account, and three leaderboard filler accounts
with canned win/loss/draw records, so you can try the clients without registering through
the UI first and the lobby leaderboard is never empty:

| Username       | Password | Role   | Record (W/L/D) | Rating |
|----------------|----------|--------|----------------|--------|
| `playera`      | `1234`   | Player | 0/0/0          | 1200   |
| `playerb`      | `1234`   | Player | 0/0/0          | 1200   |
| `admin`        | `admin`  | Admin  | 0/0/0          | 1200   |
| `demo-users1`  | `1234`   | Player | 12/3/1         | 1364   |
| `demo-users2`  | `1234`   | Player | 6/6/4          | 1200   |
| `demo-users3`  | `1234`   | Player | 2/10/3         | 1088   |

The script is idempotent — re-running it after the accounts already exist is a
harmless no-op for `playera`/`playerb`/`admin`, and restores the canned records for
`demo-users1`/`2`/`3`. Note that a full `mvn test` run does *not* touch these
accounts; they live in `matchmaker`, and the DB-integration tests run against the
separate `matchmaker_test` database.

## 3. Start the server

```bash
mvn exec:java
```

Starts the RMI registry (`AuthService`, `PlayerService`, `AdminService` on port `1099`)
and the embedded JMS broker (`tcp://localhost:61616`), and blocks in the foreground —
leave it running and use another terminal for the next step. Stop it with `Ctrl-C`.

## 4. Run the clients

Each client is a separate process. Start the server first, then run as many of these as
you like, each in its own terminal:

**Player client:**

```bash
mvn javafx:run
```

Run two of these side by side (as two separate processes) to actually play a game against
yourself — log in as `playera` in one window and `playerb` in the other, and join the
same game type's queue from both.

**Admin client:**

```bash
mvn javafx:run -Padmin
```

Log in with the `admin` seed account, or any user with `IsAdmin = TRUE` in the database.
The admin dashboard lists users and active sessions, and can monitor or force-end a live
session. Playable games are seeded in the database (Checkers and Crazy Eights); admins
cannot add new game types from the panel.

## Running the tests

```bash
mvn test
```

Requires `docker compose up -d` to be running — several test classes (`UserDaoTest`,
`GameTypeDaoTest`, `GameSessionDaoTest`, `MatchmakingQueueTest`, plus the JMS/RMI
integration tests) run real SQL and real network round-trips. All of them run against
`matchmaker_test`, a database kept entirely separate from `matchmaker` so a full test run
is always safe against a machine that also has real dev/demo data on it — the
DB-integration tests clear their tables in `@BeforeEach` and would otherwise wipe out
whatever you seeded in step 2.

To run a narrower slice while iterating, target a class directly:

```bash
mvn test -Dtest=CheckersEngineTest
```

## Project layout

See [`docs/project-structure.md`](docs/project-structure.md) for the full file-by-file
layout, and [`docs/build-plan.md`](docs/build-plan.md) for the milestone history and
current project status.
