package com.matchmaker.server.dao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Optional;

public class JdbcUserDao implements UserDao {

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
            return findByUsername(username);
        } catch (SQLIntegrityConstraintViolationException e) {
            return Optional.empty();
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
                return Optional.of(new UserRecord(
                        rs.getInt("ID"),
                        rs.getString("Username"),
                        rs.getString("Password"),
                        rs.getBoolean("IsAdmin"),
                        rs.getInt("Wins"),
                        rs.getInt("Losses"),
                        rs.getInt("Draws"),
                        rs.getInt("Rating"),
                        rs.getTimestamp("CreatedAt").toLocalDateTime()));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find user '" + username + "'", e);
        }
    }
}
