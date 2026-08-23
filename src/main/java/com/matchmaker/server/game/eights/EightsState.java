package com.matchmaker.server.game.eights;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

class EightsState {

    static final String GAME = "eights";

    final List<Card> hand1;
    final List<Card> hand2;
    final List<Card> draw;
    final List<Card> discard;
    String namedSuit;
    Card pendingDrawn;

    EightsState(List<Card> hand1, List<Card> hand2, List<Card> draw, List<Card> discard,
                String namedSuit, Card pendingDrawn) {
        this.hand1 = new ArrayList<>(hand1);
        this.hand2 = new ArrayList<>(hand2);
        this.draw = new ArrayList<>(draw);
        this.discard = new ArrayList<>(discard);
        this.namedSuit = namedSuit;
        this.pendingDrawn = pendingDrawn;
    }

    static EightsState fromJson(String json) {
        JSONObject obj = new JSONObject(json);
        return new EightsState(
                readCards(obj, "hand1"),
                readCards(obj, "hand2"),
                readCards(obj, "draw"),
                readCards(obj, "discard"),
                optionalString(obj, "namedSuit"),
                optionalCard(obj, "pendingDrawn"));
    }

    static boolean isEightsJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        return GAME.equalsIgnoreCase(new JSONObject(json).optString("game"));
    }

    String toJson() {
        JSONObject obj = new JSONObject();
        obj.put("game", GAME);
        obj.put("hand1", cardsToJson(hand1));
        obj.put("hand2", cardsToJson(hand2));
        obj.put("draw", cardsToJson(draw));
        obj.put("discard", cardsToJson(discard));
        putOptionalString(obj, "namedSuit", namedSuit);
        putCard(obj, "pendingDrawn", pendingDrawn);
        return obj.toString();
    }

    Card topDiscard() {
        if (discard.isEmpty()) {
            throw new IllegalStateException("Discard pile is empty");
        }
        return discard.get(discard.size() - 1);
    }

    List<Card> hand(boolean player1) {
        return player1 ? hand1 : hand2;
    }

    private static List<Card> readCards(JSONObject obj, String key) {
        JSONArray array = obj.getJSONArray(key);
        List<Card> cards = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            cards.add(Card.parse(array.getString(i)));
        }
        return cards;
    }

    private static JSONArray cardsToJson(List<Card> cards) {
        JSONArray array = new JSONArray();
        for (Card card : cards) {
            array.put(card.code());
        }
        return array;
    }

    private static String optionalString(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) {
            return null;
        }
        String value = obj.getString(key);
        return value.isBlank() ? null : value;
    }

    private static Card optionalCard(JSONObject obj, String key) {
        String code = optionalString(obj, key);
        return code == null ? null : Card.parse(code);
    }

    private static void putOptionalString(JSONObject obj, String key, String value) {
        if (value == null) {
            obj.put(key, JSONObject.NULL);
        } else {
            obj.put(key, value);
        }
    }

    private static void putCard(JSONObject obj, String key, Card card) {
        if (card == null) {
            obj.put(key, JSONObject.NULL);
        } else {
            obj.put(key, card.code());
        }
    }
}
