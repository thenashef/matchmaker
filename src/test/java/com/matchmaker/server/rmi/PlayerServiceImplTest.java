package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryGameTypeDao;
import com.matchmaker.server.game.checkers.CheckersEngine;
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
    private PlayerServiceImpl playerService;
    private String sessionToken;

    @BeforeEach
    void createPlayerService() throws Exception {
        sessionManager = new SessionManager();
        gameSessionDao = new InMemoryGameSessionDao();
        gameTypeDao = new InMemoryGameTypeDao();
        matchmakingQueue = new InMemoryMatchmakingQueue();
        gameEventPublisher = new InMemoryGameEventPublisher();
        playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue,
                gameEventPublisher, new CheckersEngine());
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
        // joinQueue() hands gameEngine.initialState() to the queue, which stores it on the new
        // session -- so the board is real from the moment the session exists, rather than being
        // patched in on the way out. The client needs one to render the instant it's matched.
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
                new FailingGameEventPublisher(), new CheckersEngine());
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
    void remainingMethods_stillThrowUnsupportedOperationException() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> playerService.sendChatMessage(sessionToken, 1, "hi"));
        assertThrows(UnsupportedOperationException.class, () -> playerService.resign(sessionToken, 1));
        assertThrows(UnsupportedOperationException.class, () -> playerService.rematch(sessionToken, 1));
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
        // The first move of a game used to be a special case: matchmaking left BoardState null,
        // so makeMove() had to substitute gameEngine.initialState() before it could validate
        // anything. The session now carries the opening position from creation, so this goes
        // through the ordinary path -- and, unlike the old fallback, the board the move is
        // applied to is the one actually stored on the row rather than one reconstructed here.
        // sessionToken (user 1) queues first, so they become player1 and hold the opening turn.
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
                new FailingGameEventPublisher(), new CheckersEngine());
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
        // Was a null-board fallback case; matchmaking now stores the opening position on the
        // session, so this is just the ordinary path reading a real board off the row.
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
}
