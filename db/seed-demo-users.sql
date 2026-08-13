-- Optional demo users for manually trying the JavaFX player client (roadmap step 8) without
-- registering through the UI first. Two ordinary players to match against each other, plus one
-- admin account for when the admin client (step 9) exists.
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
-- Re-run this any time the accounts are missing: it's idempotent (INSERT IGNORE), so re-running
-- it after the users already exist is a harmless no-op rather than a duplicate-username error.
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
