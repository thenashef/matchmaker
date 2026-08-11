package com.matchmaker.server.game;

import java.util.ArrayList;
import java.util.List;

public class CheckersEngine implements GameEngine {

    private static final int[][] MAN_DIRECTIONS_PLAYER1 = {{1, -1}, {1, 1}};
    private static final int[][] MAN_DIRECTIONS_PLAYER2 = {{-1, -1}, {-1, 1}};
    private static final int[][] KING_DIRECTIONS = {{1, -1}, {1, 1}, {-1, -1}, {-1, 1}};

    @Override
    public String initialBoardState() {
        return CheckersBoard.initial().toJson();
    }

    @Override
    public boolean isLegalMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        CheckersBoard board = CheckersBoard.fromJson(boardStateJson);
        for (Move legal : legalMoves(board, isPlayer1Turn)) {
            if (legal.getPath().equals(move.getPath())) {
                return true;
            }
        }
        return false;
    }

    private List<Move> legalMoves(CheckersBoard board, boolean isPlayer1Turn) {
        List<Move> captures = new ArrayList<>();
        List<Move> steps = new ArrayList<>();
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                Square from = new Square(row, col);
                char piece = board.get(from);
                if (piece == '.' || !CheckersBoard.ownedBy(piece, isPlayer1Turn)) {
                    continue;
                }
                int[][] directions = directionsFor(piece, isPlayer1Turn);
                for (int[] dir : directions) {
                    Square over = new Square(row + dir[0], col + dir[1]);
                    Square landing = new Square(row + 2 * dir[0], col + 2 * dir[1]);
                    if (landing.isInBounds() && over.isInBounds()
                            && !board.isEmpty(over) && !CheckersBoard.ownedBy(board.get(over), isPlayer1Turn)
                            && board.isEmpty(landing)) {
                        captures.add(new Move(List.of(from, landing)));
                    }
                    Square to = new Square(row + dir[0], col + dir[1]);
                    if (to.isInBounds() && board.isEmpty(to)) {
                        steps.add(new Move(List.of(from, to)));
                    }
                }
            }
        }
        return captures.isEmpty() ? steps : captures;
    }

    private static int[][] directionsFor(char piece, boolean isPlayer1Turn) {
        if (CheckersBoard.isKing(piece)) {
            return KING_DIRECTIONS;
        }
        return isPlayer1Turn ? MAN_DIRECTIONS_PLAYER1 : MAN_DIRECTIONS_PLAYER2;
    }

    @Override
    public String applyMove(String boardStateJson, boolean isPlayer1Turn, Move move) {
        throw new UnsupportedOperationException("applyMove not implemented yet -- see game-engine-implementation.md Task 7");
    }

    @Override
    public GameResult checkWinner(String boardStateJson, boolean isPlayer1ToMoveNext) {
        throw new UnsupportedOperationException("checkWinner not implemented yet -- see game-engine-implementation.md Task 8");
    }
}
