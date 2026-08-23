package com.matchmaker.client.communication;

import com.matchmaker.common.communication.ServerEventListener;
import com.matchmaker.common.communication.Subscription;
import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
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
    private AlreadyInGameException joinQueueFailure;
    private final List<String> loggedOutTokens = new ArrayList<>();
    private List<GameTypeDTO> gameTypes = new ArrayList<>();
    private GameStateDTO joinQueueResult;
    private boolean cancelQueueCalled = false;
    private GameStateDTO makeMoveResult;
    private IllegalMoveException makeMoveFailure;
    private List<String> legalContinuationsResult = new ArrayList<>();
    private NotYourTurnException legalContinuationsFailure;
    private NotParticipantException resignFailure;
    private boolean resignCalled = false;
    private int lastResignSessionId = -1;
    private GameStateDTO resignResult;
    private final List<String> sentChatMessages = new ArrayList<>();
    private List<ChatMessageDTO> chatHistoryResult = new ArrayList<>();
    private GameStateDTO rematchResult;
    private NotParticipantException rematchNotParticipantFailure;
    private AlreadyInGameException rematchAlreadyInGameFailure;
    private int lastRematchFinishedSessionId = -1;
    private List<UserDTO> leaderboard = new ArrayList<>();
    private UserDTO profileResult;
    private UserDTO opponentProfileResult;
    private final AtomicInteger keepAliveCallCount = new AtomicInteger();
    private volatile String lastKeepAliveToken;

    private RuntimeException subscribeToPlayerQueueFailure;
    private final List<MakeMoveCall> makeMoveCalls = new ArrayList<>();
    private final Map<Integer, List<ServerEventListener>> playerQueueListeners = new HashMap<>();
    private final Map<Integer, List<ServerEventListener>> sessionTopicListeners = new HashMap<>();
    private final Map<Integer, Integer> sessionTopicSubscribeCalls = new HashMap<>();

    public void setLoginResult(LoginResultDTO result) { this.loginResult = result; }
    public void setLoginFailure(AuthenticationException failure) { this.loginFailure = failure; }
    public void setSubscribeToPlayerQueueFailure(RuntimeException failure) {
        this.subscribeToPlayerQueueFailure = failure;
    }
    public void setRegisterResult(UserDTO result) { this.registerResult = result; }
    public void setRegisterFailure(UsernameTakenException failure) { this.registerFailure = failure; }
    public void setGameTypes(List<GameTypeDTO> gameTypes) { this.gameTypes = gameTypes; }
    public void setJoinQueueResult(GameStateDTO result) { this.joinQueueResult = result; }
    public void setJoinQueueFailure(AlreadyInGameException failure) { this.joinQueueFailure = failure; }
    public List<String> loggedOutTokens() { return loggedOutTokens; }
    public void setMakeMoveResult(GameStateDTO result) { this.makeMoveResult = result; }
    public void setMakeMoveFailure(IllegalMoveException failure) { this.makeMoveFailure = failure; }
    public void setLegalContinuationsResult(List<String> result) { this.legalContinuationsResult = result; }
    public void setLegalContinuationsFailure(NotYourTurnException failure) { this.legalContinuationsFailure = failure; }
    public void setResignFailure(NotParticipantException failure) { this.resignFailure = failure; }
    public void setResignResult(GameStateDTO result) { this.resignResult = result; }
    public boolean wasResignCalled() { return resignCalled; }
    public int lastResignSessionId() { return lastResignSessionId; }
    public List<String> sentChatMessages() { return sentChatMessages; }
    public void setChatHistoryResult(List<ChatMessageDTO> result) { this.chatHistoryResult = result; }
    public void setRematchResult(GameStateDTO result) { this.rematchResult = result; }
    public void setRematchNotParticipantFailure(NotParticipantException failure) { this.rematchNotParticipantFailure = failure; }
    public void setRematchAlreadyInGameFailure(AlreadyInGameException failure) { this.rematchAlreadyInGameFailure = failure; }
    public int lastRematchFinishedSessionId() { return lastRematchFinishedSessionId; }
    public void setLeaderboard(List<UserDTO> leaderboard) { this.leaderboard = leaderboard; }
    public void setProfileResult(UserDTO result) { this.profileResult = result; }
    public void setOpponentProfileResult(UserDTO result) { this.opponentProfileResult = result; }
    public boolean wasCancelQueueCalled() { return cancelQueueCalled; }
    public List<MakeMoveCall> makeMoveCalls() { return makeMoveCalls; }
    public int keepAliveCallCount() { return keepAliveCallCount.get(); }
    public String lastKeepAliveToken() { return lastKeepAliveToken; }

    @Override
    public UserDTO register(String username, String password)
            throws UsernameTakenException, InvalidRegistrationException {
        if (registerFailure != null) throw registerFailure;
        return registerResult;
    }

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
    public List<GameTypeDTO> listGameTypes(String sessionToken) {
        return gameTypes;
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws AlreadyInGameException {
        if (joinQueueFailure != null) throw joinQueueFailure;
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
    public List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayload)
            throws NotYourTurnException {
        if (legalContinuationsFailure != null) throw legalContinuationsFailure;
        return legalContinuationsResult;
    }

    @Override
    public GameStateDTO resign(String sessionToken, int gameSessionId) throws NotParticipantException {
        resignCalled = true;
        lastResignSessionId = gameSessionId;
        if (resignFailure != null) throw resignFailure;
        return resignResult;
    }

    @Override
    public void sendChatMessage(String sessionToken, int gameSessionId, String content) {
        sentChatMessages.add(content);
    }

    @Override
    public List<ChatMessageDTO> getChatHistory(String sessionToken, int gameSessionId) {
        return chatHistoryResult;
    }

    @Override
    public GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws NotParticipantException, AlreadyInGameException {
        lastRematchFinishedSessionId = finishedSessionId;
        if (rematchNotParticipantFailure != null) throw rematchNotParticipantFailure;
        if (rematchAlreadyInGameFailure != null) throw rematchAlreadyInGameFailure;
        return rematchResult;
    }

    @Override
    public List<UserDTO> listLeaderboard(String sessionToken) {
        return leaderboard;
    }

    @Override
    public UserDTO getProfile(String sessionToken) {
        return profileResult;
    }

    @Override
    public UserDTO getOpponentProfile(String sessionToken, int gameSessionId) {
        return opponentProfileResult;
    }

    @Override
    public Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener) {
        if (subscribeToPlayerQueueFailure != null) {
            throw subscribeToPlayerQueueFailure;
        }
        playerQueueListeners.computeIfAbsent(userId, id -> new ArrayList<>()).add(listener);
        return () -> playerQueueListeners.getOrDefault(userId, List.of()).remove(listener);
    }

    @Override
    public Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        sessionTopicListeners.computeIfAbsent(sessionId, id -> new ArrayList<>()).add(listener);
        sessionTopicSubscribeCalls.merge(sessionId, 1, Integer::sum);
        return () -> sessionTopicListeners.getOrDefault(sessionId, List.of()).remove(listener);
    }

    public boolean isSubscribedToPlayerQueue(int userId) {
        return !playerQueueListeners.getOrDefault(userId, List.of()).isEmpty();
    }

    public boolean isSubscribedToSessionTopic(int sessionId) {
        return !sessionTopicListeners.getOrDefault(sessionId, List.of()).isEmpty();
    }

    /** Cumulative subscribeToSessionTopic() calls for this session, unaffected by later unsubscribes. */
    public int sessionTopicSubscribeCallCount(int sessionId) {
        return sessionTopicSubscribeCalls.getOrDefault(sessionId, 0);
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

    @Override
    public void close() {
    }
}
