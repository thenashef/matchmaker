package com.matchmaker.server.game.checkers;

import com.matchmaker.server.game.GameEngine;
import com.matchmaker.server.game.GameResult;

import java.util.ArrayList;
import java.util.List;

public class CheckersEngine implements GameEngine {

    private static final int[][] MAN_DIRECTIONS_PLAYER1 = {{1, -1}, {1, 1}};
    private static final int[][] MAN_DIRECTIONS_PLAYER2 = {{-1, -1}, {-1, 1}};
    private static final int[][] KING_DIRECTIONS = {{1, -1}, {1, 1}, {-1, -1}, {-1, 1}};

    @Override
    public String initialState() {
        return CheckersBoard.initial().toJson();
    }

    @Override
    public boolean isLegalMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson) {
        Move move;
        try {
            move = Move.fromJson(movePayloadJson);
        } catch (RuntimeException e) {
            return false;
        }
        CheckersBoard board = CheckersBoard.fromJson(stateJson);
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
                findCaptureChains(board, from, piece, isPlayer1Turn, List.of(from), captures);
                if (captures.isEmpty()) {
                    for (int[] dir : directionsFor(piece, isPlayer1Turn)) {
                        Square to = new Square(row + dir[0], col + dir[1]);
                        if (to.isInBounds() && board.isEmpty(to)) {
                            steps.add(new Move(List.of(from, to)));
                        }
                    }
                }
            }
        }
        return captures.isEmpty() ? steps : captures;
    }

    private void findCaptureChains(CheckersBoard board, Square current, char piece, boolean isPlayer1Turn,
                                    List<Square> pathSoFar, List<Move> results) {
        boolean foundFurtherJump = false;
        for (int[] dir : directionsFor(piece, isPlayer1Turn)) {
            Square over = new Square(current.row() + dir[0], current.col() + dir[1]);
            Square landing = new Square(current.row() + 2 * dir[0], current.col() + 2 * dir[1]);
            if (!landing.isInBounds() || !over.isInBounds()) {
                continue;
            }
            char overPiece = board.get(over);
            if (overPiece == '.' || CheckersBoard.ownedBy(overPiece, isPlayer1Turn) || !board.isEmpty(landing)) {
                continue;
            }

            foundFurtherJump = true;
            CheckersBoard scratch = board.copy();
            scratch.set(current, '.');
            scratch.set(over, '.');
            boolean promotes = promotesAt(piece, landing);
            char pieceAfterJump = promotes ? promotedForm(piece) : piece;
            scratch.set(landing, pieceAfterJump);

            List<Square> extendedPath = new ArrayList<>(pathSoFar);
            extendedPath.add(landing);

            if (promotes) {
                results.add(new Move(extendedPath));
            } else {
                findCaptureChains(scratch, landing, pieceAfterJump, isPlayer1Turn, extendedPath, results);
            }
        }
        if (!foundFurtherJump && pathSoFar.size() > 1) {
            results.add(new Move(pathSoFar));
        }
    }

    private static boolean promotesAt(char piece, Square square) {
        if (CheckersBoard.isKing(piece)) {
            return false;
        }
        return (piece == 'b' && square.row() == 7) || (piece == 'w' && square.row() == 0);
    }

    private static char promotedForm(char piece) {
        return piece == 'b' ? 'B' : 'W';
    }

    private static int[][] directionsFor(char piece, boolean isPlayer1Turn) {
        if (CheckersBoard.isKing(piece)) {
            return KING_DIRECTIONS;
        }
        return isPlayer1Turn ? MAN_DIRECTIONS_PLAYER1 : MAN_DIRECTIONS_PLAYER2;
    }

    @Override
    public String applyMove(String stateJson, boolean isPlayer1Turn, String movePayloadJson) {
        Move move = Move.fromJson(movePayloadJson);
        List<Square> path = move.getPath();
        if (path.size() < 2) {
            throw new IllegalArgumentException("A move must have at least a from and a to square, got: " + path);
        }
        CheckersBoard board = CheckersBoard.fromJson(stateJson);
        Square from = path.get(0);
        if (board.isEmpty(from)) {
            throw new IllegalStateException("applyMove called with no piece on origin square " + from.toAlgebraic()
                    + " -- the move should have been validated with isLegalMove() first");
        }
        char piece = board.get(from);
        board.set(from, '.');

        for (int i = 1; i < path.size(); i++) {
            Square prev = path.get(i - 1);
            Square current = path.get(i);
            boolean isCapture = Math.abs(current.row() - prev.row()) == 2;
            if (isCapture) {
                Square captured = new Square((prev.row() + current.row()) / 2, (prev.col() + current.col()) / 2);
                board.set(captured, '.');
            }
        }

        Square finalSquare = path.get(path.size() - 1);
        if (promotesAt(piece, finalSquare)) {
            piece = promotedForm(piece);
        }
        board.set(finalSquare, piece);

        return board.toJson();
    }

    @Override
    public GameResult checkWinner(String stateJson, boolean isPlayer1ToMoveNext) {
        CheckersBoard board = CheckersBoard.fromJson(stateJson);
        boolean hasPieces = false;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                char piece = board.get(new Square(row, col));
                if (piece != '.' && CheckersBoard.ownedBy(piece, isPlayer1ToMoveNext)) {
                    hasPieces = true;
                }
            }
        }
        if (!hasPieces || legalMoves(board, isPlayer1ToMoveNext).isEmpty()) {
            return isPlayer1ToMoveNext ? GameResult.PLAYER2_WINS : GameResult.PLAYER1_WINS;
        }
        return GameResult.CONTINUE;
    }
}
