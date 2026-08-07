package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.rmi.PlayerService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class PlayerServiceImpl extends UnicastRemoteObject implements PlayerService {

    private final SessionManager sessionManager;
    private final GameSessionDao gameSessionDao;
    private final GameTypeDao gameTypeDao;

    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao)
            throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public void joinQueue(String sessionToken, int gameTypeId) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("joinQueue not implemented yet -- see build-plan.md step 5");
    }

    @Override
    public void cancelQueue(String sessionToken) throws RemoteException, AuthenticationException {
        throw new UnsupportedOperationException("cancelQueue not implemented yet -- see build-plan.md step 5");
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
