package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcGameTypeDao implements GameTypeDao {

    private final DataSource dataSource;

    public JdbcGameTypeDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<GameTypeDTO> findAll() {
        String sql = "SELECT " + GameTypeSql.COLUMNS + " "
                + "FROM GameType ORDER BY ID";
        List<GameTypeDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(fromRow(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to list game types", e);
        }
    }

    @Override
    public Optional<GameTypeDTO> findById(int id) {
        String sql = "SELECT " + GameTypeSql.COLUMNS + " "
                + "FROM GameType WHERE ID = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(fromRow(rs));
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to find game type " + id, e);
        }
    }

    @Override
    public GameTypeDTO insert(GameTypeDTO newGameType) {
        String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, newGameType.getName());
            stmt.setString(2, newGameType.getDescription());
            stmt.setInt(3, newGameType.getMinPlayers());
            stmt.setInt(4, newGameType.getMaxPlayers());
            stmt.setInt(5, newGameType.getBoardRows());
            stmt.setInt(6, newGameType.getBoardCols());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                int newId = keys.getInt(1);
                return new GameTypeDTO(newId, newGameType.getName(), newGameType.getDescription(),
                        newGameType.getMinPlayers(), newGameType.getMaxPlayers(),
                        newGameType.getBoardRows(), newGameType.getBoardCols());
            }
        } catch (SQLException e) {
            throw new DaoException("Failed to insert game type '" + newGameType.getName() + "'", e);
        }
    }

    private static GameTypeDTO fromRow(ResultSet rs) throws SQLException {
        return new GameTypeDTO(
                rs.getInt("ID"),
                rs.getString("Name"),
                rs.getString("Description"),
                rs.getInt("MinPlayers"),
                rs.getInt("MaxPlayers"),
                rs.getInt("BoardRows"),
                rs.getInt("BoardCols"));
    }
}
