package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.server.SessionManager;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class AdminServiceImpl extends UnicastRemoteObject implements AdminService {

    private final SessionManager sessionManager;

    public AdminServiceImpl(SessionManager sessionManager) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("listGameTypes not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("addGameType not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public List<UserDTO> listUsers(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("listUsers not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public List<GameStateDTO> listActiveSessions(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("listActiveSessions not implemented yet -- see build-plan.md step 9");
    }

    @Override
    public void forceEndSession(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotAdminException {
        throw new UnsupportedOperationException("forceEndSession not implemented yet -- see build-plan.md step 9");
    }
}
