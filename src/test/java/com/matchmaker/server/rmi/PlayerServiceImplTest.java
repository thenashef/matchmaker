package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerServiceImplTest {

    private InMemoryGameSessionDao gameSessionDao;
    private InMemoryGameTypeDao gameTypeDao;
    private PlayerServiceImpl playerService;
    private String sessionToken;

    @BeforeEach
    void createPlayerService() throws Exception {
        SessionManager sessionManager = new SessionManager();
        gameSessionDao = new InMemoryGameSessionDao();
        gameTypeDao = new InMemoryGameTypeDao();
        playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao);
        sessionToken = sessionManager.createSession(1);
    }

    @AfterEach
    void unexportPlayerService() {
        if (playerService != null) {
            try { UnicastRemoteObject.unexportObject(playerService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void listGameTypes_returnsWhatDaoReturns() throws Exception {
        gameTypeDao.add(new GameTypeDTO(1, "Checkers", "desc", 2, 2, 8, 8));

        List<GameTypeDTO> result = playerService.listGameTypes(sessionToken);

        assertEquals(1, result.size());
        assertEquals("Checkers", result.get(0).getName());
    }

    @Test
    void listGameTypes_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.listGameTypes("bogus-token"));
    }

    @Test
    void getHistory_returnsFinishedSessionsForCaller() throws Exception {
        GameStateDTO finished = new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board");
        gameSessionDao.addFinishedSession(finished);

        List<GameStateDTO> history = playerService.getHistory(sessionToken);

        assertEquals(1, history.size());
        assertEquals(1, history.get(0).getSessionId());
    }

    @Test
    void getHistory_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.getHistory("bogus-token"));
    }

    @Test
    void remainingMethods_stillThrowUnsupportedOperationException() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> playerService.joinQueue(sessionToken, 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.cancelQueue(sessionToken));
        assertThrows(UnsupportedOperationException.class, () -> playerService.makeMove(sessionToken, 1, "{}"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.sendChatMessage(sessionToken, 1, "hi"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.resign(sessionToken, 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.rematch(sessionToken, 1));
    }
}
