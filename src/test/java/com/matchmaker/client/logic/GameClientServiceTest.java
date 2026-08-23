package com.matchmaker.client.logic;

import com.matchmaker.client.communication.InMemoryServerConnection;
import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GameClientServiceTest {

    private InMemoryServerConnection serverConnection;
    private GameClientService service;

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
        serverConnection = new InMemoryServerConnection();
        service = new GameClientService(serverConnection);
    }

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
    void login_subscribeFailure_rollsBackSession() throws Exception {
        UserDTO user = new UserDTO(1, "alice", false, 0, 0, 0, 1000);
        serverConnection.setLoginResult(new LoginResultDTO(user, "token-123"));
        serverConnection.setSubscribeToPlayerQueueFailure(new RuntimeException("jms down"));

        Throwable error = await(capture -> service.login("alice", "pw", u -> fail("should not succeed"), capture));

        assertEquals("jms down", error.getMessage());
        assertNull(service.getCurrentUser());
        assertEquals(List.of("token-123"), serverConnection.loggedOutTokens());
        assertFalse(serverConnection.isSubscribedToPlayerQueue(1));
    }

    @Test
    void register_success_returnsUser() throws Exception {
        serverConnection.setRegisterResult(new UserDTO(2, "bob", false, 0, 0, 0, 1000));

        UserDTO result = await(capture -> service.register("bob", "pw", capture, err -> fail(String.valueOf(err))));

        assertEquals("bob", result.getUsername());
    }

    @Test
    void login_success_subscribesToPlayerQueueForTheSessionLifetime() throws Exception {
        loginAsUser(1);

        assertTrue(serverConnection.isSubscribedToPlayerQueue(1),
                "the player queue is login-lifetime -- MATCH_FOUND and REMATCH_CREATED can both "
                        + "arrive at any point while logged in, not just while actively queued");
    }

    @Test
    void joinQueue_immediateMatch_entersGameAndKeepsThePlayerQueueSubscriptionFromLogin() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);

        GameStateDTO result = await(capture ->
                service.joinQueue(1, capture, () -> fail("should not be waiting"), err -> fail(String.valueOf(err))));

        assertEquals(5, result.getSessionId());
        assertTrue(serverConnection.isSubscribedToPlayerQueue(1), "the login-lifetime subscription must stay open");
        assertTrue(serverConnection.isSubscribedToSessionTopic(5), "must subscribe to the session topic before returning control");
    }

    @Test
    void joinQueue_noOpponentYet_waitsWithoutClosingThePlayerQueueSubscription() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);

        Boolean waited = await(capture ->
                service.joinQueue(1, matched -> fail("should not be matched yet"), () -> capture.accept(true), err -> fail(String.valueOf(err))));

        assertTrue(waited);
        assertTrue(serverConnection.isSubscribedToPlayerQueue(1));
    }

    @Test
    void joinQueue_deferredMatchFoundPush_invokesOriginalOnMatchedAndKeepsPlayerQueueSubscription() throws Exception {
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
        assertTrue(serverConnection.isSubscribedToPlayerQueue(1), "the queue subscription is login-lifetime now");
        assertTrue(serverConnection.isSubscribedToSessionTopic(9));
    }

    @Test
    void cancelQueue_clearsPendingMatchCallbackSoALateMatchFoundIsIgnored() throws Exception {
        loginAsUser(1);
        serverConnection.setJoinQueueResult(null);
        await(capture -> service.joinQueue(1, m -> fail("should have been cancelled"), () -> capture.accept(true), err -> fail(String.valueOf(err))));

        await(capture -> service.cancelQueue(() -> capture.accept(true), err -> fail(String.valueOf(err))));

        // A stale MATCH_FOUND arriving after cancellation must not resurrect the old callback.
        serverConnection.firePlayerQueueEvent(1, new GameEventDTO(GameEventType.MATCH_FOUND, 9,
                new GameStateDTO(9, 1, 1, 2, GameStatus.ACTIVE, 1, null, "{}")));
        Thread.sleep(100);

        assertTrue(serverConnection.wasCancelQueueCalled());
        assertTrue(serverConnection.isSubscribedToPlayerQueue(1), "the subscription itself is login-lifetime");
    }

    @Test
    void shutdown_closesThePlayerQueueSubscription() throws Exception {
        loginAsUser(1);

        service.shutdown();

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
    void legalContinuations_success_reachesOnSuccessWithWhatConnectionReturns() throws Exception {
        loginAsUser(1);
        serverConnection.setLegalContinuationsResult(List.of("{\"path\":[\"b6\"]}", "{\"path\":[\"c5\"]}"));

        List<String> result = await(capture ->
                service.legalContinuations(5, "{\"path\":[]}", capture, err -> fail(String.valueOf(err))));

        assertEquals(List.of("{\"path\":[\"b6\"]}", "{\"path\":[\"c5\"]}"), result);
    }

    @Test
    void legalContinuations_failure_invokesOnError() throws Exception {
        loginAsUser(1);
        serverConnection.setLegalContinuationsFailure(new NotYourTurnException("not your turn"));

        Throwable error = await(capture -> service.legalContinuations(
                5, "{\"path\":[]}", r -> fail("should not succeed"), capture));

        assertInstanceOf(NotYourTurnException.class, error);
    }

    @Test
    void resign_success_reachesOnSuccessWithTheAuthoritativeState() throws Exception {
        loginAsUser(1);
        GameStateDTO finalState = new GameStateDTO(5, 1, 1, 2, GameStatus.ABANDONED, null, 2, "{\"pieces\":{}}");
        serverConnection.setResignResult(finalState);

        GameStateDTO result = await(capture ->
                service.resign(5, capture, err -> fail(String.valueOf(err))));

        assertEquals(GameStatus.ABANDONED, result.getStatus());
        assertEquals(Integer.valueOf(2), result.getWinnerId());
        assertTrue(serverConnection.wasResignCalled());
        assertEquals(5, serverConnection.lastResignSessionId());
    }

    @Test
    void resign_failure_invokesOnError() throws Exception {
        loginAsUser(1);
        serverConnection.setResignFailure(new NotParticipantException("not yours"));

        Throwable error = await(capture -> service.resign(5, r -> fail("should not succeed"), capture));

        assertInstanceOf(NotParticipantException.class, error);
    }

    @Test
    void sendChatMessage_success_reachesOnSuccess() throws Exception {
        loginAsUser(1);

        Boolean succeeded = await(capture ->
                service.sendChatMessage(5, "hello", () -> capture.accept(true), err -> fail(String.valueOf(err))));

        assertTrue(succeeded);
        assertEquals(List.of("hello"), serverConnection.sentChatMessages());
    }

    @Test
    void getChatHistory_success_returnsWhatConnectionReturns() throws Exception {
        loginAsUser(1);
        serverConnection.setChatHistoryResult(
                List.of(new ChatMessageDTO(5, 1, "hi", java.time.LocalDateTime.now())));

        List<ChatMessageDTO> result = await(capture ->
                service.getChatHistory(5, capture, err -> fail(String.valueOf(err))));

        assertEquals(1, result.size());
        assertEquals("hi", result.get(0).getContent());
    }

    @Test
    void rematch_success_entersTheNewGameAndReachesOnSuccess() throws Exception {
        loginAsUser(1);
        GameStateDTO newSession = new GameStateDTO(9, 1, 2, 1, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.setRematchResult(newSession);

        GameStateDTO result = await(capture ->
                service.rematch(5, capture, err -> fail(String.valueOf(err))));

        assertEquals(9, result.getSessionId());
        assertEquals(5, serverConnection.lastRematchFinishedSessionId());
        assertTrue(serverConnection.isSubscribedToSessionTopic(9), "rematch must enter the new game like a match");
    }

    @Test
    void rematch_opponentUnavailable_invokesOnErrorWithAlreadyInGameException() throws Exception {
        loginAsUser(1);
        serverConnection.setRematchAlreadyInGameFailure(new com.matchmaker.common.exceptions.AlreadyInGameException("gone"));

        Throwable error = await(capture -> service.rematch(5, r -> fail("should not succeed"), capture));

        assertInstanceOf(com.matchmaker.common.exceptions.AlreadyInGameException.class, error);
    }

    @Test
    void listLeaderboard_success_returnsWhatConnectionReturns() throws Exception {
        loginAsUser(1);
        List<UserDTO> board = List.of(
                new UserDTO(2, "bob", false, 4, 1, 0, 1300),
                new UserDTO(1, "user1", false, 0, 0, 0, 1000));
        serverConnection.setLeaderboard(board);

        List<UserDTO> result = await(capture ->
                service.listLeaderboard(capture, err -> fail(String.valueOf(err))));

        assertEquals(2, result.size());
        assertEquals("bob", result.get(0).getUsername());
        assertEquals(1300, result.get(0).getRating());
    }

    @Test
    void getProfile_success_updatesCachedCurrentUserAndReachesOnSuccess() throws Exception {
        loginAsUser(1);
        UserDTO refreshed = new UserDTO(1, "user1", false, 5, 1, 0, 1240);
        serverConnection.setProfileResult(refreshed);

        UserDTO result = await(capture -> service.getProfile(capture, err -> fail(String.valueOf(err))));

        assertEquals(1240, result.getRating());
        assertEquals(1240, service.getCurrentUser().getRating(), "getProfile must refresh the cached currentUser");
    }

    @Test
    void getOpponentProfile_success_returnsWhatConnectionReturns() throws Exception {
        loginAsUser(1);
        serverConnection.setOpponentProfileResult(new UserDTO(2, "opponent", false, 3, 2, 0, 1180));

        UserDTO result = await(capture -> service.getOpponentProfile(5, capture, err -> fail(String.valueOf(err))));

        assertEquals("opponent", result.getUsername());
    }

    @Test
    void onPlayerQueueEvent_pushedRematchCreated_entersGameAndReachesTheAttachedRematchListener() throws Exception {
        loginAsUser(1);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<GameStateDTO> captured = new AtomicReference<>();
        service.attachRematchListener(state -> { captured.set(state); latch.countDown(); });

        GameStateDTO newSession = new GameStateDTO(11, 1, 2, 1, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.firePlayerQueueEvent(1, new GameEventDTO(GameEventType.REMATCH_CREATED, 11, newSession));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(11, captured.get().getSessionId());
        assertTrue(serverConnection.isSubscribedToSessionTopic(11));
        assertEquals(1, serverConnection.sessionTopicSubscribeCallCount(11));
    }

    @Test
    void onPlayerQueueEvent_duplicateRematchCreatedForTheSameSession_doesNotRebuildTheLiveSubscription()
            throws Exception {
        // Both players clicking Rematch converges on one session server-side, so this same event can
        // reach a client twice: once as its own direct RMI response (via enterGame in rematch()) and
        // once more as the push meant for the other player.
        loginAsUser(1);
        GameStateDTO newSession = new GameStateDTO(11, 1, 2, 1, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");

        CountDownLatch firstLatch = new CountDownLatch(1);
        service.attachRematchListener(state -> firstLatch.countDown());
        serverConnection.firePlayerQueueEvent(1, new GameEventDTO(GameEventType.REMATCH_CREATED, 11, newSession));
        assertTrue(firstLatch.await(2, TimeUnit.SECONDS));

        CountDownLatch secondLatch = new CountDownLatch(1);
        service.attachRematchListener(state -> secondLatch.countDown());
        serverConnection.firePlayerQueueEvent(1, new GameEventDTO(GameEventType.REMATCH_CREATED, 11, newSession));
        assertTrue(secondLatch.await(2, TimeUnit.SECONDS), "downstream listeners still fire -- they guard their own re-entry");

        assertEquals(1, serverConnection.sessionTopicSubscribeCallCount(11),
                "the second arrival must not tear down and rebuild the already-live subscription");
    }

    @Test
    void enterGame_pushedChatMessageEvent_reachesTheAttachedChatListenerWithoutTouchingGameState() throws Exception {
        loginAsUser(1);
        GameStateDTO matched = new GameStateDTO(5, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        serverConnection.setJoinQueueResult(matched);
        this.<GameStateDTO>await(capture -> service.joinQueue(1, capture, () -> fail("immediate"), err -> fail(String.valueOf(err))));

        CountDownLatch gameUpdateLatch = new CountDownLatch(1);
        service.attachGameUpdateListener(state -> gameUpdateLatch.countDown());

        CountDownLatch chatLatch = new CountDownLatch(1);
        AtomicReference<ChatMessageDTO> captured = new AtomicReference<>();
        service.attachChatListener(message -> { captured.set(message); chatLatch.countDown(); });

        serverConnection.fireSessionTopicEvent(5, new GameEventDTO(GameEventType.CHAT_MESSAGE, 5, 2, "gl hf"));

        assertTrue(chatLatch.await(2, TimeUnit.SECONDS));
        assertEquals(2, captured.get().getUserId());
        assertEquals("gl hf", captured.get().getContent());
        assertFalse(gameUpdateLatch.await(200, TimeUnit.MILLISECONDS),
                "a chat event must not also notify the game-update listener");
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

    @Test
    void login_startsPeriodicKeepAlivePings() throws Exception {
        GameClientService fastKeepAliveService = new GameClientService(serverConnection, Duration.ofMillis(20));
        UserDTO user = new UserDTO(1, "user1", false, 0, 0, 0, 1000);
        serverConnection.setLoginResult(new LoginResultDTO(user, "token-1"));

        this.<UserDTO>await(capture ->
                fastKeepAliveService.login("user1", "pw", capture, err -> fail(String.valueOf(err))));

        long deadline = System.currentTimeMillis() + 2000;
        while (serverConnection.keepAliveCallCount() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        fastKeepAliveService.shutdown();
        assertTrue(serverConnection.keepAliveCallCount() >= 2, "expected at least 2 keepAlive pings");
        assertEquals("token-1", serverConnection.lastKeepAliveToken());
    }

    private void loginAsUser(int userId) throws InterruptedException {
        UserDTO user = new UserDTO(userId, "user" + userId, false, 0, 0, 0, 1000);
        serverConnection.setLoginResult(new LoginResultDTO(user, "token-" + userId));
        this.<UserDTO>await(capture -> service.login("user" + userId, "pw", capture, err -> fail(String.valueOf(err))));
    }
}
