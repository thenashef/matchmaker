package com.matchmaker.server.game;

public record Square(int row, int col) {

    public static Square fromAlgebraic(String algebraic) {
        char file = algebraic.charAt(0);
        char rank = algebraic.charAt(1);
        return new Square(rank - '1', file - 'a');
    }

    public String toAlgebraic() {
        char file = (char) ('a' + col);
        char rank = (char) ('1' + row);
        return "" + file + rank;
    }

    public boolean isInBounds() {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }
}
