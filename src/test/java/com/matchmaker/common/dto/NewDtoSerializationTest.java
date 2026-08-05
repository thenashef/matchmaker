package com.matchmaker.common.dto;

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
}
