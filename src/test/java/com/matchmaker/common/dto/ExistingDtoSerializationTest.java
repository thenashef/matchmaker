package com.matchmaker.common.dto;

import com.matchmaker.common.enums.GameStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExistingDtoSerializationTest {

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
    void userDTO_survivesSerializationRoundTrip() throws Exception {
        UserDTO original = new UserDTO(3, "checkers_king", false, 160, 55, 0, 1790);

        UserDTO restored = roundTrip(original);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getUsername(), restored.getUsername());
        assertEquals(original.isAdmin(), restored.isAdmin());
        assertEquals(original.getWins(), restored.getWins());
        assertEquals(original.getLosses(), restored.getLosses());
        assertEquals(original.getDraws(), restored.getDraws());
        assertEquals(original.getRating(), restored.getRating());
    }

    @Test
    void moveDTO_survivesSerializationRoundTrip() throws Exception {
        MoveDTO original = new MoveDTO(42, 3, 7, "{\"from\":\"b6\",\"to\":\"a5\"}");

        MoveDTO restored = roundTrip(original);

        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getUserId(), restored.getUserId());
        assertEquals(original.getMoveNumber(), restored.getMoveNumber());
        assertEquals(original.getPayload(), restored.getPayload());
    }

    @Test
    void gameStateDTO_survivesSerializationRoundTrip_withNullWinner() throws Exception {
        GameStateDTO original = new GameStateDTO(
            42, 1, 3, 4, GameStatus.ACTIVE, 3, null, "{\"board\":\"...\"}"
        );

        GameStateDTO restored = roundTrip(original);

        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getGameTypeId(), restored.getGameTypeId());
        assertEquals(original.getPlayer1Id(), restored.getPlayer1Id());
        assertEquals(original.getPlayer2Id(), restored.getPlayer2Id());
        assertEquals(original.getStatus(), restored.getStatus());
        assertEquals(original.getCurrentTurnUserId(), restored.getCurrentTurnUserId());
        assertEquals(original.getWinnerId(), restored.getWinnerId());
        assertEquals(original.getBoardState(), restored.getBoardState());
    }
}
