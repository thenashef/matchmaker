package com.matchmaker.admin.communication;

import com.matchmaker.common.communication.ServerEventListener;
import com.matchmaker.common.communication.Subscription;
import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryAdminConnection implements AdminConnection {

    private LoginResultDTO loginResult;
    private AuthenticationException loginFailure;
    private List<GameTypeDTO> gameTypes = new ArrayList<>();
    private GameTypeDTO addGameTypeResult;
    private List<UserDTO> users = new ArrayList<>();
    private List<UserDTO> onlineUsers = new ArrayList<>();
    private List<GameStateDTO> activeSessions = new ArrayList<>();
    private boolean forceEndSessionCalled = false;
    private NotAdminException notAdminFailure;
    private AdminDashboardStatsDTO dashboardStats;
    private List<MoveDTO> moves = new ArrayList<>();
    private int lastListMovesSessionId = -1;
    private UserDTO createUserResult;
    private UserDTO promoteToAdminResult;
    private int lastPromoteToAdminUserId = -1;
    private UsernameTakenException createUserUsernameTakenFailure;
    private InvalidRegistrationException createUserInvalidRegistrationFailure;
    private String lastCreateUserUsername;
    private boolean lastCreateUserIsAdmin;
    private final AtomicInteger keepAliveCallCount = new AtomicInteger();
    private final java.util.List<String> loggedOutTokens = new java.util.ArrayList<>();
    private volatile String lastKeepAliveToken;
    private boolean closed;

    private final Map<Integer, List<ServerEventListener>> sessionTopicListeners = new HashMap<>();

    public void setLoginResult(LoginResultDTO result) { this.loginResult = result; }
    public void setLoginFailure(AuthenticationException failure) { this.loginFailure = failure; }
    public void setGameTypes(List<GameTypeDTO> gameTypes) { this.gameTypes = gameTypes; }
    public void setAddGameTypeResult(GameTypeDTO result) { this.addGameTypeResult = result; }
    public void setUsers(List<UserDTO> users) { this.users = users; }
    public void setOnlineUsers(List<UserDTO> onlineUsers) { this.onlineUsers = onlineUsers; }
    public void setActiveSessions(List<GameStateDTO> activeSessions) { this.activeSessions = activeSessions; }
    public void setNotAdminFailure(NotAdminException failure) { this.notAdminFailure = failure; }
    public void setDashboardStats(AdminDashboardStatsDTO stats) { this.dashboardStats = stats; }
    public void setMoves(List<MoveDTO> moves) { this.moves = moves; }
    public int lastListMovesSessionId() { return lastListMovesSessionId; }
    public void setCreateUserResult(UserDTO result) { this.createUserResult = result; }
    public void setPromoteToAdminResult(UserDTO result) { this.promoteToAdminResult = result; }
    public int lastPromoteToAdminUserId() { return lastPromoteToAdminUserId; }
    public void setCreateUserUsernameTakenFailure(UsernameTakenException failure) { this.createUserUsernameTakenFailure = failure; }
    public void setCreateUserInvalidRegistrationFailure(InvalidRegistrationException failure) { this.createUserInvalidRegistrationFailure = failure; }
    public String lastCreateUserUsername() { return lastCreateUserUsername; }
    public boolean lastCreateUserIsAdmin() { return lastCreateUserIsAdmin; }
    public boolean wasForceEndSessionCalled() { return forceEndSessionCalled; }
    public int keepAliveCallCount() { return keepAliveCallCount.get(); }
    public java.util.List<String> loggedOutTokens() { return loggedOutTokens; }
    public boolean isClosed() { return closed; }
    public String lastKeepAliveToken() { return lastKeepAliveToken; }

    @Override
    public LoginResultDTO login(String username, String password) throws AuthenticationException {
        if (loginFailure != null) throw loginFailure;
        return loginResult;
    }

    @Override
    public void logout(String sessionToken) {
        loggedOutTokens.add(sessionToken);
    }

    @Override
    public void keepAlive(String sessionToken) {
        lastKeepAliveToken = sessionToken;
        keepAliveCallCount.incrementAndGet();
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws NotAdminException {
        if (notAdminFailure != null) throw notAdminFailure;
        return gameTypes;
    }

    @Override
    public GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType) throws NotAdminException {
        if (notAdminFailure != null) throw notAdminFailure;
        return addGameTypeResult;
    }

    @Override
    public List<UserDTO> listUsers(String sessionToken) throws NotAdminException {
        if (notAdminFailure != null) throw notAdminFailure;
        return users;
    }

    @Override
    public List<UserDTO> listOnlineUsers(String sessionToken) throws NotAdminException {
        if (notAdminFailure != null) throw notAdminFailure;
        return onlineUsers;
    }

    @Override
    public UserDTO promoteToAdmin(String sessionToken, int userId) throws NotAdminException {
        lastPromoteToAdminUserId = userId;
        if (notAdminFailure != null) throw notAdminFailure;
        return promoteToAdminResult;
    }

    @Override
    public UserDTO createUser(String sessionToken, String username, String password, boolean isAdmin)
            throws NotAdminException, UsernameTakenException, InvalidRegistrationException {
        lastCreateUserUsername = username;
        lastCreateUserIsAdmin = isAdmin;
        if (notAdminFailure != null) throw notAdminFailure;
        if (createUserUsernameTakenFailure != null) throw createUserUsernameTakenFailure;
        if (createUserInvalidRegistrationFailure != null) throw createUserInvalidRegistrationFailure;
        return createUserResult;
    }

    @Override
    public List<GameStateDTO> listActiveSessions(String sessionToken) throws NotAdminException {
        if (notAdminFailure != null) throw notAdminFailure;
        return activeSessions;
    }

    @Override
    public void forceEndSession(String sessionToken, int gameSessionId) throws NotAdminException {
        if (notAdminFailure != null) throw notAdminFailure;
        forceEndSessionCalled = true;
    }

    @Override
    public AdminDashboardStatsDTO getDashboardStats(String sessionToken) throws NotAdminException {
        if (notAdminFailure != null) throw notAdminFailure;
        return dashboardStats;
    }

    @Override
    public List<MoveDTO> listMoves(String sessionToken, int gameSessionId) throws NotAdminException {
        lastListMovesSessionId = gameSessionId;
        if (notAdminFailure != null) throw notAdminFailure;
        return moves;
    }

    @Override
    public Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        sessionTopicListeners.computeIfAbsent(sessionId, id -> new ArrayList<>()).add(listener);
        return () -> sessionTopicListeners.getOrDefault(sessionId, List.of()).remove(listener);
    }

    public boolean isSubscribedToSessionTopic(int sessionId) {
        return !sessionTopicListeners.getOrDefault(sessionId, List.of()).isEmpty();
    }

    public void fireSessionTopicEvent(int sessionId, GameEventDTO event) {
        for (ServerEventListener listener : List.copyOf(sessionTopicListeners.getOrDefault(sessionId, List.of()))) {
            listener.onEvent(event);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
