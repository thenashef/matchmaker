package com.matchmaker.server.game;

import com.matchmaker.server.game.checkers.CheckersEngine;
import com.matchmaker.server.game.eights.EightsEngine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class GameEngineRegistry {

    private final Map<String, GameEngine> enginesByName = new HashMap<>();

    public GameEngineRegistry(Map<String, GameEngine> enginesByName) {
        enginesByName.forEach((name, engine) -> this.enginesByName.put(normalize(name), engine));
    }

    public static GameEngineRegistry standard() {
        return new GameEngineRegistry(Map.of(
                "Checkers", new CheckersEngine(),
                "Crazy Eights", new EightsEngine()));
    }

    public GameEngine forName(String name) {
        GameEngine engine = enginesByName.get(normalize(name));
        if (engine == null) {
            throw new IllegalArgumentException("No game engine registered for '" + name + "'");
        }
        return engine;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
