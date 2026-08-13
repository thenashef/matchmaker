package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import com.matchmaker.server.dao.InMemoryUserDao;
import com.matchmaker.server.dao.UserRecord;
import com.matchmaker.server.jms.InMemoryGameEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdminServiceImplTest {

    private SessionManager sessionManager;
    private InMemoryUserDao userDao;
    private InMemoryGameTypeDao gameTypeDao;
    private InMemoryGameSessionDao gameSessionDao;
    private InMemoryGameEventPublisher gameEventPublisher;
    private AdminServiceImpl adminService;
    private String adminToken;
    private String playerToken;

    @BeforeEach
    void createAdminService() throws Exception {
        sessionManager = new SessionManager();
        userDao = new InMemoryUserDao();
        gameTypeDao = new InMemoryGameTypeDao();
        gameSessionDao = new InMemoryGameSessionDao();
        gameEventPublisher = new InMemoryGameEventPublisher();
        adminService = new AdminServiceImpl(sessionManager, userDao, gameTypeDao, gameSessionDao, gameEventPublisher);

        Optional<UserRecord> admin = userDao.insert("admin", "hash");
        userDao.markAdmin(admin.get().id());
        adminToken = sessionManager.createSession(admin.get().id());

        Optional<UserRecord> player = userDao.insert("player", "hash");
        playerToken = sessionManager.createSession(player.get().id());
    }

    @AfterEach
    void unexportAdminService() {
        if (adminService != null) {
            try { UnicastRemoteObject.unexportObject(adminService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void listGameTypes_asAdmin_returnsWhatDaoReturns() throws Exception {
        gameTypeDao.add(new GameTypeDTO(1, "Checkers", "desc", 2, 2, 8, 8));

        List<GameTypeDTO> result = adminService.listGameTypes(adminToken);

        assertEquals(1, result.size());
    }

    @Test
    void listGameTypes_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.listGameTypes(playerToken));
    }

    @Test
    void addGameType_asAdmin_insertsAndReturnsCreated() throws Exception {
        GameTypeDTO created = adminService.addGameType(adminToken,
                new GameTypeDTO(0, "Battleship", "Naval combat", 2, 2, 10, 10));

        assertTrue(created.getId() > 0);
        assertEquals(1, gameTypeDao.findAll().size());
    }

    @Test
    void addGameType_asNonAdmin_throwsNotAdminException() {
        GameTypeDTO newGameType = new GameTypeDTO(0, "Battleship", "Naval combat", 2, 2, 10, 10);
        assertThrows(NotAdminException.class, () -> adminService.addGameType(playerToken, newGameType));
    }

    @Test
    void listUsers_asAdmin_returnsEveryUser() throws Exception {
        List<UserDTO> result = adminService.listUsers(adminToken);

        assertEquals(2, result.size());
    }

    @Test
    void listUsers_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.listUsers(playerToken));
    }

    @Test
    void listActiveSessions_asAdmin_returnsOnlyActiveOnes() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));
        gameSessionDao.addFinishedSession(new GameStateDTO(2, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

        List<GameStateDTO> result = adminService.listActiveSessions(adminToken);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getSessionId());
    }

    @Test
    void listActiveSessions_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.listActiveSessions(playerToken));
    }

    @Test
    void forceEndSession_asAdmin_endsIt() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));

        adminService.forceEndSession(adminToken, 1);

        assertTrue(gameSessionDao.findActiveById(1).isEmpty());
    }

    @Test
    void forceEndSession_asAdmin_publishesSessionForceEndedEvent() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));

        adminService.forceEndSession(adminToken, 1);

        assertEquals(1, gameEventPublisher.publishedToSessions().size());
        assertEquals(GameEventType.SESSION_FORCE_ENDED,
                gameEventPublisher.publishedToSessions().get(0).event().getType());
    }

    @Test
    void forceEndSession_asAdmin_alreadyFinishedSession_doesNotPublish() throws Exception {
        gameSessionDao.addFinishedSession(new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

        adminService.forceEndSession(adminToken, 1);

        assertEquals(0, gameEventPublisher.publishedToSessions().size());
    }

    @Test
    void forceEndSession_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.forceEndSession(playerToken, 1));
    }
}
