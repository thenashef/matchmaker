package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryServerConnection implements ServerConnection {

    public record MakeMoveCall(String sessionToken, int gameSessionId, String movePayload) {
    }

    private LoginResultDTO loginResult;
    private AuthenticationException loginFailure;
    private UserDTO registerResult;
    private UsernameTakenException registerFailure;
    private List<GameTypeDTO> gameTypes = new ArrayList<>();
    private GameStateDTO joinQueueResult;
    private boolean cancelQueueCalled = false;
    private GameStateDTO makeMoveResult;
    private IllegalMoveException makeMoveFailure;
    private final AtomicInteger keepAliveCallCount = new AtomicInteger();

    private final List<MakeMoveCall> makeMoveCalls = new ArrayList<>();
    private final Map<Integer, List<ServerEventListener>> playerQueueListeners = new HashMap<>();
    private final Map<Integer, List<ServerEventListener>> sessionTopicListeners = new HashMap<>();

    public void setLoginResult(LoginResultDTO result) { this.loginResult = result; }
    public void setLoginFailure(AuthenticationException failure) { this.loginFailure = failure; }
    public void setRegisterResult(UserDTO result) { this.registerResult = result; }
    public void setRegisterFailure(UsernameTakenException failure) { this.registerFailure = failure; }
    public void setGameTypes(List<GameTypeDTO> gameTypes) { this.gameTypes = gameTypes; }
    public void setJoinQueueResult(GameStateDTO result) { this.joinQueueResult = result; }
    public void setMakeMoveResult(GameStateDTO result) { this.makeMoveResult = result; }
    public void setMakeMoveFailure(IllegalMoveException failure) { this.makeMoveFailure = failure; }
    public boolean wasCancelQueueCalled() { return cancelQueueCalled; }
    public List<MakeMoveCall> makeMoveCalls() { return makeMoveCalls; }
    public int keepAliveCallCount() { return keepAliveCallCount.get(); }

    @Override
    public UserDTO register(String username, String password) throws UsernameTakenException {
        if (registerFailure != null) throw registerFailure;
        return registerResult;
    }

    @Override
    public LoginResultDTO login(String username, String password) throws AuthenticationException {
        if (loginFailure != null) throw loginFailure;
        return loginResult;
    }

    @Override
    public void keepAlive(String sessionToken) {
        keepAliveCallCount.incrementAndGet();
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) {
        return gameTypes;
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) {
        return joinQueueResult;
    }

    @Override
    public void cancelQueue(String sessionToken) {
        cancelQueueCalled = true;
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload) throws IllegalMoveException {
        makeMoveCalls.add(new MakeMoveCall(sessionToken, gameSessionId, movePayload));
        if (makeMoveFailure != null) throw makeMoveFailure;
        return makeMoveResult;
    }

    @Override
    public Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener) {
        playerQueueListeners.computeIfAbsent(userId, id -> new ArrayList<>()).add(listener);
        return () -> playerQueueListeners.getOrDefault(userId, List.of()).remove(listener);
    }

    @Override
    public Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        sessionTopicListeners.computeIfAbsent(sessionId, id -> new ArrayList<>()).add(listener);
        return () -> sessionTopicListeners.getOrDefault(sessionId, List.of()).remove(listener);
    }

    public boolean isSubscribedToPlayerQueue(int userId) {
        return !playerQueueListeners.getOrDefault(userId, List.of()).isEmpty();
    }

    public boolean isSubscribedToSessionTopic(int sessionId) {
        return !sessionTopicListeners.getOrDefault(sessionId, List.of()).isEmpty();
    }

    public void firePlayerQueueEvent(int userId, GameEventDTO event) {
        for (ServerEventListener listener : List.copyOf(playerQueueListeners.getOrDefault(userId, List.of()))) {
            listener.onEvent(event);
        }
    }

    public void fireSessionTopicEvent(int sessionId, GameEventDTO event) {
        for (ServerEventListener listener : List.copyOf(sessionTopicListeners.getOrDefault(sessionId, List.of()))) {
            listener.onEvent(event);
        }
    }
}
