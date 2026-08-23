-- Optional demo users for manually trying the JavaFX player client (roadmap step 8) without
-- registering through the UI first. Two ordinary players to match against each other, plus one
-- admin account for when the admin client (step 9) exists, plus three leaderboard-only accounts
-- (demo-users1/2/3) with pre-filled wins/losses/draws so the lobby table is never empty.
--
-- Deliberately NOT auto-applied by docker-entrypoint-initdb.d the way schema.sql is (compare
-- docker-compose.yml, which only mounts schema.sql there) -- these are throwaway demo accounts
-- with weak, publicly-known passwords, not something that belongs baked into every fresh
-- database automatically. Run it yourself, on demand, whenever you want the accounts to exist:
--
--   docker compose up -d
--   docker compose exec -T mysql mysql -uroot -proot matchmaker < db/seed-demo-users.sql
--
-- Passwords are bcrypt hashes of the plaintext below (generated with the same jbcrypt version/
-- settings AuthServiceImpl.register() uses -- BCrypt.hashpw(password, BCrypt.gensalt())), not the
-- plaintext itself -- Password is never stored in plaintext, even for demo accounts.
--
-- Re-run this any time the accounts are missing: playera/playerb/admin use INSERT IGNORE, and
-- the demo-users* rows use ON DUPLICATE KEY UPDATE so re-running restores their canned records
-- rather than failing on a duplicate username.
--
-- Heads up: `mvn test`'s DB-integration tier (UserDaoTest, MatchmakingQueueTest, etc.) clears
-- the whole User table in @BeforeEach as part of its own test isolation -- it doesn't know these
-- rows are "yours" vs. test fixtures, so a full `mvn test` run against this same database will
-- wipe these accounts along with everything else. Just re-run this script afterward.

-- playera / 1234
INSERT IGNORE INTO User (Username, Password, IsAdmin)
VALUES ('playera', '$2a$10$nKQEh7WzfMdtCm5WLAPTn.sHV.xJa8CdUXba9x.U1BRjJsrIsjkSy', FALSE);

-- playerb / 1234
INSERT IGNORE INTO User (Username, Password, IsAdmin)
VALUES ('playerb', '$2a$10$xYzCmN/X0wGayUMeeqnyCuVRs0yI.TISm7AquZkGdqgZ5QS7Y9kd2', FALSE);

-- admin / admin
INSERT IGNORE INTO User (Username, Password, IsAdmin)
VALUES ('admin', '$2a$10$tD44.YsPZNrh5f0EE9X0rOZPX/CfGiDCIz6H/84xYTtgMVfReps1u', TRUE);

-- demo-users1 / 1234  (12W / 3L / 1D)
-- demo-users2 / 1234  (6W / 6L / 4D)
-- demo-users3 / 1234  (2W / 10L / 3D)
INSERT INTO User (Username, Password, IsAdmin, Wins, Losses, Draws, Rating)
VALUES ('demo-users1', '$2a$10$AgFT2N.JlP8k9d.oDh9PVu0oKHsZNtZ2rsfSfb7fCCYSUVCjAUMrq', FALSE, 12, 3, 1, 1364)
AS new_row
ON DUPLICATE KEY UPDATE
    Wins = new_row.Wins,
    Losses = new_row.Losses,
    Draws = new_row.Draws,
    Rating = new_row.Rating;

INSERT INTO User (Username, Password, IsAdmin, Wins, Losses, Draws, Rating)
VALUES ('demo-users2', '$2a$10$t9JKjFheuiyrkOSymfePautyr8mJTpmZIqTNp6CWMw2PzpN99BfhK', FALSE, 6, 6, 4, 1200)
AS new_row
ON DUPLICATE KEY UPDATE
    Wins = new_row.Wins,
    Losses = new_row.Losses,
    Draws = new_row.Draws,
    Rating = new_row.Rating;

INSERT INTO User (Username, Password, IsAdmin, Wins, Losses, Draws, Rating)
VALUES ('demo-users3', '$2a$10$e01kGE6.8BCwO/8z6Z0QFORKDDNiIQDjumvzopkvQij1q3u6G7Vx2', FALSE, 2, 10, 3, 1088)
AS new_row
ON DUPLICATE KEY UPDATE
    Wins = new_row.Wins,
    Losses = new_row.Losses,
    Draws = new_row.Draws,
    Rating = new_row.Rating;
