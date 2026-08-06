package com.matchmaker.server.rmi;

import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerServiceImplTest {

    private PlayerServiceImpl playerService;

    @BeforeEach
    void createPlayerService() throws Exception {
        playerService = new PlayerServiceImpl(new SessionManager());
    }

    @AfterEach
    void unexportPlayerService() {
        if (playerService != null) {
            try { UnicastRemoteObject.unexportObject(playerService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void allMethods_throwUnsupportedOperationException() throws Exception {
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
