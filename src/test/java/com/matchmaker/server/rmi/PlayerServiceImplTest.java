package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameHistoryDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryChatMessageDao;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import com.matchmaker.server.dao.InMemoryUserDao;
import com.matchmaker.server.game.GameEngineRegistry;
import com.matchmaker.server.game.checkers.CheckersEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import com.matchmaker.server.jms.FailingGameEventPublisher;
import com.matchmaker.server.jms.InMemoryGameEventPublisher;
import com.matchmaker.server.matchmaking.InMemoryMatchmakingQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerServiceImplTest {

    private SessionManager sessionManager;
    private InMemoryGameSessionDao gameSessionDao;
    private InMemoryGameTypeDao gameTypeDao;
    private InMemoryMatchmakingQueue matchmakingQueue;
    private InMemoryGameEventPublisher gameEventPublisher;
    private InMemoryChatMessageDao chatMessageDao;
    private InMemoryUserDao userDao;
    private PlayerServiceImpl playerService;
    private String sessionToken;
    private String otherSessionToken;

    @BeforeEach
    void createPlayerService() throws Exception {
        sessionManager = new SessionManager();
        gameSessionDao = new InMemoryGameSessionDao();
        gameTypeDao = new InMemoryGameTypeDao();
        matchmakingQueue = new InMemoryMatchmakingQueue();
        gameEventPublisher = new InMemoryGameEventPublisher();
        chatMessageDao = new InMemoryChatMessageDao();
        userDao = new InMemoryUserDao();
        userDao.insert("player1", "hash"); // id 1
        userDao.insert("player2", "hash"); // id 2
        gameTypeDao.add(new GameTypeDTO(1, "Checkers", "desc", 2, 2, 8, 8));
        gameTypeDao.add(new GameTypeDTO(2, "Crazy Eights", "desc", 2, 2, 1, 1));
        playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue,
                gameEventPublisher, GameEngineRegistry.standard(), chatMessageDao, userDao);
        sessionToken = sessionManager.createSession(1);
        otherSessionToken = sessionManager.createSession(2);
    }

    @AfterEach
    void unexportPlayerService() {
        if (playerService != null) {
            try { UnicastRemoteObject.unexportObject(playerService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void listGameTypes_returnsWhatDaoReturns() throws Exception {
        List<GameTypeDTO> result = playerService.listGameTypes(sessionToken);

        assertEquals(2, result.size());
        assertEquals("Checkers", result.get(0).getName());
        assertEquals("Crazy Eights", result.get(1).getName());
    }

    @Test
    void listGameTypes_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.listGameTypes("bogus-token"));
    }

    @Test
    void getHistory_returnsFinishedSessionsForCaller() throws Exception {
        GameStateDTO finished = new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board");
        gameSessionDao.addFinishedSession(finished);

        List<GameHistoryDTO> history = playerService.getHistory(sessionToken);

        assertEquals(1, history.size());
        assertEquals(1, history.get(0).getSessionId());
        assertEquals("user-2", history.get(0).getOpponentUsername());
        assertEquals("game-type-1", history.get(0).getGameTypeName());
        assertEquals(GameStatus.FINISHED, history.get(0).getStatus());
        assertEquals(1, history.get(0).getWinnerId());
    }

    @Test
    void getHistory_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.getHistory("bogus-token"));
    }

    @Test
    void joinQueue_noOpponentWaiting_returnsNull() throws Exception {
        GameStateDTO result = playerService.joinQueue(sessionToken, 1);

        assertNull(result);
    }

    @Test
    void joinQueue_opponentWaiting_returnsMatchedSession() throws Exception {
        String otherToken = sessionManager.createSession(2);
        playerService.joinQueue(otherToken, 1);

        GameStateDTO result = playerService.joinQueue(sessionToken, 1);

        assertNotNull(result);
        assertEquals(GameStatus.ACTIVE, result.getStatus());
    }

    @Test
    void joinQueue_opponentWaiting_returnedStateHasARealBoardNotNull() throws Exception {
        String otherToken = sessionManager.createSession(2);
        playerService.joinQueue(otherToken, 1);

        GameStateDTO result = playerService.joinQueue(sessionToken, 1);

        assertEquals(new CheckersEngine().initialState(), result.getBoardState());
    }

    @Test
    void joinQueue_opponentWaiting_publishedMatchFoundEventHasARealBoardNotNull() throws Exception {
        String otherToken = sessionManager.createSession(2);
        playerService.joinQueue(otherToken, 1); // user 2 waits first

        playerService.joinQueue(sessionToken, 1); // user 1 matches them

        InMemoryGameEventPublisher.PublishedEvent published = gameEventPublisher.published().get(0);
        assertEquals(new CheckersEngine().initialState(), published.event().getGameState().getBoardState());
    }

    @Test
    void joinQueue_opponentWaiting_publishesMatchFoundEventToWaitingPlayer() throws Exception {
        String otherToken = sessionManager.createSession(2);
        playerService.joinQueue(otherToken, 1); // user 2 waits first

        GameStateDTO result = playerService.joinQueue(sessionToken, 1); // user 1 (this test's default caller) matches them

        assertEquals(1, gameEventPublisher.published().size());
        InMemoryGameEventPublisher.PublishedEvent published = gameEventPublisher.published().get(0);
        assertEquals(2, published.userId());
        assertEquals(GameEventType.MATCH_FOUND, published.event().getType());
        assertEquals(result.getSessionId(), published.event().getSessionId());
    }

    @Test
    void joinQueue_noOpponentWaiting_doesNotPublishAnyEvent() throws Exception {
        playerService.joinQueue(sessionToken, 1);

        assertEquals(0, gameEventPublisher.published().size());
    }

    @Test
    void joinQueue_publisherThrows_stillReturnsCallersOwnMatchedResult() throws Exception {
        PlayerServiceImpl playerServiceWithFailingPublisher = new PlayerServiceImpl(
                sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue,
                new FailingGameEventPublisher(), GameEngineRegistry.standard(), chatMessageDao, userDao);
        try {
            String otherToken = sessionManager.createSession(2);
            playerServiceWithFailingPublisher.joinQueue(otherToken, 1); // user 2 waits first

            GameStateDTO result = assertDoesNotThrow(
                    () -> playerServiceWithFailingPublisher.joinQueue(sessionToken, 1));

            assertNotNull(result);
            assertEquals(GameStatus.ACTIVE, result.getStatus());
        } finally {
            UnicastRemoteObject.unexportObject(playerServiceWithFailingPublisher, true);
        }
    }

    @Test
    void joinQueue_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.joinQueue("bogus-token", 1));
    }

    @Test
    void cancelQueue_validToken_doesNotThrow() throws Exception {
        playerService.joinQueue(sessionToken, 1);

        assertDoesNotThrow(() -> playerService.cancelQueue(sessionToken));
    }

    @Test
    void cancelQueue_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.cancelQueue("bogus-token"));
    }

    @Test
    void rematch_bothPlayersParticipatedAndOpponentRecentlyActive_createsSwappedActiveSession() throws Exception {
        gameSessionDao.addFinishedSession(new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

        GameStateDTO result = playerService.rematch(sessionToken, 1);

        assertEquals(GameStatus.ACTIVE, result.getStatus());
        assertEquals(2, result.getPlayer1Id(), "players should swap so turn order alternates");
        assertEquals(1, result.getPlayer2Id());
        assertEquals(2, result.getCurrentTurnUserId(), "the new player1 moves first");
    }

    @Test
    void rematch_notifiesTheOtherPlayerViaTheirPlayerQueue() throws Exception {
        gameSessionDao.addFinishedSession(new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

        GameStateDTO result = playerService.rematch(sessionToken, 1);

        assertEquals(1, gameEventPublisher.published().size());
        InMemoryGameEventPublisher.PublishedEvent published = gameEventPublisher.published().get(0);
        assertEquals(2, published.userId());
        assertEquals(GameEventType.REMATCH_CREATED, published.event().getType());
        assertEquals(result.getSessionId(), published.event().getSessionId());
    }

    @Test
    void rematch_repeatedCallForSameFinishedSession_isIdempotent() throws Exception {
        gameSessionDao.addFinishedSession(new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

        GameStateDTO first = playerService.rematch(sessionToken, 1);
        GameStateDTO second = playerService.rematch(otherSessionToken, 1);

        assertEquals(first.getSessionId(), second.getSessionId(), "a repeated rematch must not create a duplicate session");
    }

    @Test
    void rematch_notAParticipant_throwsNotParticipantException() throws Exception {
        gameSessionDao.addFinishedSession(new GameStateDTO(1, 1, 2, 3, GameStatus.FINISHED, null, 2, "board"));

        assertThrows(NotParticipantException.class, () -> playerService.rematch(sessionToken, 1));
    }

    @Test
    void rematch_sessionStillActive_throwsNotParticipantException() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));

        assertThrows(NotParticipantException.class, () -> playerService.rematch(sessionToken, 1));
    }

    @Test
    void rematch_opponentNeverSeenOnThisSessionManager_throwsAlreadyInGameException() throws Exception {
        // The "seen, but longer ago than the liveness window" branch of this same check is exercised
        // in isolation (with a fast custom window) by SessionManagerTest.onlineUserIds_excludesUsers...
        // -- rematch()'s check is a thin delegation to onlineUserIds(), so this test only needs to
        // cover the "opponent has no lastSeen entry at all" branch.
        SessionManager freshSessionManager = new SessionManager();
        String callerToken = freshSessionManager.createSession(1);
        // user 2 never calls resolve() on this fresh SessionManager, so they have no lastSeen entry
        PlayerServiceImpl serviceWithFreshSessionManager = new PlayerServiceImpl(
                freshSessionManager, gameSessionDao, gameTypeDao, matchmakingQueue, gameEventPublisher,
                GameEngineRegistry.standard(), chatMessageDao, userDao);
        try {
            gameSessionDao.addFinishedSession(new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

            assertThrows(AlreadyInGameException.class, () -> serviceWithFreshSessionManager.rematch(callerToken, 1));
        } finally {
            UnicastRemoteObject.unexportObject(serviceWithFreshSessionManager, true);
        }
    }

    @Test
    void rematch_unknownSession_throwsNotParticipantException() {
        assertThrows(NotParticipantException.class, () -> playerService.rematch(sessionToken, 999));
    }

    @Test
    void listLeaderboard_sortsByRatingThenWinsThenUsername_andIncludesAdmins() throws Exception {
        userDao.insert("admin", "hash", true); // id 3
        userDao.setStats(1, 5, 1, 0, 1300);
        userDao.setStats(2, 2, 3, 0, 1300);
        userDao.setStats(3, 0, 0, 0, 1400);

        List<UserDTO> board = playerService.listLeaderboard(sessionToken);

        assertEquals(List.of(3, 1, 2), board.stream().map(UserDTO::getId).toList());
        assertEquals("admin", board.get(0).getUsername());
        assertTrue(board.stream().noneMatch(UserDTO::isAdmin),
                "leaderboard must strip IsAdmin even for admin accounts that still appear");
    }

    @Test
    void listLeaderboard_equalRatingAndWins_sortsByUsernameThenId() throws Exception {
        userDao.insert("Alice", "hash"); // id 3
        userDao.setStats(1, 1, 0, 0, 1200);
        userDao.setStats(2, 1, 0, 0, 1200);
        userDao.setStats(3, 1, 0, 0, 1200);

        List<UserDTO> board = playerService.listLeaderboard(sessionToken);

        assertEquals(List.of("Alice", "player1", "player2"),
                board.stream().map(UserDTO::getUsername).toList());
    }

    @Test
    void listLeaderboard_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.listLeaderboard("bogus-token"));
    }

    @Test
    void getProfile_admin_stillReturnsRealAdminFlag() throws Exception {
        userDao.insert("admin", "hash", true); // id 3
        String adminToken = sessionManager.createSession(3);

        UserDTO profile = playerService.getProfile(adminToken);
        UserDTO onBoard = playerService.listLeaderboard(adminToken).stream()
                .filter(user -> user.getId() == 3)
                .findFirst()
                .orElseThrow();

        assertTrue(profile.isAdmin(), "getProfile must keep using toUserDTO, not the leaderboard mapper");
        assertFalse(onBoard.isAdmin());
    }

    @Test
    void getProfile_validToken_returnsCallersOwnRecord() throws Exception {
        UserDTO profile = playerService.getProfile(sessionToken);

        assertEquals(1, profile.getId());
        assertEquals("player1", profile.getUsername());
    }

    @Test
    void getProfile_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.getProfile("bogus-token"));
    }

    @Test
    void getOpponentProfile_participant_returnsTheOtherPlayer() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, "board"));
        userDao.markAdmin(2);

        UserDTO opponent = playerService.getOpponentProfile(sessionToken, 1);

        assertEquals(2, opponent.getId());
        assertEquals("player2", opponent.getUsername());
        assertFalse(opponent.isAdmin(), "opponent profiles must not leak the admin flag");
    }

    @Test
    void getOpponentProfile_worksAfterTheSessionHasFinished() throws Exception {
        gameSessionDao.addFinishedSession(new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 1, "board"));

        UserDTO opponent = playerService.getOpponentProfile(sessionToken, 1);

        assertEquals(2, opponent.getId());
    }

    @Test
    void getOpponentProfile_notAParticipant_throwsNotParticipantException() throws Exception {
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 2, 3, GameStatus.ACTIVE, 2, null, "board"));

        assertThrows(NotParticipantException.class, () -> playerService.getOpponentProfile(sessionToken, 1));
    }

    @Test
    void sendChatMessage_participant_persistsAndPublishesToSession() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        playerService.sendChatMessage(sessionToken, 1, "gl hf");

        List<ChatMessageDTO> history = chatMessageDao.findBySession(1);
        assertEquals(1, history.size());
        assertEquals(1, history.get(0).getUserId());
        assertEquals("gl hf", history.get(0).getContent());

        assertEquals(1, gameEventPublisher.publishedToSessions().size());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertEquals(GameEventType.CHAT_MESSAGE, published.event().getType());
        assertEquals(1, published.event().getChatSenderUserId());
        assertEquals("gl hf", published.event().getChatContent());
    }

    @Test
    void sendChatMessage_tooLong_isTruncatedToTheServerCap() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));
        String tooLong = "x".repeat(600);

        playerService.sendChatMessage(sessionToken, 1, tooLong);

        assertEquals(500, chatMessageDao.findBySession(1).get(0).getContent().length());
    }

    @Test
    void sendChatMessage_notAParticipant_throwsNotParticipantException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 2, 3, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotParticipantException.class, () -> playerService.sendChatMessage(sessionToken, 1, "hi"));
    }

    @Test
    void sendChatMessage_unknownSession_throwsNotParticipantException() {
        assertThrows(NotParticipantException.class, () -> playerService.sendChatMessage(sessionToken, 999, "hi"));
    }

    @Test
    void sendChatMessage_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.sendChatMessage("bogus-token", 1, "hi"));
    }

    @Test
    void getChatHistory_participant_returnsWhatDaoReturns() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));
        chatMessageDao.insert(1, 2, "hey");

        List<ChatMessageDTO> history = playerService.getChatHistory(sessionToken, 1);

        assertEquals(1, history.size());
        assertEquals("hey", history.get(0).getContent());
    }

    @Test
    void getChatHistory_notAParticipant_throwsNotParticipantException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 2, 3, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotParticipantException.class, () -> playerService.getChatHistory(sessionToken, 1));
    }

    @Test
    void resign_participant_endsGameAndPublishesAbandonedEventWithOpponentAsWinner() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        playerService.resign(sessionToken, 1);

        assertTrue(gameSessionDao.findActiveById(1).isEmpty(), "session should no longer be active");
        assertEquals(1, gameEventPublisher.publishedToSessions().size());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertEquals(1, published.sessionId());
        assertEquals(GameEventType.SESSION_ABANDONED, published.event().getType());
        assertEquals(Integer.valueOf(2), published.event().getGameState().getWinnerId());
    }

    @Test
    void resign_notAParticipant_throwsNotParticipantException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 2, 3, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotParticipantException.class, () -> playerService.resign(sessionToken, 1));
    }

    @Test
    void resign_unknownSession_throwsNotParticipantException() {
        assertThrows(NotParticipantException.class, () -> playerService.resign(sessionToken, 999));
    }

    @Test
    void resign_invalidToken_throwsAuthenticationException() {
        assertThrows(AuthenticationException.class, () -> playerService.resign("bogus-token", 1));
    }

    @Test
    void resign_publisherThrows_stillEndsGameForCaller() throws Exception {
        PlayerServiceImpl playerServiceWithFailingPublisher = new PlayerServiceImpl(
                sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue,
                new FailingGameEventPublisher(), GameEngineRegistry.standard(), chatMessageDao, userDao);
        try {
            String initialBoard = new CheckersEngine().initialState();
            gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

            assertDoesNotThrow(() -> playerServiceWithFailingPublisher.resign(sessionToken, 1));

            assertTrue(gameSessionDao.findActiveById(1).isEmpty(), "session should still end despite publish failure");
        } finally {
            UnicastRemoteObject.unexportObject(playerServiceWithFailingPublisher, true);
        }
    }

    @Test
    void resign_sessionAlreadyEndedConcurrently_returnsTheAuthoritativeOutcomeInstead() throws Exception {
        // Simulates the opponent's own winning move landing on the server a moment before this
        // resignation: the participant check still sees an ACTIVE-looking session, but abandon()
        // reports the race (empty), and the true record is already FINISHED with the opponent as
        // winner -- resign() must hand back that real outcome, not a fabricated "you lost".
        String initialBoard = new CheckersEngine().initialState();
        GameStateDTO stillLooksActive = new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard);
        GameStateDTO actuallyFinished = new GameStateDTO(1, 1, 1, 2, GameStatus.FINISHED, null, 2, initialBoard);
        InMemoryGameSessionDao raceDao = new InMemoryGameSessionDao() {
            @Override
            public java.util.Optional<GameStateDTO> findActiveById(int sessionId) {
                return sessionId == 1 ? java.util.Optional.of(stillLooksActive) : java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<GameStateDTO> abandon(int sessionId, Integer winnerUserId) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<GameStateDTO> findById(int sessionId) {
                return sessionId == 1 ? java.util.Optional.of(actuallyFinished) : java.util.Optional.empty();
            }
        };
        PlayerServiceImpl serviceWithRaceDao = new PlayerServiceImpl(
                sessionManager, raceDao, gameTypeDao, matchmakingQueue, gameEventPublisher, GameEngineRegistry.standard(),
                chatMessageDao, userDao);
        try {
            GameStateDTO result = serviceWithRaceDao.resign(sessionToken, 1);

            assertEquals(GameStatus.FINISHED, result.getStatus());
            assertEquals(Integer.valueOf(2), result.getWinnerId(),
                    "must return the real outcome (opponent's own winning move), not a fabricated one");
            assertEquals(0, gameEventPublisher.publishedToSessions().size(),
                    "resign() didn't end the game -- whoever actually did already published");
        } finally {
            UnicastRemoteObject.unexportObject(serviceWithRaceDao, true);
        }
    }

    @Test
    void makeMove_legalMove_appliesItAndReturnsUpdatedState() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        GameStateDTO result = playerService.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"a4\"]}");

        assertEquals(2, result.getCurrentTurnUserId());
        assertNotEquals(initialBoard, result.getBoardState());
    }

    @Test
    void makeMove_onAFreshlyMatchedSession_playsOffTheStoredOpeningBoard() throws Exception {
        playerService.joinQueue(sessionToken, 1);
        GameStateDTO matched = playerService.joinQueue(sessionManager.createSession(2), 1);
        gameSessionDao.addActiveSession(matched);

        GameStateDTO result = playerService.makeMove(sessionToken, matched.getSessionId(),
                "{\"path\":[\"b3\",\"a4\"]}");

        assertEquals(2, result.getCurrentTurnUserId());
        assertNotEquals(new CheckersEngine().initialState(), result.getBoardState());
    }

    @Test
    void makeMove_notAParticipant_throwsNotParticipantException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 2, 3, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotParticipantException.class,
                () -> playerService.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"a4\"]}"));
    }

    @Test
    void makeMove_notYourTurn_throwsNotYourTurnException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotYourTurnException.class,
                () -> playerService.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"a4\"]}"));
    }

    @Test
    void makeMove_illegalMove_throwsIllegalMoveException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        assertThrows(IllegalMoveException.class,
                () -> playerService.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"b5\"]}"));
    }

    @Test
    void makeMove_unknownSession_throwsIllegalMoveException() {
        assertThrows(IllegalMoveException.class,
                () -> playerService.makeMove(sessionToken, 999, "{\"path\":[\"b3\",\"a4\"]}"));
    }

    @Test
    void makeMove_legalMove_publishesMoveMadeEventToSession() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        GameStateDTO result = playerService.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"a4\"]}");

        assertEquals(1, gameEventPublisher.publishedToSessions().size());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertEquals(1, published.sessionId());
        assertEquals(GameEventType.MOVE_MADE, published.event().getType());
        assertEquals(result.getCurrentTurnUserId(), published.event().getGameState().getCurrentTurnUserId());
    }

    @Test
    void makeMove_publisherThrows_stillReturnsCallersOwnUpdatedState() throws Exception {
        PlayerServiceImpl playerServiceWithFailingPublisher = new PlayerServiceImpl(
                sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue,
                new FailingGameEventPublisher(), GameEngineRegistry.standard(), chatMessageDao, userDao);
        try {
            String initialBoard = new CheckersEngine().initialState();
            gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

            GameStateDTO result = assertDoesNotThrow(
                    () -> playerServiceWithFailingPublisher.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"a4\"]}"));

            assertEquals(2, result.getCurrentTurnUserId());
        } finally {
            UnicastRemoteObject.unexportObject(playerServiceWithFailingPublisher, true);
        }
    }

    @Test
    void legalContinuations_activeSession_delegatesToTheEngine() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        List<String> result = playerService.legalContinuations(sessionToken, 1, "{\"path\":[]}");

        assertTrue(result.contains("{\"path\":[\"b3\"]}"));
    }

    @Test
    void legalContinuations_onAFreshlyMatchedSession_readsTheStoredOpeningBoard() throws Exception {
        playerService.joinQueue(sessionToken, 1);
        GameStateDTO matched = playerService.joinQueue(sessionManager.createSession(2), 1);
        gameSessionDao.addActiveSession(matched);

        List<String> result = playerService.legalContinuations(sessionToken, matched.getSessionId(),
                "{\"path\":[]}");

        assertTrue(result.contains("{\"path\":[\"b3\"]}"));
    }

    @Test
    void legalContinuations_notAParticipant_throwsNotParticipantException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 2, 3, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotParticipantException.class,
                () -> playerService.legalContinuations(sessionToken, 1, "{\"path\":[]}"));
    }

    @Test
    void legalContinuations_notYourTurn_throwsNotYourTurnException() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 2, null, initialBoard));

        assertThrows(NotYourTurnException.class,
                () -> playerService.legalContinuations(sessionToken, 1, "{\"path\":[]}"));
    }

    @Test
    void legalContinuations_unknownSession_throwsNotParticipantException() {
        assertThrows(NotParticipantException.class,
                () -> playerService.legalContinuations(sessionToken, 999, "{\"path\":[]}"));
    }

    @Test
    void eightsMatch_playingLastMatchingCard_player1Wins() throws Exception {
        playerService.joinQueue(sessionToken, 2);
        GameStateDTO matched = playerService.joinQueue(otherSessionToken, 2);
        assertNotNull(matched);
        assertTrue(matched.getBoardState().contains("\"game\":\"eights\""));

        JSONObject board = new JSONObject();
        board.put("game", "eights");
        board.put("hand1", new JSONArray().put("7H"));
        board.put("hand2", new JSONArray().put("9S").put("2C"));
        board.put("draw", new JSONArray().put("3D"));
        board.put("discard", new JSONArray().put("KH"));
        board.put("namedSuit", JSONObject.NULL);
        board.put("pendingDrawn", JSONObject.NULL);
        gameSessionDao.addActiveSession(new GameStateDTO(
                matched.getSessionId(), 2, 1, 2, GameStatus.ACTIVE, 1, null, board.toString()));

        GameStateDTO result = playerService.makeMove(sessionToken, matched.getSessionId(),
                new JSONObject().put("action", "play").put("card", "7H").toString());

        assertEquals(GameStatus.FINISHED, result.getStatus());
        assertEquals(1, result.getWinnerId());
    }
}
