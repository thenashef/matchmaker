package com.matchmaker.server.game.eights;

import com.matchmaker.server.game.GameResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EightsEngineTest {

    private static final String DRAW = new JSONObject().put("action", "draw").toString();
    private final EightsEngine engine = new EightsEngine(new Random(1));

    @Test
    void initialState_dealsSevenCardsEachPlusDrawAndDiscardCoveringTheDeck() {
        JSONObject state = new JSONObject(engine.initialState());

        assertEquals("eights", state.getString("game"));
        JSONArray hand1 = state.getJSONArray("hand1");
        JSONArray hand2 = state.getJSONArray("hand2");
        JSONArray draw = state.getJSONArray("draw");
        JSONArray discard = state.getJSONArray("discard");
        assertEquals(7, hand1.length());
        assertEquals(7, hand2.length());
        assertEquals(1, discard.length());
        assertEquals(37, draw.length());

        Set<String> codes = new HashSet<>();
        addAll(codes, hand1);
        addAll(codes, hand2);
        addAll(codes, draw);
        addAll(codes, discard);
        assertEquals(52, codes.size());

        String starter = discard.getString(0);
        if (starter.startsWith("8") && !starter.startsWith("10")) {
            assertEquals(starter.substring(starter.length() - 1), state.getString("namedSuit"));
        } else {
            assertTrue(state.isNull("namedSuit"));
        }
    }

    @Test
    void isLegalMove_matchingSuitOrRank_isLegalAndMismatchIsNot() {
        String state = state(List.of("7H", "2S"), List.of("9D"), List.of("3C"), List.of("KH"), null);

        assertTrue(engine.isLegalMove(state, true, play("7H")));
        assertTrue(engine.isLegalMove(state, true, play("KD")));
        assertFalse(engine.isLegalMove(state, true, play("2S")));
        assertFalse(engine.isLegalMove(state, true, play("9D")));
        assertFalse(engine.isLegalMove(state, true, DRAW));
    }

    @Test
    void isLegalMove_matchingRank_isLegal() {
        String state = state(List.of("KD"), List.of("9D"), List.of("3C"), List.of("KH"), null);

        assertTrue(engine.isLegalMove(state, true, play("KD")));
    }

    @Test
    void isLegalMove_eightRequiresSuit() {
        String state = state(List.of("8C"), List.of("9D"), List.of("3C"), List.of("KH"), null);

        assertFalse(engine.isLegalMove(state, true, play("8C")));
        assertTrue(engine.isLegalMove(state, true, playEight("8C", "D")));
        assertFalse(engine.isLegalMove(state, true, playEight("8C", "X")));
    }

    @Test
    void isLegalMove_drawOnlyWhenNoPlay() {
        String canPlay = state(List.of("7H"), List.of("9D"), List.of("3C"), List.of("KH"), null);
        String mustDraw = state(List.of("2S"), List.of("9D"), List.of("3C"), List.of("KH"), null);

        assertFalse(engine.isLegalMove(canPlay, true, DRAW));
        assertTrue(engine.isLegalMove(mustDraw, true, DRAW));
        assertFalse(engine.isLegalMove(mustDraw, true, play("2S")));
    }

    @Test
    void applyMove_playingMatchingSuit_movesCardToDiscard() {
        String after = engine.applyMove(
                state(List.of("7H", "2S"), List.of("9D"), List.of("3C"), List.of("KH"), null),
                true, play("7H"));
        JSONObject obj = new JSONObject(after);

        assertEquals(List.of("2S"), toList(obj.getJSONArray("hand1")));
        assertEquals(List.of("KH", "7H"), toList(obj.getJSONArray("discard")));
        assertTrue(obj.isNull("namedSuit"));
        assertEquals(GameResult.CONTINUE, engine.checkWinner(after, false));
    }

    @Test
    void applyMove_playingEight_setsNamedSuit() {
        String after = engine.applyMove(
                state(List.of("8C", "2S"), List.of("9D"), List.of("3C"), List.of("KH"), null),
                true, playEight("8C", "D"));
        JSONObject obj = new JSONObject(after);

        assertEquals("D", obj.getString("namedSuit"));
        assertEquals("8C", obj.getJSONArray("discard").getString(1));
        String next = after;
        assertTrue(engine.isLegalMove(next, false, play("9D")));
        assertFalse(engine.isLegalMove(next, false, play("KH")));
    }

    @Test
    void applyMove_emptyingHand_player1Wins() {
        String after = engine.applyMove(
                state(List.of("7H"), List.of("9D", "2C"), List.of("3C"), List.of("KH"), null),
                true, play("7H"));

        assertEquals(0, new JSONObject(after).getJSONArray("hand1").length());
        assertEquals(GameResult.PLAYER1_WINS, engine.checkWinner(after, false));
    }

    @Test
    void applyMove_drawWhenPileEmpty_recyclesDiscardKeepingTopCard() {
        String after = engine.applyMove(
                state(List.of("2S"), List.of("9D"), List.of(), List.of("KH", "7D", "3C"), null),
                true, DRAW);
        JSONObject obj = new JSONObject(after);

        assertEquals(List.of("KH"), toList(obj.getJSONArray("discard")));
        List<String> hand1 = toList(obj.getJSONArray("hand1"));
        List<String> draw = toList(obj.getJSONArray("draw"));
        assertEquals(2, hand1.size());
        assertEquals("2S", hand1.get(0));
        assertEquals(1, draw.size());
        Set<String> recycled = new HashSet<>();
        recycled.add(hand1.get(1));
        recycled.add(draw.get(0));
        assertEquals(Set.of("7D", "3C"), recycled);
    }

    @Test
    void applyMove_drawingPlayableCard_retainsTurnUntilItIsPlayed() {
        String afterDraw = engine.applyMove(
                state(List.of("2S"), List.of("9D"), List.of("7H", "3C"), List.of("KH"), null),
                true, DRAW);

        assertTrue(engine.retainsTurn(afterDraw));
        assertEquals("7H", new JSONObject(afterDraw).getString("pendingDrawn"));
        assertTrue(engine.isLegalMove(afterDraw, true, play("7H")));
        assertFalse(engine.isLegalMove(afterDraw, true, DRAW));
        assertEquals(GameResult.CONTINUE, engine.checkWinner(afterDraw, true));

        String afterPlay = engine.applyMove(afterDraw, true, play("7H"));
        assertFalse(engine.retainsTurn(afterPlay));
        assertTrue(new JSONObject(afterPlay).isNull("pendingDrawn"));
    }

    @Test
    void checkWinner_stuckPlayerWithMoreCards_loses() {
        String state = state(List.of("2S", "3C"), List.of("9D"), List.of(), List.of("KH"), null);

        assertEquals(GameResult.PLAYER2_WINS, engine.checkWinner(state, true));
    }

    @Test
    void legalContinuations_eightListsEverySuit() {
        String state = state(List.of("8C"), List.of("9D"), List.of("3C"), List.of("KH"), null);
        List<String> legal = engine.legalContinuations(state, true, "{}");

        assertEquals(4, legal.size());
        Set<String> suits = new HashSet<>();
        for (String json : legal) {
            JSONObject move = new JSONObject(json);
            assertEquals("play", move.getString("action"));
            assertEquals("8C", move.getString("card"));
            suits.add(move.getString("suit"));
        }
        assertEquals(Set.of("H", "D", "C", "S"), suits);
    }

    private static String play(String card) {
        return new JSONObject().put("action", "play").put("card", card).toString();
    }

    private static String playEight(String card, String suit) {
        return new JSONObject().put("action", "play").put("card", card).put("suit", suit).toString();
    }

    private static String state(List<String> hand1, List<String> hand2, List<String> draw,
                                List<String> discard, String namedSuit) {
        JSONObject obj = new JSONObject();
        obj.put("game", "eights");
        obj.put("hand1", new JSONArray(new ArrayList<>(hand1)));
        obj.put("hand2", new JSONArray(new ArrayList<>(hand2)));
        obj.put("draw", new JSONArray(new ArrayList<>(draw)));
        obj.put("discard", new JSONArray(new ArrayList<>(discard)));
        if (namedSuit == null) {
            obj.put("namedSuit", JSONObject.NULL);
        } else {
            obj.put("namedSuit", namedSuit);
        }
        obj.put("pendingDrawn", JSONObject.NULL);
        return obj.toString();
    }

    private static List<String> toList(JSONArray array) {
        List<String> values = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }

    private static void addAll(Set<String> codes, JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            codes.add(array.getString(i));
        }
    }
}
