package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NewDtoSerializationTest {

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T original) throws Exception {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(byteStream)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(byteStream.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @Test
    void loginResultDTO_survivesSerializationRoundTrip() throws Exception {
        UserDTO user = new UserDTO(1, "alice", false, 10, 2, 1, 1240);
        LoginResultDTO original = new LoginResultDTO(user, "token-abc-123");

        LoginResultDTO restored = roundTrip(original);

        assertEquals(original.getSessionToken(), restored.getSessionToken());
        assertEquals(original.getUser().getId(), restored.getUser().getId());
        assertEquals(original.getUser().getUsername(), restored.getUser().getUsername());
    }

    @Test
    void gameTypeDTO_survivesSerializationRoundTrip() throws Exception {
        GameTypeDTO original = new GameTypeDTO(1, "Checkers", "Classic checkers", 2, 2, 8, 8);

        GameTypeDTO restored = roundTrip(original);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getName(), restored.getName());
        assertEquals(original.getDescription(), restored.getDescription());
        assertEquals(original.getMinPlayers(), restored.getMinPlayers());
        assertEquals(original.getMaxPlayers(), restored.getMaxPlayers());
        assertEquals(original.getBoardRows(), restored.getBoardRows());
        assertEquals(original.getBoardCols(), restored.getBoardCols());
    }

    @Test
    void chatMessageDTO_survivesSerializationRoundTrip() throws Exception {
        LocalDateTime sentAt = LocalDateTime.of(2026, 8, 5, 14, 30);
        ChatMessageDTO original = new ChatMessageDTO(42, 7, "good luck", sentAt);

        ChatMessageDTO restored = roundTrip(original);

        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getUserId(), restored.getUserId());
        assertEquals(original.getContent(), restored.getContent());
        assertEquals(original.getSentAt(), restored.getSentAt());
    }

    @Test
    void gameEventDTO_chatMessageVariant_survivesSerializationRoundTrip() throws Exception {
        GameEventDTO original = new GameEventDTO(GameEventType.CHAT_MESSAGE, 7, 42, "good luck");

        GameEventDTO restored = roundTrip(original);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getChatSenderUserId(), restored.getChatSenderUserId());
        assertEquals(original.getChatContent(), restored.getChatContent());
    }

    @Test
    void adminDashboardStatsDTO_survivesSerializationRoundTrip() throws Exception {
        AdminDashboardStatsDTO original = new AdminDashboardStatsDTO(4, 2, 9, 1);

        AdminDashboardStatsDTO restored = roundTrip(original);

        assertEquals(original.getOnlinePlayers(), restored.getOnlinePlayers());
        assertEquals(original.getActiveGames(), restored.getActiveGames());
        assertEquals(original.getGamesToday(), restored.getGamesToday());
        assertEquals(original.getOpenInQueue(), restored.getOpenInQueue());
    }

    @Test
    void gameEventDTO_survivesSerializationRoundTrip() throws Exception {
        GameStateDTO gameState = new GameStateDTO(7, 1, 42, 99, GameStatus.ACTIVE, 42, null, null);
        GameEventDTO original = new GameEventDTO(GameEventType.MATCH_FOUND, 7, gameState);

        GameEventDTO restored = roundTrip(original);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getGameState().getPlayer1Id(), restored.getGameState().getPlayer1Id());
        assertEquals(original.getGameState().getPlayer2Id(), restored.getGameState().getPlayer2Id());
    }
}
