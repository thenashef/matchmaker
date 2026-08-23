package com.matchmaker.server.dao;

import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.server.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final ChatMessageDao chatMessageDao = new JdbcChatMessageDao(DATA_SOURCE);

    private int gameTypeId;
    private int sessionId;
    private int player1Id;
    private int player2Id;

    @BeforeEach
    void cleanTablesAndInsertFixtures() throws Exception {
        TestDatabase.cleanAll(DATA_SOURCE);
        gameTypeId = insertGameType("Checkers");
        player1Id = insertUser("player1");
        player2Id = insertUser("player2");
        sessionId = insertActiveSession(gameTypeId, player1Id, player2Id);
    }

    @Test
    void insert_thenFindBySession_returnsTheMessage() {
        chatMessageDao.insert(sessionId, player1Id, "good luck");

        List<ChatMessageDTO> history = chatMessageDao.findBySession(sessionId);

        assertEquals(1, history.size());
        assertEquals(sessionId, history.get(0).getSessionId());
        assertEquals(player1Id, history.get(0).getUserId());
        assertEquals("good luck", history.get(0).getContent());
    }

    @Test
    void findBySession_returnsMessagesInChronologicalOrder() throws Exception {
        chatMessageDao.insert(sessionId, player1Id, "first");
        Thread.sleep(10);
        chatMessageDao.insert(sessionId, player2Id, "second");

        List<ChatMessageDTO> history = chatMessageDao.findBySession(sessionId);

        assertEquals(2, history.size());
        assertEquals("first", history.get(0).getContent());
        assertEquals("second", history.get(1).getContent());
    }

    @Test
    void findBySession_onlyReturnsMessagesForThatSession() throws Exception {
        int otherSessionId = insertActiveSession(gameTypeId, player1Id, player2Id);
        chatMessageDao.insert(sessionId, player1Id, "in session one");
        chatMessageDao.insert(otherSessionId, player1Id, "in session two");

        List<ChatMessageDTO> history = chatMessageDao.findBySession(sessionId);

        assertEquals(1, history.size());
        assertEquals("in session one", history.get(0).getContent());
    }

    @Test
    void findBySession_unknownSession_returnsEmptyList() {
        assertTrue(chatMessageDao.findBySession(999999).isEmpty());
    }

    private int insertGameType(String name) throws Exception {
        String sql = "INSERT INTO GameType (Name, Description, MinPlayers, MaxPlayers, BoardRows, BoardCols) "
                + "VALUES (?, 'desc', 2, 2, 8, 8)";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int insertUser(String username) throws Exception {
        String sql = "INSERT INTO User (Username, Password) VALUES (?, 'hash')";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private int insertActiveSession(int gameTypeId, int player1Id, int player2Id) throws Exception {
        String sql = "INSERT INTO GameSession (GameTypeID, Player1ID, Player2ID, Status, CurrentTurnUserID, "
                + "BoardState, TurnStartedAt, StartTime) VALUES (?, ?, ?, 'ACTIVE', ?, '{}', NOW(), NOW())";
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setInt(1, gameTypeId);
            stmt.setInt(2, player1Id);
            stmt.setInt(3, player2Id);
            stmt.setInt(4, player1Id);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }
}
