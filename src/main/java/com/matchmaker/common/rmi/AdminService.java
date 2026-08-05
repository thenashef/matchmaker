package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface AdminService extends Remote {
    List<GameTypeDTO> listGameTypes(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
        throws RemoteException, AuthenticationException, NotAdminException;

    List<UserDTO> listUsers(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    List<GameStateDTO> listActiveSessions(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    void forceEndSession(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotAdminException;
}
