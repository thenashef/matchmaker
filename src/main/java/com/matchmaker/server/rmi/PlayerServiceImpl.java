package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.rmi.PlayerService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.ConcurrentGameUpdateException;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.game.GameEngine;
import com.matchmaker.server.game.GameResult;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsPublishException;
import com.matchmaker.server.matchmaking.MatchmakingQueue;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class PlayerServiceImpl extends UnicastRemoteObject implements PlayerService {

    private final SessionManager sessionManager;
    private final GameSessionDao gameSessionDao;
    private final GameTypeDao gameTypeDao;
    private final MatchmakingQueue matchmakingQueue;
    private final GameEventPublisher gameEventPublisher;
    private final GameEngine gameEngine;

    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao,
                              MatchmakingQueue matchmakingQueue, GameEventPublisher gameEventPublisher,
                              GameEngine gameEngine) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
        this.matchmakingQueue = matchmakingQueue;
        this.gameEventPublisher = gameEventPublisher;
        this.gameEngine = gameEngine;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId)
            throws RemoteException, AuthenticationException, AlreadyInGameException {
        int userId = sessionManager.resolve(sessionToken);
        // The opening position is handed to the queue rather than patched in afterwards, so the
        // session row carries a real board from the moment it exists -- for the MATCH_FOUND push
        // and this return value, but equally for the readers that had no fallback of their own.
        GameStateDTO result = matchmakingQueue.join(userId, gameTypeId, gameEngine.initialState());

        if (result != null) {
            Integer opponentUserId;
            if (result.getPlayer1Id() == userId) {
                opponentUserId = result.getPlayer2Id();
            } else if (result.getPlayer2Id() == userId) {
                opponentUserId = result.getPlayer1Id();
            } else {
                // Caller isn't either participant in the session MatchmakingQueue.join() handed
                // back -- that's a bug elsewhere, not something to guess an opponent for.
                opponentUserId = null;
                System.err.println("joinQueue: matched session " + result.getSessionId()
                        + " does not include caller " + userId + " as either player -- skipping opponent notification");
            }

            if (opponentUserId != null) {
                try {
                    gameEventPublisher.publishToPlayer(opponentUserId,
                            new GameEventDTO(GameEventType.MATCH_FOUND, result.getSessionId(), result));
                } catch (JmsPublishException e) {
                    // The pairing already committed to the DB -- a failed notification to the
                    // *other* player shouldn't fail this caller's own, already-successful result.
                    System.err.println("Failed to notify opponent " + opponentUserId + " of match: " + e.getMessage());
                }
            }
        }

        return result;
    }

    @Override
    public void cancelQueue(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        matchmakingQueue.cancel(userId);
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException {
        int userId = sessionManager.resolve(sessionToken);

        GameStateDTO session = gameSessionDao.findActiveById(gameSessionId)
                .orElseThrow(() -> new IllegalMoveException("No active game session " + gameSessionId));

        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }
        if (session.getCurrentTurnUserId() == null || session.getCurrentTurnUserId() != userId) {
            throw new NotYourTurnException("It is not user " + userId + "'s turn in session " + gameSessionId);
        }

        String currentBoardState = session.getBoardState();
        boolean isPlayer1Turn = session.getPlayer1Id() == userId;
        if (!gameEngine.isLegalMove(currentBoardState, isPlayer1Turn, movePayload)) {
            throw new IllegalMoveException("Illegal move for session " + gameSessionId + ": " + movePayload);
        }

        String newBoardState = gameEngine.applyMove(currentBoardState, isPlayer1Turn, movePayload);
        GameResult result = gameEngine.checkWinner(newBoardState, !isPlayer1Turn);

        int opponentId = isPlayer1Turn ? session.getPlayer2Id() : session.getPlayer1Id();
        GameStateDTO updatedSession;
        if (result == GameResult.CONTINUE) {
            updatedSession = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                    session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.ACTIVE, opponentId, null, newBoardState);
        } else {
            Integer winnerId = result == GameResult.PLAYER1_WINS ? session.getPlayer1Id()
                    : result == GameResult.PLAYER2_WINS ? session.getPlayer2Id() : null;
            updatedSession = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                    session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.FINISHED, null, winnerId, newBoardState);
        }

        GameStateDTO persistedSession;
        try {
            persistedSession = gameSessionDao.recordMove(updatedSession, userId, movePayload);
        } catch (ConcurrentGameUpdateException e) {
            // Someone else's call for this same session committed first since we read it --
            // from this caller's perspective that means the turn/status they validated
            // against is stale, which is exactly what NotYourTurnException communicates.
            throw new NotYourTurnException("Session " + gameSessionId + " changed since it was read -- "
                    + "it is no longer user " + userId + "'s turn (or the game already ended)");
        }

        try {
            gameEventPublisher.publishToSession(gameSessionId,
                    new GameEventDTO(GameEventType.MOVE_MADE, gameSessionId, persistedSession));
        } catch (JmsPublishException e) {
            // The move already committed to the DB -- a failed notification shouldn't undo or
            // fail the mover's own already-successful result. Mirrors joinQueue()'s handling.
            System.err.println("Failed to notify session " + gameSessionId + " of move: " + e.getMessage());
        }

        return persistedSession;
    }

    @Override
    public List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayload)
            throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException {
        int userId = sessionManager.resolve(sessionToken);

        GameStateDTO session = gameSessionDao.findActiveById(gameSessionId)
                .orElseThrow(() -> new NotParticipantException("No active game session " + gameSessionId));

        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }
        if (session.getCurrentTurnUserId() == null || session.getCurrentTurnUserId() != userId) {
            throw new NotYourTurnException("It is not user " + userId + "'s turn in session " + gameSessionId);
        }

        boolean isPlayer1Turn = session.getPlayer1Id() == userId;
        return gameEngine.legalContinuations(session.getBoardState(), isPlayer1Turn, partialMovePayload);
    }

    @Override
    public void sendChatMessage(String sessionToken, int gameSessionId, String content)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("sendChatMessage not implemented yet -- see build-plan.md step 6");
    }

    @Override
    public void resign(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("resign not implemented yet -- see build-plan.md step 7");
    }

    @Override
    public GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        throw new UnsupportedOperationException("rematch not implemented yet -- see build-plan.md step 10");
    }

    @Override
    public List<GameStateDTO> getHistory(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        return gameSessionDao.findFinishedSessionsForUser(userId);
    }
}
