package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcGameTypeDao implements GameTypeDao {

    private final DataSource dataSource;

    public JdbcGameTypeDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<GameTypeDTO> findAll() {
        String sql = "SELECT ID, Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols "
                + "FROM GameType ORDER BY ID";
        List<GameTypeDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new GameTypeDTO(
                        rs.getInt("ID"),
                        rs.getString("Name"),
                        rs.getString("Description"),
                        rs.getInt("MinPlayers"),
                        rs.getInt("MaxPlayers"),
                        rs.getInt("BoardRows"),
                        rs.getInt("BoardCols")));
            }
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to list game types", e);
        }
    }
}
