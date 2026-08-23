package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import com.matchmaker.server.dao.InMemoryUserDao;
import com.matchmaker.server.dao.UserRecord;
import com.matchmaker.server.game.GameEngineRegistry;
import com.matchmaker.server.jms.InMemoryGameEventPublisher;
import com.matchmaker.server.matchmaking.InMemoryMatchmakingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdminServiceImplTest {

    private SessionManager sessionManager;
    private InMemoryUserDao userDao;
    private InMemoryGameTypeDao gameTypeDao;
    private InMemoryGameSessionDao gameSessionDao;
    private InMemoryGameEventPublisher gameEventPublisher;
    private InMemoryMatchmakingQueue matchmakingQueue;
    private AdminServiceImpl adminService;
    private String adminToken;
    private int adminUserId;
    private String playerToken;
    private int playerUserId;

    @BeforeEach
    void createAdminService() throws Exception {
        sessionManager = new SessionManager();
        userDao = new InMemoryUserDao();
        gameTypeDao = new InMemoryGameTypeDao();
        gameSessionDao = new InMemoryGameSessionDao();
        gameEventPublisher = new InMemoryGameEventPublisher();
        matchmakingQueue = new InMemoryMatchmakingQueue();
        adminService = new AdminServiceImpl(sessionManager, userDao, gameTypeDao, gameSessionDao,
                gameEventPublisher, matchmakingQueue, GameEngineRegistry.standard(), Duration.ofSeconds(60));

        Optional<UserRecord> admin = userDao.insert("admin", "hash");
        userDao.markAdmin(admin.get().id());
        adminUserId = admin.get().id();
        adminToken = sessionManager.createSession(adminUserId);

        Optional<UserRecord> player = userDao.insert("player", "hash");
        playerUserId = player.get().id();
        playerToken = sessionManager.createSession(playerUserId);
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
                new GameTypeDTO(0, "Checkers", "Classic checkers", 2, 2, 8, 8));

        assertTrue(created.getId() > 0);
        assertEquals(1, gameTypeDao.findAll().size());
    }

    @Test
    void addGameType_asNonAdmin_throwsNotAdminException() {
        GameTypeDTO newGameType = new GameTypeDTO(0, "Checkers", "Classic checkers", 2, 2, 8, 8);
        assertThrows(NotAdminException.class, () -> adminService.addGameType(playerToken, newGameType));
    }

    @Test
    void addGameType_unknownEngine_throwsIllegalArgumentException() {
        GameTypeDTO newGameType = new GameTypeDTO(0, "Battleship", "Naval combat", 2, 2, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> adminService.addGameType(adminToken, newGameType));
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
    void createUser_asAdmin_insertsAndReturnsCreated() throws Exception {
        // A 6-char password: clears the player floor but not the 12-char admin floor, so this also
        // pins that regular accounts aren't held to the stricter admin requirement.
        UserDTO created = adminService.createUser(adminToken, "newplayer", "abcdef", false);

        assertEquals("newplayer", created.getUsername());
        assertFalse(created.isAdmin());
        assertTrue(userDao.findByUsername("newplayer").isPresent());
    }

    @Test
    void createUser_duplicateUsername_throwsUsernameTakenException() {
        assertThrows(UsernameTakenException.class,
                () -> adminService.createUser(adminToken, "player", "goodpassword", false));
    }

    @Test
    void createUser_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class,
                () -> adminService.createUser(playerToken, "newplayer", "goodpassword", false));
    }

    @Test
    void createUser_adminAccountWithPlayerLengthPassword_throwsInvalidRegistrationException() {
        // 8 chars clears the 6-char player floor but not the higher floor required for admins.
        assertThrows(InvalidRegistrationException.class,
                () -> adminService.createUser(adminToken, "newadmin", "8-chars!", true));
    }

    @Test
    void createUser_adminAccountWithLongEnoughPassword_succeedsAndIsMarkedAdmin() throws Exception {
        UserDTO created = adminService.createUser(adminToken, "newadmin", "a-genuinely-long-password", true);

        assertTrue(created.isAdmin());
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

    @Test
    void getDashboardStats_asAdmin_countsOnlinePlayersExcludingAdmins() throws Exception {
        Optional<UserRecord> otherPlayer = userDao.insert("player2", "hash");
        sessionManager.createSession(otherPlayer.get().id());

        AdminDashboardStatsDTO stats = adminService.getDashboardStats(adminToken);

        assertEquals(2, stats.getOnlinePlayers(), "admin session should not count as an online player");
    }

    @Test
    void getDashboardStats_asAdmin_reflectsActiveGamesGamesTodayAndQueueDepth() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));
        gameSessionDao.setGamesStartedTodayForTest(3);
        matchmakingQueue.join(playerUserId, 1, "board");

        AdminDashboardStatsDTO stats = adminService.getDashboardStats(adminToken);

        assertEquals(1, stats.getActiveGames());
        assertEquals(3, stats.getGamesToday());
        assertEquals(1, stats.getOpenInQueue());
    }

    @Test
    void getDashboardStats_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.getDashboardStats(playerToken));
    }

    @Test
    void listMoves_asAdmin_returnsWhatDaoReturns() throws Exception {
        gameSessionDao.addMove(new MoveDTO(1, playerUserId, 1, "{\"path\":[\"b3\",\"a4\"]}"));

        List<MoveDTO> moves = adminService.listMoves(adminToken, 1);

        assertEquals(1, moves.size());
        assertEquals("{\"path\":[\"b3\",\"a4\"]}", moves.get(0).getPayload());
    }

    @Test
    void listMoves_asNonAdmin_throwsNotAdminException() {
        assertThrows(NotAdminException.class, () -> adminService.listMoves(playerToken, 1));
    }
}
