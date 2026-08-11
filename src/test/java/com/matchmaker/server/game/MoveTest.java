package com.matchmaker.server.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveTest {

    @Test
    void fromJson_parsesASimpleTwoSquarePath() {
        Move move = Move.fromJson("{\"path\":[\"b6\",\"c5\"]}");

        assertEquals(List.of(new Square(5, 1), new Square(4, 2)), move.getPath());
    }

    @Test
    void fromJson_parsesAMultiJumpPath() {
        Move move = Move.fromJson("{\"path\":[\"b6\",\"d4\",\"f2\"]}");

        assertEquals(
                List.of(new Square(5, 1), new Square(3, 3), new Square(1, 5)),
                move.getPath());
    }
}
