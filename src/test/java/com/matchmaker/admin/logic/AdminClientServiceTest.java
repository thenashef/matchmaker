package com.matchmaker.admin.logic;

import com.matchmaker.admin.communication.InMemoryAdminConnection;
import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AdminClientServiceTest {

    private InMemoryAdminConnection adminConnection;
    private AdminClientService service;

    @BeforeAll
    static void initJavaFxRuntime() throws InterruptedException {
        CountDownLatch warmupLatch = new CountDownLatch(1);
        try {
            Platform.startup(warmupLatch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(warmupLatch::countDown);
        }
        assertTrue(warmupLatch.await(10, TimeUnit.SECONDS), "JavaFX toolkit never became responsive");
    }

    @BeforeEach
    void setUp() {
        adminConnection = new InMemoryAdminConnection();
        service = new AdminClientService(adminConnection);
    }

    private <T> T await(Consumer<Consumer<T>> triggerWithCapture) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> captured = new AtomicReference<>();
        triggerWithCapture.accept(value -> { captured.set(value); latch.countDown(); });
        assertTrue(latch.await(2, TimeUnit.SECONDS), "callback never fired");
        return captured.get();
    }

    @Test
    void login_adminAccount_succeeds() throws Exception {
        UserDTO admin = new UserDTO(1, "admin", true, 0, 0, 0, 1200);
        adminConnection.setLoginResult(new LoginResultDTO(admin, "token-1"));

        UserDTO result = this.<UserDTO>await(capture ->
                service.login("admin", "pw", capture, () -> fail("should not be rejected"), err -> fail(String.valueOf(err))));

        assertEquals("admin", result.getUsername());
        assertEquals(1, service.getCurrentUser().getId());
    }

    @Test
    void login_startsPeriodicKeepAlivePings() throws Exception {
        AdminClientService fastKeepAliveService = new AdminClientService(adminConnection, Duration.ofMillis(20));
        UserDTO admin = new UserDTO(1, "admin", true, 0, 0, 0, 1200);
        adminConnection.setLoginResult(new LoginResultDTO(admin, "token-1"));

        this.<UserDTO>await(capture -> fastKeepAliveService.login(
                "admin", "pw", capture, () -> fail("should not be rejected"), err -> fail(String.valueOf(err))));

        long deadline = System.currentTimeMillis() + 2000;
        while (adminConnection.keepAliveCallCount() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        fastKeepAliveService.shutdown();
        assertTrue(adminConnection.keepAliveCallCount() >= 2, "expected at least 2 keepAlive pings");
        assertEquals("token-1", adminConnection.lastKeepAliveToken());
    }

    @Test
    void login_nonAdminAccount_invokesOnNotAdmin() throws Exception {
        UserDTO player = new UserDTO(2, "player", false, 0, 0, 0, 1200);
        adminConnection.setLoginResult(new LoginResultDTO(player, "token-2"));

        Boolean rejected = this.<Boolean>await(capture ->
                service.login("player", "pw", user -> fail("should not succeed"), () -> capture.accept(true),
                        err -> fail(String.valueOf(err))));

        assertTrue(rejected);
        assertEquals(List.of("token-2"), adminConnection.loggedOutTokens());
        assertFalse(adminConnection.isClosed());
    }

    @Test
    void login_failure_invokesOnError() throws Exception {
        adminConnection.setLoginFailure(new AuthenticationException("bad password"));

        Throwable error = this.<Throwable>await(capture ->
                service.login("admin", "wrong", user -> fail("should not succeed"), () -> fail("not this path"), capture));

        assertInstanceOf(AuthenticationException.class, error);
    }

    @Test
    void listGameTypes_success_returnsWhatConnectionReturns() throws Exception {
        adminConnection.setGameTypes(List.of(new GameTypeDTO(1, "Checkers", "desc", 2, 2, 8, 8)));

        List<GameTypeDTO> result = this.<List<GameTypeDTO>>await(capture ->
                service.listGameTypes(capture, err -> fail(String.valueOf(err))));

        assertEquals(1, result.size());
    }

    @Test
    void listGameTypes_notAdmin_invokesOnError() throws Exception {
        adminConnection.setNotAdminFailure(new NotAdminException("not an admin"));

        Throwable error = this.<Throwable>await(capture ->
                service.listGameTypes(result -> fail("should not succeed"), capture));

        assertInstanceOf(NotAdminException.class, error);
    }

    @Test
    void addGameType_success_returnsCreated() throws Exception {
        GameTypeDTO created = new GameTypeDTO(5, "Battleship", "desc", 2, 2, 10, 10);
        adminConnection.setAddGameTypeResult(created);

        GameTypeDTO result = this.<GameTypeDTO>await(capture ->
                service.addGameType(new GameTypeDTO(0, "Battleship", "desc", 2, 2, 10, 10),
                        capture, err -> fail(String.valueOf(err))));

        assertEquals(5, result.getId());
    }

    @Test
    void listUsers_success_returnsWhatConnectionReturns() throws Exception {
        adminConnection.setUsers(List.of(new UserDTO(1, "admin", true, 0, 0, 0, 1200)));

        List<UserDTO> result = this.<List<UserDTO>>await(capture ->
                service.listUsers(capture, err -> fail(String.valueOf(err))));

        assertEquals(1, result.size());
    }

    @Test
    void createUser_success_returnsCreatedAndForwardsArguments() throws Exception {
        adminConnection.setCreateUserResult(new UserDTO(9, "newplayer", false, 0, 0, 0, 1200));

        UserDTO result = this.<UserDTO>await(capture ->
                service.createUser("newplayer", "goodpassword", false, capture, err -> fail(String.valueOf(err))));

        assertEquals("newplayer", result.getUsername());
        assertEquals("newplayer", adminConnection.lastCreateUserUsername());
        assertFalse(adminConnection.lastCreateUserIsAdmin());
    }

    @Test
    void createUser_asAdmin_forwardsTheAdminFlag() throws Exception {
        adminConnection.setCreateUserResult(new UserDTO(10, "newadmin", true, 0, 0, 0, 1200));

        this.<UserDTO>await(capture -> service.createUser("newadmin", "a-genuinely-long-password", true,
                capture, err -> fail(String.valueOf(err))));

        assertTrue(adminConnection.lastCreateUserIsAdmin(), "the isAdmin flag must reach the connection layer");
    }

    @Test
    void createUser_usernameTaken_invokesOnError() throws Exception {
        adminConnection.setCreateUserUsernameTakenFailure(new UsernameTakenException("taken"));

        Throwable error = this.<Throwable>await(capture ->
                service.createUser("bob", "goodpassword", false, r -> fail("should not succeed"), capture));

        assertInstanceOf(UsernameTakenException.class, error);
    }

    @Test
    void listActiveSessions_success_returnsWhatConnectionReturns() throws Exception {
        adminConnection.setActiveSessions(List.of(
                new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board")));

        List<GameStateDTO> result = this.<List<GameStateDTO>>await(capture ->
                service.listActiveSessions(capture, err -> fail(String.valueOf(err))));

        assertEquals(1, result.size());
    }

    @Test
    void forceEndSession_success_callsOnSuccess() throws Exception {
        Boolean called = this.<Boolean>await(capture ->
                service.forceEndSession(1, () -> capture.accept(true), err -> fail(String.valueOf(err))));

        assertTrue(called);
        assertTrue(adminConnection.wasForceEndSessionCalled());
    }

    @Test
    void getDashboardStats_success_returnsWhatConnectionReturns() throws Exception {
        adminConnection.setDashboardStats(new AdminDashboardStatsDTO(4, 2, 9, 1));

        AdminDashboardStatsDTO result = this.<AdminDashboardStatsDTO>await(capture ->
                service.getDashboardStats(capture, err -> fail(String.valueOf(err))));

        assertEquals(4, result.getOnlinePlayers());
        assertEquals(2, result.getActiveGames());
        assertEquals(9, result.getGamesToday());
        assertEquals(1, result.getOpenInQueue());
    }

    @Test
    void listMoves_success_returnsWhatConnectionReturns() throws Exception {
        adminConnection.setMoves(List.of(new MoveDTO(1, 1, 1, "{\"path\":[\"b3\",\"a4\"]}")));

        List<MoveDTO> result = this.<List<MoveDTO>>await(capture ->
                service.listMoves(7, capture, err -> fail(String.valueOf(err))));

        assertEquals(1, result.size());
        assertEquals(7, adminConnection.lastListMovesSessionId());
    }

    @Test
    void monitorSession_subscribesAndReceivesPushedEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        service.monitorSession(7, event -> { captured.set(event.getGameState()); latch.countDown(); });

        assertTrue(adminConnection.isSubscribedToSessionTopic(7));

        GameStateDTO pushed = new GameStateDTO(7, 1, 1, 2, GameStatus.ACTIVE, 2, null, "board");
        adminConnection.fireSessionTopicEvent(7, new GameEventDTO(GameEventType.MOVE_MADE, 7, pushed));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, captured.get().getCurrentTurnUserId());
    }

    @Test
    void stopMonitoring_closesTheSubscription() {
        service.monitorSession(7, event -> { });

        service.stopMonitoring();

        assertFalse(adminConnection.isSubscribedToSessionTopic(7));
    }
}
