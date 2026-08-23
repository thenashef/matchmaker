package com.matchmaker.server.game.eights;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record Card(String rank, String suit) {

    static final String[] RANKS = {"2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A"};
    static final String[] SUITS = {"H", "D", "C", "S"};
    private static final Set<String> SUIT_SET = Set.of(SUITS);

    String code() {
        return rank + suit;
    }

    boolean isEight() {
        return "8".equals(rank);
    }

    static boolean isValidSuit(String suit) {
        return suit != null && SUIT_SET.contains(suit);
    }

    static Card parse(String code) {
        if (code == null || code.length() < 2) {
            throw new IllegalArgumentException("Invalid card code: " + code);
        }
        if (code.startsWith("10")) {
            if (code.length() != 3) {
                throw new IllegalArgumentException("Invalid card code: " + code);
            }
            return new Card("10", code.substring(2));
        }
        if (code.length() != 2) {
            throw new IllegalArgumentException("Invalid card code: " + code);
        }
        return new Card(code.substring(0, 1), code.substring(1));
    }

    static List<Card> standardDeck() {
        List<Card> deck = new ArrayList<>(52);
        for (String suit : SUITS) {
            for (String rank : RANKS) {
                deck.add(new Card(rank, suit));
            }
        }
        return deck;
    }
}
