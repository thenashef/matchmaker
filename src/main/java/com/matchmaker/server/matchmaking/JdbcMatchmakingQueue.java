package com.matchmaker.server.matchmaking;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.server.dao.DaoException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * JDBC-backed {@link MatchmakingQueue}: pairs waiting players and creates the resulting
 * {@code GameSession} row, all inside a single database transaction.
 *
 * <h2>Why both join() and cancel() are synchronized</h2>
 * Both methods are {@code synchronized} on {@code this} (the single {@code
 * JdbcMatchmakingQueue} instance shared by the whole server process) — not just {@code
 * join()}. It's tempting to think only {@code join()} needs protection, since pairing is
 * where the "two threads race to match the same opponent" bug would live. But {@code
 * cancel()} touches the exact same {@code MatchmakingQueue} rows {@code join()} reads and
 * deletes, so without its own lock a {@code cancel()} call could interleave with an
 * in-flight {@code join()} call's pairing logic: a player could call {@code cancel()}
 * believing they've backed out of the queue, while a concurrent {@code join()} on another
 * thread has already read their row as the opponent and is about to commit a
 * {@code GameSession} pairing them anyway. Synchronizing both methods on the same monitor
 * means at most one of them is ever touching the queue at a time, so that race can't happen.
 *
 * <h2>Why commit-before-unlock makes this actually correct</h2>
 * Java's {@code synchronized} only guarantees mutual exclusion in memory — it says nothing
 * about the database by itself. What makes this safe is that the transaction's {@code
 * commit()} happens <em>inside</em> the synchronized method, before the monitor is
 * released (see {@link #join(int, int, String)}: {@code commit()} is called, then the method
 * returns, releasing the lock only after that). That ordering matters: it means the next
 * thread to acquire the lock — whether it's another {@code join()} or a {@code cancel()} —
 * is guaranteed to see an already-committed, fully consistent view of the queue and session
 * tables. If the lock were released before (or without) committing, a second thread could
 * read stale or half-written state and pair against a row that's about to be rolled back.
 *
 * <h2>What this does NOT protect against</h2>
 * This scheme assumes a single, non-clustered server JVM — the same assumption {@code
 * SessionManager}'s in-memory token map already makes elsewhere in this codebase. A {@code
 * synchronized} block only coordinates threads within one JVM; it has no effect across
 * processes. If a second {@code ServerMain} process were run against the same MySQL
 * database (e.g. for horizontal scaling), its own {@code JdbcMatchmakingQueue} instance
 * would have a completely separate monitor, and the two processes could race each other
 * and double-match a player, because there is deliberately no {@code SELECT ... FOR UPDATE}
 * (or other database-level row locking) backing this up. That's an accepted limitation for
 * this course project's single-server design, not an oversight.
 */
public class JdbcMatchmakingQueue implements MatchmakingQueue {

    private final DataSource dataSource;

    public JdbcMatchmakingQueue(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public synchronized GameStateDTO join(int userId, int gameTypeId, String initialBoardState)
            throws AlreadyInGameException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                GameStateDTO result = pairOrEnqueue(conn, userId, gameTypeId, initialBoardState);
                conn.commit();
                return result;
            } catch (AlreadyInGameException e) {
                // Raised before anything is written, so this rollback is a formality -- but it
                // hands a clean connection back to the pool rather than one mid-transaction.
                conn.rollback();
                throw e;
            } catch (SQLException e) {
                conn.rollback();
                throw new DaoException("Failed to join matchmaking queue for user " + userId, e);
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to join matchmaking queue for user " + userId, e);
        }
    }

    @Override
    public synchronized void cancel(int userId) {
        String sql = "DELETE FROM MatchmakingQueue WHERE UserID = ? AND Status = 'WAITING'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to cancel matchmaking queue for user " + userId, e);
        }
    }

    private GameStateDTO pairOrEnqueue(Connection conn, int userId, int gameTypeId, String initialBoardState)
            throws SQLException, AlreadyInGameException {
        // The "one queue slot per user" guard below only ever consulted the MatchmakingQueue
        // table, which left a whole second route to a double-match: a player with a game
        // already in progress could queue again and be paired into a concurrent session, while
        // the first one sat there running down its turn timer toward a loss they never saw.
        //
        // Checked inside this same transaction, not up in PlayerServiceImpl, for the same
        // reason the queue check is: it is only meaningful if it can't interleave with the
        // pairing logic below, and everything here runs under the instance monitor with the
        // commit happening before the lock is released.
        String findActiveSessionSql = "SELECT ID FROM GameSession "
                + "WHERE (Player1ID = ? OR Player2ID = ?) AND Status = 'ACTIVE' LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(findActiveSessionSql)) {
            stmt.setInt(1, userId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    throw new AlreadyInGameException("User " + userId + " is already in active game session "
                            + rs.getInt("ID"));
                }
            }
        }

        // Deliberately NOT scoped by GameTypeID: a user can only ever occupy one
        // queue slot at a time, for one game type, mirroring cancel(int userId)'s
        // "one queue slot per user" model below. Scoping this check by game type as
        // well would let the same user queue for checkers AND chess simultaneously,
        // each row independently eligible to be matched by a different opponent -
        // i.e. the exact double-match bug this guard exists to prevent, just reached
        // via a second game type instead of a second call for the same one.
        //
        // This check runs unconditionally, before any opponent lookup: if it only ran
        // on the "no opponent found" path, a caller who already has a WAITING row for
        // a different game type would skip straight past it whenever an opponent for
        // *this* game type happened to be waiting, leaving their other row stale and
        // eligible to be matched a second time later.
        String findOwnRowSql = "SELECT ID FROM MatchmakingQueue "
                + "WHERE UserID = ? AND Status = 'WAITING' LIMIT 1";
        boolean alreadyQueued;
        try (PreparedStatement stmt = conn.prepareStatement(findOwnRowSql)) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                alreadyQueued = rs.next();
            }
        }

        if (alreadyQueued) {
            return null;
        }

        Integer opponentQueueId = null;
        Integer opponentUserId = null;

        String findOpponentSql = "SELECT ID, UserID FROM MatchmakingQueue "
                + "WHERE GameTypeID = ? AND UserID != ? AND Status = 'WAITING' "
                + "ORDER BY JoinedAt ASC, ID ASC LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(findOpponentSql)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    opponentQueueId = rs.getInt("ID");
                    opponentUserId = rs.getInt("UserID");
                }
            }
        }

        if (opponentUserId == null) {
            String insertQueueRowSql = "INSERT INTO MatchmakingQueue (UserID, GameTypeID, Status, JoinedAt) "
                    + "VALUES (?, ?, 'WAITING', ?)";
            try (PreparedStatement stmt = conn.prepareStatement(insertQueueRowSql)) {
                stmt.setInt(1, userId);
                stmt.setInt(2, gameTypeId);
                stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                stmt.executeUpdate();
            }
            return null;
        }

        // TurnStartedAt/StartTime are evaluated by MySQL's own NOW(), not a Java-side Timestamp --
        // JdbcGameSessionDao.recordMove() and currentTurnStartedAt() both trust NOW()/the DB's
        // stored value directly, so this write path has to agree with them on which clock is
        // authoritative rather than risk a JVM-vs-server timezone mismatch on the very first read.
        // BoardState is written here, at creation, rather than being left null until the first
        // move lands. A null board is not a state the rest of the system can render: the admin's
        // live monitor, the watchdog's SESSION_ABANDONED push and an admin force-end all read the
        // session straight back out and hand it to a client, and every one of them threw on the
        // null. Two callers used to paper over it with a GameEngine.initialState() fallback; the
        // other three didn't, which is what made an idle first turn crash the opponent's board.
        String insertSessionSql = "INSERT INTO GameSession "
                + "(GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, BoardState, "
                + "TurnStartedAt, StartTime) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, NOW(), NOW())";
        int sessionId;
        try (PreparedStatement stmt = conn.prepareStatement(insertSessionSql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, opponentUserId);
            stmt.setInt(3, userId);
            stmt.setInt(4, opponentUserId);
            stmt.setString(5, initialBoardState);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                sessionId = keys.getInt(1);
            }
        }

        String deleteQueueRowSql = "DELETE FROM MatchmakingQueue WHERE ID = ?";
        try (PreparedStatement stmt = conn.prepareStatement(deleteQueueRowSql)) {
            stmt.setInt(1, opponentQueueId);
            stmt.executeUpdate();
        }

        return new GameStateDTO(sessionId, gameTypeId, opponentUserId, userId,
                GameStatus.ACTIVE, opponentUserId, null, initialBoardState);
    }
}
