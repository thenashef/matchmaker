package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.exceptions.UsernameTakenException;

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

    UserDTO createUser(String sessionToken, String username, String password, boolean isAdmin)
        throws RemoteException, AuthenticationException, NotAdminException, UsernameTakenException,
               InvalidRegistrationException;

    List<GameStateDTO> listActiveSessions(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    void forceEndSession(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotAdminException;

    AdminDashboardStatsDTO getDashboardStats(String sessionToken)
        throws RemoteException, AuthenticationException, NotAdminException;

    List<MoveDTO> listMoves(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotAdminException;
}
