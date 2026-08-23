package com.matchmaker.server.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameEngineRegistryTest {

    @Test
    void forName_null_throwsIllegalArgumentException() {
        GameEngineRegistry registry = GameEngineRegistry.standard();

        assertThrows(IllegalArgumentException.class, () -> registry.forName(null));
    }

    @Test
    void isRegistered_null_isFalse() {
        assertFalse(GameEngineRegistry.standard().isRegistered(null));
    }
}
