package com.matchmaker.server.rmi;

import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerServiceImplTest {

    @Test
    void allMethods_throwUnsupportedOperationException() throws Exception {
        PlayerServiceImpl playerService = new PlayerServiceImpl(new SessionManager());

        assertThrows(UnsupportedOperationException.class, () -> playerService.listGameTypes("token"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.joinQueue("token", 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.cancelQueue("token"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.makeMove("token", 1, "{}"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.sendChatMessage("token", 1, "hi"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.resign("token", 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.rematch("token", 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.getHistory("token"));
    }
}
