package com.matchmaker.server.dao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcUserDao implements UserDao {

    private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;

    private final DataSource dataSource;

    public JdbcUserDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<UserRecord> insert(String username, String passwordHash) {
        String sql = "INSERT INTO User (Username, Password) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, passwordHash);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return findById(conn, keys.getInt(1));
            }
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() == MYSQL_DUPLICATE_ENTRY_ERROR_CODE) {
                return Optional.empty();
            }
            throw new DaoException("Failed to insert user '" + username + "'", e);
        } catch (SQLException e) {
            throw new DaoException("Failed to insert user '" + username + "'", e);
        }
    }

    @Override
    public Optional<UserRecord> findByUsername(String username) {
        String sql = "SELECT ID, Username, Password, IsAdmin, Wins, Losses, Draws, Rating, CreatedAt "
                + "FROM User WHERE Username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find user '" + username + "'", e);
        }
    }

    @Override
    public Optional<UserRecord> findById(int id) {
        String sql = "SELECT ID, Username, Password, IsAdmin, Wins, Losses, Draws, Rating, CreatedAt "
                + "FROM User WHERE ID = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find user " + id, e);
        }
    }

    @Override
    public List<UserRecord> findAll() {
        String sql = "SELECT ID, Username, Password, IsAdmin, Wins, Losses, Draws, Rating, CreatedAt "
                + "FROM User ORDER BY ID";
        List<UserRecord> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to list users", e);
        }
    }

    private Optional<UserRecord> findById(Connection conn, int id) throws SQLException {
        String sql = "SELECT ID, Username, Password, IsAdmin, Wins, Losses, Draws, Rating, CreatedAt "
                + "FROM User WHERE ID = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    private static UserRecord mapRow(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getInt("ID"),
                rs.getString("Username"),
                rs.getString("Password"),
                rs.getBoolean("IsAdmin"),
                rs.getInt("Wins"),
                rs.getInt("Losses"),
                rs.getInt("Draws"),
                rs.getInt("Rating"),
                rs.getTimestamp("CreatedAt").toLocalDateTime());
    }
}
