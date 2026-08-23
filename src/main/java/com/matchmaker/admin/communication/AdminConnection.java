package com.matchmaker.admin.communication;

import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.communication.ServerEventListener;
import com.matchmaker.common.communication.Subscription;

import java.util.List;

public interface AdminConnection {

    LoginResultDTO login(String username, String password) throws AuthenticationException;

    void keepAlive(String sessionToken) throws AuthenticationException;

    void logout(String sessionToken);

    List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException, NotAdminException;

    GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
            throws AuthenticationException, NotAdminException;

    List<UserDTO> listUsers(String sessionToken) throws AuthenticationException, NotAdminException;

    UserDTO createUser(String sessionToken, String username, String password, boolean isAdmin)
            throws AuthenticationException, NotAdminException, UsernameTakenException, InvalidRegistrationException;

    List<GameStateDTO> listActiveSessions(String sessionToken) throws AuthenticationException, NotAdminException;

    void forceEndSession(String sessionToken, int gameSessionId) throws AuthenticationException, NotAdminException;

    AdminDashboardStatsDTO getDashboardStats(String sessionToken) throws AuthenticationException, NotAdminException;

    List<MoveDTO> listMoves(String sessionToken, int gameSessionId) throws AuthenticationException, NotAdminException;

    Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener);

    void close();
}
