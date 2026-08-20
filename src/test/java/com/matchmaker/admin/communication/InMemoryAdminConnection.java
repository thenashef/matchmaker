package com.matchmaker.admin.communication;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;

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
    private List<GameStateDTO> activeSessions = new ArrayList<>();
    private boolean forceEndSessionCalled = false;
    private NotAdminException notAdminFailure;
    private final AtomicInteger keepAliveCallCount = new AtomicInteger();
    private final java.util.List<String> loggedOutTokens = new java.util.ArrayList<>();
    private volatile String lastKeepAliveToken;

    private final Map<Integer, List<ServerEventListener>> sessionTopicListeners = new HashMap<>();

    public void setLoginResult(LoginResultDTO result) { this.loginResult = result; }
    public void setLoginFailure(AuthenticationException failure) { this.loginFailure = failure; }
    public void setGameTypes(List<GameTypeDTO> gameTypes) { this.gameTypes = gameTypes; }
    public void setAddGameTypeResult(GameTypeDTO result) { this.addGameTypeResult = result; }
    public void setUsers(List<UserDTO> users) { this.users = users; }
    public void setActiveSessions(List<GameStateDTO> activeSessions) { this.activeSessions = activeSessions; }
    public void setNotAdminFailure(NotAdminException failure) { this.notAdminFailure = failure; }
    public boolean wasForceEndSessionCalled() { return forceEndSessionCalled; }
    public int keepAliveCallCount() { return keepAliveCallCount.get(); }
    public java.util.List<String> loggedOutTokens() { return loggedOutTokens; }
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
}
