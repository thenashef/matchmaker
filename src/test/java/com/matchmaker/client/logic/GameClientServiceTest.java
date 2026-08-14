package com.matchmaker.client.logic;

import com.matchmaker.client.communication.InMemoryServerConnection;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GameClientServiceTest {

    private InMemoryServerConnection serverConnection;
    private GameClientService service;

    @BeforeAll
    static void initJavaFxRuntime() throws InterruptedException {
        // Platform.startup()'s first real runLater round-trip pays a one-time cost (native
        // library loading) that can take longer than an individual test's 2-second budget --
        // absorb that cost here, once, rather than risking it inside whichever test runs first.
        CountDownLatch warmupLatch = new CountDownLatch(1);
        try {
            Platform.startup(warmupLatch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            // Another test class in this JVM already initialized the toolkit -- still confirm
            // the FX thread is responsive before proceeding.
            Platform.runLater(warmupLatch::countDown);
        }
        assertTrue(warmupLatch.await(10, TimeUnit.SECONDS), "JavaFX toolkit never became responsive");
    }

    @BeforeEach
    void setUp() {
        serverConnection = new InMemoryServerConnection();
        service = new GameClientService(serverConnection);
    }

    /** Every GameClientService callback fires via Platform.runLater -- block until it does. */
    private <T> T await(java.util.function.Consumer<java.util.function.Consumer<T>> triggerWithCapture) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> captured = new AtomicReference<>();
        triggerWithCapture.accept(value -> { captured.set(value); latch.countDown(); });
        assertTrue(latch.await(2, TimeUnit.SECONDS), "callback never fired");
        return captured.get();
    }

    @Test
    void login_success_storesSessionAndReturnsUser() throws Exception {
        UserDTO user = new UserDTO(1, "alice", false, 0, 0, 0, 1000);
        serverConnection.setLoginResult(new LoginResultDTO(user, "token-123"));

        UserDTO result = await(capture -> service.login("alice", "pw", capture, err -> fail(String.valueOf(err))));

        assertEquals("alice", result.getUsername());
        assertEquals(user.getId(), service.getCurrentUser().getId());
    }

    @Test
    void login_failure_invokesOnError() throws Exception {
        serverConnection.setLoginFailure(new AuthenticationException("bad password"));

        Throwable error = await(capture -> service.login("alice", "wrong", user -> fail("should not succeed"), capture));

        assertInstanceOf(AuthenticationException.class, error);
    }

    @Test
    void register_success_returnsUser() throws Exception {
        serverConnection.setRegisterResult(new UserDTO(2, "bob", false, 0, 0, 0, 1000));

        UserDTO result = await(capture -> service.register("bob", "pw", capture, err -> fail(String.valueOf(err))));

        assertEquals("bob", result.getUsername());
    }

    @Test
    void joinQueue_immediateMatch_entersGameWithoutSubscribingToPlayerQueue() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);

        GameStateDTO result = await(capture ->
                service.joinQueue(1, capture, () -> fail("should not be waiting"), err -> fail(String.valueOf(err))));

        assertEquals(5, result.getSessionId());
        assertFalse(serverConnection.isSubscribedToPlayerQueue(1), "matched immediately -- no need to wait on the player queue");
        assertTrue(serverConnection.isSubscribedToSessionTopic(5), "must subscribe to the session topic before returning control");
    }

    @Test
    void joinQueue_noOpponentYet_subscribesToPlayerQueueAndWaits() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);

        Boolean waited = await(capture ->
                service.joinQueue(1, matched -> fail("should not be matched yet"), () -> capture.accept(true), err -> fail(String.valueOf(err))));

        assertTrue(waited);
        assertTrue(serverConnection.isSubscribedToPlayerQueue(1));
    }

    @Test
    void joinQueue_deferredMatchFoundPush_invokesOriginalOnMatchedAndSwitchesSubscriptions() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);

        CountDownLatch matchedLatch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        CountDownLatch waitingLatch = new CountDownLatch(1);
        service.joinQueue(1,
                matched -> { captured.set(matched); matchedLatch.countDown(); },
                waitingLatch::countDown,
                err -> fail(String.valueOf(err)));
        assertTrue(waitingLatch.await(2, TimeUnit.SECONDS));

        GameStateDTO matched = new GameStateDTO(9, 1, 2, 1, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        serverConnection.firePlayerQueueEvent(1, new GameEventDTO(GameEventType.MATCH_FOUND, 9, matched));

        assertTrue(matchedLatch.await(2, TimeUnit.SECONDS));
        assertEquals(9, captured.get().getSessionId());
        assertFalse(serverConnection.isSubscribedToPlayerQueue(1), "no longer waiting once matched");
        assertTrue(serverConnection.isSubscribedToSessionTopic(9));
    }

    @Test
    void cancelQueue_closesPlayerQueueSubscription() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);
        await(capture -> service.joinQueue(1, m -> fail("not yet"), () -> capture.accept(true), err -> fail(String.valueOf(err))));

        await(capture -> service.cancelQueue(() -> capture.accept(true), err -> fail(String.valueOf(err))));

        assertTrue(serverConnection.wasCancelQueueCalled());
        assertFalse(serverConnection.isSubscribedToPlayerQueue(1));
    }

    @Test
    void makeMove_success_updatesCurrentGameStateAndReachesOnSuccess() throws Exception {
        loginAsUser(1);
        GameStateDTO updated = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.setMakeMoveResult(updated);

        GameStateDTO result = await(capture ->
                service.makeMove(5, "{\"path\":[\"b3\",\"a4\"]}", capture, err -> fail(String.valueOf(err))));

        assertEquals(2, result.getCurrentTurnUserId());
        assertEquals(1, serverConnection.makeMoveCalls().size());
    }

    @Test
    void makeMove_failure_invokesOnError() throws Exception {
        loginAsUser(1);
        serverConnection.setMakeMoveFailure(new IllegalMoveException("nope"));

        Throwable error = await(capture ->
                service.makeMove(5, "{\"path\":[\"b3\",\"b5\"]}", r -> fail("should not succeed"), capture));

        assertInstanceOf(IllegalMoveException.class, error);
    }

    @Test
    void enterGame_pushedMoveMadeEvent_reachesTheAttachedGameUpdateListener() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);
        this.<GameStateDTO>await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(String.valueOf(err))));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        GameStateDTO latest = service.attachGameUpdateListener(state -> { captured.set(state); latch.countDown(); });
        assertEquals(5, latest.getSessionId(), "attaching should immediately replay the latest known state");

        GameStateDTO pushed = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.fireSessionTopicEvent(5, new GameEventDTO(GameEventType.MOVE_MADE, 5, pushed));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, captured.get().getCurrentTurnUserId());
    }

    @Test
    void enterGame_pushedSessionForceEndedEvent_alsoReachesTheAttachedGameUpdateListener() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);
        this.<GameStateDTO>await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(String.valueOf(err))));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        service.attachGameUpdateListener(state -> { captured.set(state); latch.countDown(); });

        GameStateDTO abandoned = new GameStateDTO(5, 1, 1, 2, GameStatus.ABANDONED, null, null, "{\"pieces\":{}}");
        serverConnection.fireSessionTopicEvent(5, new GameEventDTO(GameEventType.SESSION_FORCE_ENDED, 5, abandoned));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(GameStatus.ABANDONED, captured.get().getStatus());
    }

    @Test
    void enterGame_pushedSessionAbandonedEvent_alsoReachesTheAttachedGameUpdateListener() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);
        this.<GameStateDTO>await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(String.valueOf(err))));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        service.attachGameUpdateListener(state -> { captured.set(state); latch.countDown(); });

        GameStateDTO abandoned = new GameStateDTO(5, 1, 1, 2, GameStatus.ABANDONED, null, 2, "{\"pieces\":{}}");
        serverConnection.fireSessionTopicEvent(5, new GameEventDTO(GameEventType.SESSION_ABANDONED, 5, abandoned));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(GameStatus.ABANDONED, captured.get().getStatus());
        assertEquals(Integer.valueOf(2), captured.get().getWinnerId());
    }

    @Test
    void leaveGame_closesSessionTopicSubscriptionAndStopsUpdates() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);
        this.<GameStateDTO>await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(String.valueOf(err))));

        service.leaveGame();

        assertFalse(serverConnection.isSubscribedToSessionTopic(5));
    }

    private void loginAsUser(int userId) throws InterruptedException {
        UserDTO user = new UserDTO(userId, "user" + userId, false, 0, 0, 0, 1000);
        serverConnection.setLoginResult(new LoginResultDTO(user, "token-" + userId));
        this.<UserDTO>await(capture -> service.login("user" + userId, "pw", capture, err -> fail(String.valueOf(err))));
    }
}
