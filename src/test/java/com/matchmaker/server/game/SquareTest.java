package com.matchmaker.server.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquareTest {

    @Test
    void fromAlgebraic_parsesFileAndRankIntoRowAndCol() {
        assertEquals(new Square(0, 0), Square.fromAlgebraic("a1"));
        assertEquals(new Square(0, 1), Square.fromAlgebraic("b1"));
        assertEquals(new Square(5, 1), Square.fromAlgebraic("b6"));
        assertEquals(new Square(7, 7), Square.fromAlgebraic("h8"));
    }

    @Test
    void toAlgebraic_isTheInverseOfFromAlgebraic() {
        assertEquals("a1", new Square(0, 0).toAlgebraic());
        assertEquals("b6", new Square(5, 1).toAlgebraic());
        assertEquals("h8", new Square(7, 7).toAlgebraic());
    }

    @Test
    void isInBounds_trueForZeroToSeven_falseOutsideThatRange() {
        assertTrue(new Square(0, 0).isInBounds());
        assertTrue(new Square(7, 7).isInBounds());
        assertFalse(new Square(-1, 0).isInBounds());
        assertFalse(new Square(0, 8).isInBounds());
        assertFalse(new Square(8, 0).isInBounds());
    }
}
