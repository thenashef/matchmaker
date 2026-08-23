package com.matchmaker.server.dao;

import com.matchmaker.common.dto.ChatMessageDTO;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JdbcChatMessageDao implements ChatMessageDao {

    private static final int MAX_HISTORY_MESSAGES = 200;

    private final DataSource dataSource;

    public JdbcChatMessageDao(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void insert(int sessionId, int userId, String content) {
        String sql = "INSERT INTO ChatMessage (SessionID, UserID, Content) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            stmt.setInt(2, userId);
            stmt.setString(3, content);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DaoException("Failed to insert chat message for session " + sessionId, e);
        }
    }

    @Override
    public List<ChatMessageDTO> findBySession(int sessionId) {
        String sql = "SELECT SessionID, UserID, Content, SentAt FROM ChatMessage "
                + "WHERE SessionID = ? ORDER BY SentAt DESC, ID DESC LIMIT " + MAX_HISTORY_MESSAGES;
        List<ChatMessageDTO> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new ChatMessageDTO(rs.getInt("SessionID"), rs.getInt("UserID"),
                            rs.getString("Content"), rs.getTimestamp("SentAt").toLocalDateTime()));
                }
            }
            Collections.reverse(result);
            return result;
        } catch (SQLException e) {
            throw new DaoException("Failed to find chat messages for session " + sessionId, e);
        }
    }
}
