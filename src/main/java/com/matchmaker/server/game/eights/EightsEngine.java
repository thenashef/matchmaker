package com.matchmaker.server.game.eights;

import com.matchmaker.server.game.GameEngine;
import com.matchmaker.server.game.GameResult;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class EightsEngine implements GameEngine {

    private final Random random;

    public EightsEngine() {
        this(new Random());
    }

    public EightsEngine(Random random) {
        this.random = random;
    }

    @Override
    public String initialState() {
        List<Card> deck = Card.standardDeck();
        shuffle(deck);
        List<Card> hand1 = new ArrayList<>(deck.subList(0, 7));
        List<Card> hand2 = new ArrayList<>(deck.subList(7, 14));
        List<Card> rest = new ArrayList<>(deck.subList(14, 52));
        Card starter = rest.remove(0);
        List<Card> discard = new ArrayList<>();
        discard.add(starter);
        String namedSuit = starter.isEight() ? starter.suit() : null;
        return new EightsState(hand1, hand2, rest, discard, namedSuit, null).toJson();
    }

    @Override
    public boolean isLegalMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson) {
        EightsState state;
        try {
            state = EightsState.fromJson(stateJson);
        } catch (RuntimeException e) {
            return false;
        }
        for (String legal : legalMoves(state, isPlayer1Turn)) {
            if (sameMove(legal, movePayloadJson)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> legalContinuations(String stateJson, boolean isPlayer1Turn, String partialMovePayloadJson) {
        try {
            return List.copyOf(legalMoves(EightsState.fromJson(stateJson), isPlayer1Turn));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public String applyMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson) {
        if (!isLegalMove(stateJson, isPlayer1Turn, movePayloadJson)) {
            throw new IllegalArgumentException("Illegal Crazy Eights move: " + movePayloadJson);
        }
        EightsState state = EightsState.fromJson(stateJson);
        JSONObject payload = new JSONObject(movePayloadJson);
        if ("draw".equals(payload.getString("action"))) {
            applyDraw(state, isPlayer1Turn);
        } else {
            applyPlay(state, isPlayer1Turn, Card.parse(payload.getString("card")), optionalSuit(payload));
        }
        return state.toJson();
    }

    @Override
    public GameResult checkWinner(String stateJson, boolean isPlayer1ToMoveNext) {
        EightsState state = EightsState.fromJson(stateJson);
        if (state.hand1.isEmpty()) {
            return GameResult.PLAYER1_WINS;
        }
        if (state.hand2.isEmpty()) {
            return GameResult.PLAYER2_WINS;
        }
        if (state.pendingDrawn != null) {
            return GameResult.CONTINUE;
        }
        List<Card> nextHand = state.hand(isPlayer1ToMoveNext);
        if (hasLegalPlay(nextHand, state) || canDraw(state)) {
            return GameResult.CONTINUE;
        }
        int size1 = state.hand1.size();
        int size2 = state.hand2.size();
        if (size1 < size2) {
            return GameResult.PLAYER1_WINS;
        }
        if (size2 < size1) {
            return GameResult.PLAYER2_WINS;
        }
        return GameResult.DRAW;
    }

    @Override
    public boolean retainsTurn(String stateJson) {
        try {
            return EightsState.fromJson(stateJson).pendingDrawn != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<String> legalMoves(EightsState state, boolean isPlayer1Turn) {
        List<String> moves = new ArrayList<>();
        List<Card> hand = state.hand(isPlayer1Turn);
        if (state.pendingDrawn != null) {
            if (hand.contains(state.pendingDrawn)) {
                addPlayPayloads(moves, state.pendingDrawn);
            }
            return moves;
        }
        boolean anyPlay = false;
        for (Card card : hand) {
            if (isLegalPlay(card, state)) {
                addPlayPayloads(moves, card);
                anyPlay = true;
            }
        }
        if (!anyPlay && canDraw(state)) {
            moves.add(new JSONObject().put("action", "draw").toString());
        }
        return moves;
    }

    private void applyDraw(EightsState state, boolean isPlayer1Turn) {
        rebuildDrawIfNeeded(state);
        if (state.draw.isEmpty()) {
            throw new IllegalStateException("Cannot draw: draw pile is empty and cannot be rebuilt");
        }
        Card drawn = state.draw.remove(0);
        state.hand(isPlayer1Turn).add(drawn);
        state.pendingDrawn = isLegalPlay(drawn, state) ? drawn : null;
    }

    private void applyPlay(EightsState state, boolean isPlayer1Turn, Card card, String suit) {
        List<Card> hand = state.hand(isPlayer1Turn);
        if (!hand.remove(card)) {
            throw new IllegalStateException("Card " + card.code() + " is not in the player's hand");
        }
        state.discard.add(card);
        state.namedSuit = card.isEight() ? suit : null;
        state.pendingDrawn = null;
    }

    private void shuffle(List<Card> cards) {
        synchronized (random) {
            Collections.shuffle(cards, random);
        }
    }

    private void rebuildDrawIfNeeded(EightsState state) {
        if (!state.draw.isEmpty()) {
            return;
        }
        if (state.discard.size() <= 1) {
            return;
        }
        Card top = state.discard.remove(state.discard.size() - 1);
        shuffle(state.discard);
        state.draw.addAll(state.discard);
        state.discard.clear();
        state.discard.add(top);
    }

    private static boolean isLegalPlay(Card card, EightsState state) {
        if (card.isEight()) {
            return true;
        }
        if (state.namedSuit != null) {
            return card.suit().equals(state.namedSuit);
        }
        Card top = state.topDiscard();
        return card.rank().equals(top.rank()) || card.suit().equals(top.suit());
    }

    private static boolean hasLegalPlay(List<Card> hand, EightsState state) {
        for (Card card : hand) {
            if (isLegalPlay(card, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canDraw(EightsState state) {
        return !state.draw.isEmpty() || state.discard.size() > 1;
    }

    private static void addPlayPayloads(List<String> moves, Card card) {
        if (card.isEight()) {
            for (String suit : Card.SUITS) {
                moves.add(new JSONObject()
                        .put("action", "play")
                        .put("card", card.code())
                        .put("suit", suit)
                        .toString());
            }
        } else {
            moves.add(new JSONObject()
                    .put("action", "play")
                    .put("card", card.code())
                    .toString());
        }
    }

    private static boolean sameMove(String legalJson, String candidateJson) {
        JSONObject legal;
        JSONObject candidate;
        try {
            legal = new JSONObject(legalJson);
            candidate = new JSONObject(candidateJson);
        } catch (RuntimeException e) {
            return false;
        }
        if (!legal.optString("action").equals(candidate.optString("action"))) {
            return false;
        }
        if ("draw".equals(legal.optString("action"))) {
            return true;
        }
        if (!legal.optString("card").equals(candidate.optString("card"))) {
            return false;
        }
        return Objects.equals(optionalSuit(legal), optionalSuit(candidate));
    }

    private static String optionalSuit(JSONObject payload) {
        if (!payload.has("suit") || payload.isNull("suit")) {
            return null;
        }
        String suit = payload.getString("suit");
        return suit.isBlank() ? null : suit;
    }
}
