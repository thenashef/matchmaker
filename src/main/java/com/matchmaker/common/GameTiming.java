package com.matchmaker.common;

import java.time.Duration;

public final class GameTiming {

    public static final int TURN_SECONDS = 60;
    public static final Duration TURN_TIMEOUT = Duration.ofSeconds(TURN_SECONDS);

    private GameTiming() {
    }
}
