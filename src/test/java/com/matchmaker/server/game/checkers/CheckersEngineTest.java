package com.matchmaker.server.game.checkers;

import com.matchmaker.server.game.GameResult;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckersEngineTest {

    private final CheckersEngine engine = new CheckersEngine();

    @Test
    void initialState_hasTwelvePiecesPerSideOnTheStandardSquares() {
        JSONObject board = new JSONObject(engine.initialState());

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
        String move = "{\"path\":[\"b3\",\"a4\"]}";

        assertTrue(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_manMovingBackward_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"a4\":\"b\"}}";
        String move = "{\"path\":[\"a4\",\"b3\"]}";

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_movingOntoOccupiedSquare_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"a4\":\"w\"}}";
        String move = "{\"path\":[\"b3\",\"a4\"]}";

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_nonDiagonalStep_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";
        String move = "{\"path\":[\"b3\",\"b4\"]}";

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_movingOpponentsPiece_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"c4\":\"w\"}}";
        String move = "{\"path\":[\"c4\",\"b5\"]}";

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_kingMovesEitherDiagonalDirection() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"e4\":\"B\"}}";

        assertTrue(engine.isLegalMove(board, true, "{\"path\":[\"e4\",\"f5\"]}"));
        assertTrue(engine.isLegalMove(board, true, "{\"path\":[\"e4\",\"d3\"]}"));
    }

    @Test
    void isLegalMove_jumpingOverAdjacentOpponentOntoEmptySquare_isLegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\"}}";
        String move = "{\"path\":[\"b3\",\"d5\"]}";

        assertTrue(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_jumpingWithNoOpponentToCapture_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";
        String move = "{\"path\":[\"b3\",\"d5\"]}";

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_jumpingOntoOccupiedLandingSquare_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"d5\":\"w\"}}";
        String move = "{\"path\":[\"b3\",\"d5\"]}";

        assertFalse(engine.isLegalMove(board, true, move));
    }

    @Test
    void isLegalMove_captureIsMandatory_nonCaptureMoveIsIllegalWhenACaptureExists() {
        // b3 could step to a4, but c4 is capturable via a jump to d5, so ONLY the
        // capture is legal -- the simple step is not, even though it's otherwise valid.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\"}}";
        String simpleStep = "{\"path\":[\"b3\",\"a4\"]}";

        assertFalse(engine.isLegalMove(board, true, simpleStep));
    }

    @Test
    void isLegalMove_captureIsMandatory_appliesAcrossAllOfThePlayersPieces() {
        // f3 has no capture available itself, but b3 does (over c4 to d5) -- since a
        // capture exists SOMEWHERE for player1, f3's simple step is illegal too.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"f3\":\"b\"}}";
        String otherPieceStep = "{\"path\":[\"f3\",\"e4\"]}";

        assertFalse(engine.isLegalMove(board, true, otherPieceStep));
    }

    @Test
    void isLegalMove_multiJumpChain_isLegalAsOnePath() {
        // b3 jumps c4 landing d5, then must continue: jumps e6 landing f7.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"e6\":\"w\"}}";
        String chain = "{\"path\":[\"b3\",\"d5\",\"f7\"]}";

        assertTrue(engine.isLegalMove(board, true, chain));
    }

    @Test
    void isLegalMove_multiJumpChain_stoppingEarlyWhenAFurtherJumpIsAvailable_isIllegal() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"e6\":\"w\"}}";
        String stoppedEarly = "{\"path\":[\"b3\",\"d5\"]}";

        assertFalse(engine.isLegalMove(board, true, stoppedEarly));
    }

    @Test
    void isLegalMove_multiJumpChain_choosingAShorterNonOverlappingCaptureInstead_isLegal() {
        // Two separate, non-overlapping capture options for different player1 pieces:
        // b3 over c4 to d5 (a two-jump chain continuing to f7), and f3 over g4 to h5 (a
        // one-jump capture, no further jump available from h5). Both are legal choices --
        // majority-capture is not enforced.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"e6\":\"w\",\"f3\":\"b\",\"g4\":\"w\"}}";
        String shorterOption = "{\"path\":[\"f3\",\"h5\"]}";

        assertTrue(engine.isLegalMove(board, true, shorterOption));
    }

    @Test
    void isLegalMove_chainStopsImmediatelyOnPromotion_evenIfAFurtherJumpWouldExist() {
        // c6 (player1 man) jumps d7 landing e8 -- e8 is the promotion rank, so the chain
        // ends there even though a king at e8 could otherwise jump f7 to g6.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"c6\":\"b\",\"d7\":\"w\",\"f7\":\"w\"}}";
        String stopsAtPromotion = "{\"path\":[\"c6\",\"e8\"]}";
        String triesToContinuePastPromotion = "{\"path\":[\"c6\",\"e8\",\"g6\"]}";

        assertTrue(engine.isLegalMove(board, true, stopsAtPromotion));
        assertFalse(engine.isLegalMove(board, true, triesToContinuePastPromotion));
    }

    @Test
    void legalContinuations_emptyPath_returnsTheOnlyPiecesOrigin() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";

        List<String> continuations = engine.legalContinuations(board, true, "{\"path\":[]}");

        assertEquals(List.of("{\"path\":[\"b3\"]}"), continuations);
    }

    @Test
    void legalContinuations_mandatoryCapture_onlyCaptureCapableOriginsAreReturned() {
        // Same fixture as isLegalMove_captureIsMandatory_appliesAcrossAllOfThePlayersPieces --
        // b3 can capture c4, f3 cannot capture anything, so only b3 should come back.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"f3\":\"b\"}}";

        List<String> continuations = engine.legalContinuations(board, true, "{\"path\":[]}");

        assertEquals(List.of("{\"path\":[\"b3\"]}"), continuations);
    }

    @Test
    void legalContinuations_midMultiJumpChain_returnsTheNextForcedJumpSquare() {
        // Same fixture as isLegalMove_multiJumpChain_isLegalAsOnePath -- after the first jump
        // (b3 -> d5), the chain must continue to f7.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"e6\":\"w\"}}";

        List<String> continuations = engine.legalContinuations(board, true, "{\"path\":[\"b3\",\"d5\"]}");

        assertEquals(List.of("{\"path\":[\"b3\",\"d5\",\"f7\"]}"), continuations);
    }

    @Test
    void legalContinuations_alreadyCompleteMove_returnsEmpty() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";

        List<String> continuations = engine.legalContinuations(board, true, "{\"path\":[\"b3\",\"a4\"]}");

        assertTrue(continuations.isEmpty());
    }

    @Test
    void legalContinuations_malformedPartialMove_returnsEmptyRatherThanThrowing() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";

        List<String> continuations = engine.legalContinuations(board, true, "not json");

        assertTrue(continuations.isEmpty());
    }

    @Test
    void applyMove_simpleStep_movesThePieceAndLeavesTheOriginSquareEmpty() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";
        String move = "{\"path\":[\"b3\",\"a4\"]}";

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("b", pieces.getString("a4"));
        assertFalse(pieces.has("b3"));
        assertEquals(1, pieces.length());
    }

    @Test
    void applyMove_singleCapture_removesTheCapturedPieceAndMovesToTheLandingSquare() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\"}}";
        String move = "{\"path\":[\"b3\",\"d5\"]}";

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("b", pieces.getString("d5"));
        assertFalse(pieces.has("b3"));
        assertFalse(pieces.has("c4"));
        assertEquals(1, pieces.length());
    }

    @Test
    void applyMove_multiJumpChain_removesEveryCapturedPieceAlongThePath() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\",\"c4\":\"w\",\"e6\":\"w\"}}";
        String move = "{\"path\":[\"b3\",\"d5\",\"f7\"]}";

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("b", pieces.getString("f7"));
        assertEquals(1, pieces.length());
    }

    @Test
    void applyMove_manReachingTheFarRank_promotesToKing() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b7\":\"b\"}}";
        String move = "{\"path\":[\"b7\",\"a8\"]}";

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("B", pieces.getString("a8"));
    }

    @Test
    void applyMove_kingDoesNotChangeSymbolWhenAlreadyCrowned() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"c4\":\"B\"}}";
        String move = "{\"path\":[\"c4\",\"b5\"]}";

        String result = engine.applyMove(board, true, move);
        JSONObject pieces = new JSONObject(result).getJSONObject("pieces");

        assertEquals("B", pieces.getString("b5"));
    }

    @Test
    void checkWinner_gameContinuesWhenBothSidesHavePiecesAndMoves() {
        assertEquals(GameResult.CONTINUE, engine.checkWinner(engine.initialState(), false));
    }

    @Test
    void checkWinner_player2HasNoPiecesLeft_player1Wins() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b3\":\"b\"}}";

        assertEquals(GameResult.PLAYER1_WINS, engine.checkWinner(board, false));
    }

    @Test
    void checkWinner_player1HasNoPiecesLeft_player2Wins() {
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"f7\":\"w\"}}";

        assertEquals(GameResult.PLAYER2_WINS, engine.checkWinner(board, true));
    }

    @Test
    void checkWinner_playerAboutToMoveHasPiecesButNoLegalMove_thatPlayerLoses() {
        // A player2 man on b1 (rank 1) has no legal move: its forward direction (decreasing
        // row, since player2 moves toward rank 1) is off the board entirely.
        String board = "{\"rows\":8,\"cols\":8,\"pieces\":{\"b1\":\"w\"}}";

        assertEquals(GameResult.PLAYER1_WINS, engine.checkWinner(board, false));
    }
}
