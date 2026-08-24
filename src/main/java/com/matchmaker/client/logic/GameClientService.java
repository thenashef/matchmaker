package com.matchmaker.client.logic;

import com.matchmaker.client.communication.ServerConnection;
import com.matchmaker.common.communication.Subscription;
import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameHistoryDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.fx.FxAsync;
import javafx.application.Platform;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameClientService {

    private static final Logger LOG = Logger.getLogger(GameClientService.class.getName());

    private final ServerConnection serverConnection;
    private final Duration keepAliveInterval;
    private final ExecutorService backgroundExecutor = FxAsync.daemonExecutor("server-communication");
    private ScheduledExecutorService keepAliveExecutor;

    private UserDTO currentUser;
    private String sessionToken;

    private Subscription playerQueueSubscription;
    private Consumer<GameStateDTO> pendingMatchCallback;
    private volatile Consumer<GameStateDTO> rematchListener;

    private Subscription sessionTopicSubscription;
    private volatile GameStateDTO currentGameState;
    private volatile Consumer<GameStateDTO> gameUpdateListener;
    private volatile Consumer<ChatMessageDTO> chatListener;

    public GameClientService(ServerConnection serverConnection) {
        this(serverConnection, Duration.ofSeconds(15));
    }

    GameClientService(ServerConnection serverConnection, Duration keepAliveInterval) {
        this.serverConnection = serverConnection;
        this.keepAliveInterval = keepAliveInterval;
    }

    public UserDTO getCurrentUser() {
        return currentUser;
    }

    public void login(String username, String password, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.login(username, password),
                result -> {
                    currentUser = result.getUser();
                    sessionToken = result.getSessionToken();
                    startKeepAlive();
                    try {
                        playerQueueSubscription = serverConnection.subscribeToPlayerQueue(
                                currentUser.getId(), this::onPlayerQueueEvent);
                    } catch (Exception e) {
                        abortFailedLogin();
                        onError.accept(e);
                        return;
                    }
                    onSuccess.accept(result.getUser());
                },
                onError);
    }

    public void register(String username, String password, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.register(username, password), onSuccess, onError);
    }

    public void listGameTypes(Consumer<List<GameTypeDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.listGameTypes(sessionToken), onSuccess, onError);
    }

    public void listLeaderboard(Consumer<List<UserDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.listLeaderboard(sessionToken), onSuccess, onError);
    }

    public void joinQueue(int gameTypeId, Consumer<GameStateDTO> onMatched, Runnable onWaiting, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.joinQueue(sessionToken, gameTypeId),
                result -> {
                    if (result != null) {
                        enterGame(result);
                        onMatched.accept(result);
                    } else {
                        pendingMatchCallback = onMatched;
                        onWaiting.run();
                    }
                },
                onError);
    }

    public void cancelQueue(Runnable onCancelled, Consumer<Throwable> onError) {
        runAsync(() -> { serverConnection.cancelQueue(sessionToken); return null; },
                ignored -> {
                    pendingMatchCallback = null;
                    onCancelled.run();
                },
                onError);
    }

    public void makeMove(int gameSessionId, String movePayload, Consumer<GameStateDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.makeMove(sessionToken, gameSessionId, movePayload),
                result -> {
                    currentGameState = result;
                    onSuccess.accept(result);
                },
                onError);
    }

    public void legalContinuations(int gameSessionId, String partialMovePayload,
                                    Consumer<List<String>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.legalContinuations(sessionToken, gameSessionId, partialMovePayload),
                onSuccess, onError);
    }

    public void resign(int gameSessionId, Consumer<GameStateDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.resign(sessionToken, gameSessionId),
                result -> {
                    currentGameState = result;
                    onSuccess.accept(result);
                },
                onError);
    }

    public GameStateDTO attachGameUpdateListener(Consumer<GameStateDTO> listener) {
        this.gameUpdateListener = listener;
        return currentGameState;
    }

    public void attachChatListener(Consumer<ChatMessageDTO> listener) {
        this.chatListener = listener;
    }

    public void sendChatMessage(int gameSessionId, String content, Runnable onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> { serverConnection.sendChatMessage(sessionToken, gameSessionId, content); return null; },
                ignored -> onSuccess.run(),
                onError);
    }

    public void getChatHistory(int gameSessionId, Consumer<List<ChatMessageDTO>> onSuccess,
                                Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.getChatHistory(sessionToken, gameSessionId), onSuccess, onError);
    }

    public void attachRematchListener(Consumer<GameStateDTO> listener) {
        this.rematchListener = listener;
    }

    public void rematch(int finishedSessionId, Consumer<GameStateDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.rematch(sessionToken, finishedSessionId),
                result -> {
                    enterGame(result);
                    onSuccess.accept(result);
                },
                onError);
    }

    public void getHistory(Consumer<List<GameHistoryDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.getHistory(sessionToken), onSuccess, onError);
    }

    public void getProfile(Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.getProfile(sessionToken),
                result -> {
                    currentUser = result;
                    onSuccess.accept(result);
                },
                onError);
    }

    public void getOpponentProfile(int gameSessionId, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.getOpponentProfile(sessionToken, gameSessionId), onSuccess, onError);
    }

    public void leaveGame() {
        gameUpdateListener = null;
        chatListener = null;
        if (sessionTopicSubscription != null) {
            sessionTopicSubscription.close();
            sessionTopicSubscription = null;
        }
        currentGameState = null;
    }

    /** Ends the current session so another account can log in on this client. Leaves the
     *  connection and background executor running. */
    public void logout() {
        leaveGame();
        rematchListener = null;
        pendingMatchCallback = null;
        if (playerQueueSubscription != null) {
            playerQueueSubscription.close();
            playerQueueSubscription = null;
        }
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
            keepAliveExecutor = null;
        }
        if (sessionToken != null) {
            try {
                serverConnection.logout(sessionToken);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "logout() failed", e);
            }
            sessionToken = null;
        }
        currentUser = null;
    }

    public void shutdown() {
        logout();
        backgroundExecutor.shutdownNow();
    }

    private void abortFailedLogin() {
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
            keepAliveExecutor = null;
        }
        if (sessionToken != null) {
            try {
                serverConnection.logout(sessionToken);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to logout after a failed login subscribe", e);
            }
            sessionToken = null;
        }
        currentUser = null;
    }

    private void startKeepAlive() {
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
        }
        keepAliveExecutor = FxAsync.startKeepAlive(keepAliveInterval,
                () -> serverConnection.keepAlive(sessionToken), LOG);
    }

    private void enterGame(GameStateDTO initialState) {
        if (currentGameState != null && currentGameState.getSessionId() == initialState.getSessionId()) {
            return;
        }
        if (sessionTopicSubscription != null) {
            sessionTopicSubscription.close();
        }
        currentGameState = initialState;
        sessionTopicSubscription = serverConnection.subscribeToSessionTopic(
                initialState.getSessionId(), this::onSessionTopicEvent);
    }

    private void onPlayerQueueEvent(GameEventDTO event) {
        if (event.getType() == GameEventType.REMATCH_CREATED) {
            Platform.runLater(() -> {
                GameStateDTO newSession = event.getGameState();
                pendingMatchCallback = null;
                enterGame(newSession);
                if (rematchListener != null) {
                    rematchListener.accept(newSession);
                }
            });
            return;
        }
        if (event.getType() != GameEventType.MATCH_FOUND) {
            return;
        }
        Platform.runLater(() -> {
            GameStateDTO matchedState = event.getGameState();
            enterGame(matchedState);
            if (pendingMatchCallback != null) {
                Consumer<GameStateDTO> callback = pendingMatchCallback;
                pendingMatchCallback = null;
                callback.accept(matchedState);
            }
        });
    }

    private void onSessionTopicEvent(GameEventDTO event) {
        if (event.getType() == GameEventType.CHAT_MESSAGE) {
            if (event.getChatSenderUserId() == null) {
                LOG.log(Level.WARNING, "Ignoring CHAT_MESSAGE event with no sender for session " + event.getSessionId());
                return;
            }
            Platform.runLater(() -> {
                if (chatListener != null) {
                    chatListener.accept(new ChatMessageDTO(event.getSessionId(), event.getChatSenderUserId(),
                            event.getChatContent(), LocalDateTime.now()));
                }
            });
            return;
        }
        if (event.getType() != GameEventType.MOVE_MADE && event.getType() != GameEventType.SESSION_FORCE_ENDED
                && event.getType() != GameEventType.SESSION_ABANDONED) {
            return;
        }
        Platform.runLater(() -> {
            currentGameState = event.getGameState();
            if (gameUpdateListener != null) {
                gameUpdateListener.accept(currentGameState);
            }
        });
    }

    private <T> void runAsync(FxAsync.ThrowingSupplier<T> action, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        FxAsync.run(backgroundExecutor, action, onSuccess, onError);
    }
}
