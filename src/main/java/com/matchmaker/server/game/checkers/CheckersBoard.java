package com.matchmaker.server.game.checkers;

import org.json.JSONObject;

import java.util.Arrays;

class CheckersBoard {

    private final char[][] grid = new char[8][8];

    private CheckersBoard() {
        for (char[] row : grid) {
            Arrays.fill(row, '.');
        }
    }

    static CheckersBoard initial() {
        CheckersBoard board = new CheckersBoard();
        for (int row = 0; row <= 2; row++) {
            for (int col = 0; col < 8; col++) {
                if (isDarkSquare(row, col)) {
                    board.grid[row][col] = 'b';
                }
            }
        }
        for (int row = 5; row <= 7; row++) {
            for (int col = 0; col < 8; col++) {
                if (isDarkSquare(row, col)) {
                    board.grid[row][col] = 'w';
                }
            }
        }
        return board;
    }

    static CheckersBoard fromJson(String json) {
        CheckersBoard board = new CheckersBoard();
        JSONObject obj = new JSONObject(json);
        JSONObject pieces = obj.getJSONObject("pieces");
        for (String squareName : pieces.keySet()) {
            Square square = Square.fromAlgebraic(squareName);
            board.grid[square.row()][square.col()] = pieces.getString(squareName).charAt(0);
        }
        return board;
    }

    String toJson() {
        JSONObject pieces = new JSONObject();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                char piece = grid[row][col];
                if (piece != '.') {
                    pieces.put(new Square(row, col).toAlgebraic(), String.valueOf(piece));
                }
            }
        }
        JSONObject obj = new JSONObject();
        obj.put("rows", 8);
        obj.put("cols", 8);
        obj.put("pieces", pieces);
        return obj.toString();
    }

    CheckersBoard copy() {
        CheckersBoard copy = new CheckersBoard();
        for (int row = 0; row < 8; row++) {
            copy.grid[row] = grid[row].clone();
        }
        return copy;
    }

    char get(Square square) {
        return grid[square.row()][square.col()];
    }

    void set(Square square, char piece) {
        grid[square.row()][square.col()] = piece;
    }

    boolean isEmpty(Square square) {
        return get(square) == '.';
    }

    private static boolean isDarkSquare(int row, int col) {
        return (row + col) % 2 == 1;
    }

    static boolean isPlayer1Piece(char piece) {
        return piece == 'b' || piece == 'B';
    }

    static boolean isPlayer2Piece(char piece) {
        return piece == 'w' || piece == 'W';
    }

    static boolean isKing(char piece) {
        return piece == 'B' || piece == 'W';
    }

    static boolean ownedBy(char piece, boolean isPlayer1) {
        return isPlayer1 ? isPlayer1Piece(piece) : isPlayer2Piece(piece);
    }
}
