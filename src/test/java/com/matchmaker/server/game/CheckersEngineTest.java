package com.matchmaker.server.game;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void isLegalMove_manMovingOneStepDiagonallyForwardOntoEmptySquare_isLegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";
        Move move = Move.fromJson("{\"path\":[\"b3\",\"a4\"]}");

        assertTrue(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_manMovingBackward_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"a4\":\"b\"}}";
        Move move = Move.fromJson("{\"path\":[\"a4\",\"b3\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_movingOntoOccupiedSquare_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"a4\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"b3\",\"a4\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_nonDiagonalStep_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";
        Move move = Move.fromJson("{\"path\":[\"b3\",\"b4\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_movingOpponentsPiece_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"c4\":\"w\"}}";
        Move move = Move.fromJson("{\"path\":[\"c4\",\"b5\"]}");

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_kingMovesEitherDiagonalDirection() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"e4\":\"B\"}}";

        assertTrue(engine.isLegalMove(board, true, Move.fromJson("{\"path\":[\"e4\",\"f5\"]}")));
        assertTrue(engine.isLegalMove(board, true, Move.fromJson("{\"path\":[\"e4\",\"d3\"]}")));
    }
}
