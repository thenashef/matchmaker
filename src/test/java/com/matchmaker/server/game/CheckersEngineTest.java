package com.matchmaker.server.game;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckersEngineTest {

    private final CheckersEngine engine = new CheckersEngine();

    @Test
    void initialBoardState_hasTwelvePiecesPerSideOnTheStandardSquares() {
        JSONObject board = new JSONObject(engine.initialBoardState());

        assertEquals(8, board.getInt("rows"));
        assertEquals(8, board.getInt("cols"));

        JSONObject pieces = board.getJSONObject("pieces");
        assertEquals(24, pieces.length());

        // Spot-check a few known starting squares (rank 1-3 = player1 'b', rank 6-8 = player2 'w').
        assertEquals("b", pieces.getString("b1"));
        assertEquals("b", pieces.getString("a2"));
        assertEquals("b", pieces.getString("d3"));
        assertEquals("w", pieces.getString("a6"));
        assertEquals("w", pieces.getString("h7"));
        assertEquals("w", pieces.getString("a8"));

        // The two middle ranks (4 and 5) start empty -- spot-check one square from each.
        assertEquals(false, pieces.has("a4"));
        assertEquals(false, pieces.has("b5"));

        // Light squares are never occupied, even on the starting ranks.
        assertEquals(false, pieces.has("a1"));
    }
}
