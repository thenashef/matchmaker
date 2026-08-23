package com.matchmaker.server.dao;

import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.server.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTypeDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final GameTypeDao gameTypeDao = new JdbcGameTypeDao(DATA_SOURCE);

    @BeforeEach
    void cleanTables() throws Exception {
        TestDatabase.cleanAll(DATA_SOURCE);
    }

    @Test
    void findAll_noGameTypes_returnsEmptyList() {
        assertTrue(gameTypeDao.findAll().isEmpty());
    }

    @Test
    void findAll_withGameTypes_returnsThemInInsertOrder() throws Exception {
        insertGameType("Checkers", "Classic checkers", 2, 2, 8, 8);
        insertGameType("Chess", "Classic chess", 2, 2, 8, 8);

        List<GameTypeDTO> result = gameTypeDao.findAll();

        assertEquals(2, result.size());
        assertEquals("Checkers", result.get(0).getName());
        assertEquals("Chess", result.get(1).getName());
    }

    @Test
    void findById_existingRow_returnsIt() {
        GameTypeDTO created = gameTypeDao.insert(new GameTypeDTO(0, "Crazy Eights", "card game", 2, 2, 1, 1));

        Optional<GameTypeDTO> found = gameTypeDao.findById(created.getId());

        assertTrue(found.isPresent());
        assertEquals("Crazy Eights", found.get().getName());
        assertEquals(created.getId(), found.get().getId());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertTrue(gameTypeDao.findById(999).isEmpty());
    }

    @Test
    void insert_returnsTheCreatedGameTypeWithARealId() {
        GameTypeDTO created = gameTypeDao.insert(new GameTypeDTO(0, "Battleship", "Naval combat", 2, 2, 10, 10));

        assertTrue(created.getId() > 0);
        assertEquals("Battleship", created.getName());
        assertEquals(1, gameTypeDao.findAll().size());
    }

    private void insertGameType(String name, String description, int minPlayers, int maxPlayers,
                                 int rows, int cols) throws Exception {
        String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DATA_SOURCE.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, description);
            stmt.setInt(3, minPlayers);
            stmt.setInt(4, maxPlayers);
            stmt.setInt(5, rows);
            stmt.setInt(6, cols);
            stmt.executeUpdate();
        }
    }
}
