-- Idempotent seed: insert Crazy Eights if this database does not already have it.
-- schema.sql only runs on first Docker volume init, so a running container will not
-- pick up a new GameType row until you either start fresh or run this.
--
--   docker exec -i disconnect-timeout-mysql-1 mysql -umatchmaker -pmatchmaker matchmaker < db/seed-eights-gametype.sql
--
INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols)
SELECT 'Crazy Eights',
       'Play a card matching rank or suit. Eights are wild and name the next suit. First to empty their hand wins.',
       2, 2, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM GameType WHERE Name = 'Crazy Eights');
