package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.rmi.PlayerService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
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

    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao,
                              MatchmakingQueue matchmakingQueue, GameEventPublisher gameEventPublisher) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
        this.matchmakingQueue = matchmakingQueue;
        this.gameEventPublisher = gameEventPublisher;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        GameStateDTO result = matchmakingQueue.join(userId, gameTypeId);

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
        throw new UnsupportedOperationException("makeMove not implemented yet -- see build-plan.md step 7");
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
