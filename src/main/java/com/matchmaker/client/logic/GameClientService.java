package com.matchmaker.client.logic;

import com.matchmaker.client.communication.ServerConnection;
import com.matchmaker.client.communication.Subscription;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import javafx.application.Platform;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GameClientService {

    private static final Logger LOG = Logger.getLogger(GameClientService.class.getName());

    private final ServerConnection serverConnection;
    private final Duration keepAliveInterval;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "server-communication");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledExecutorService keepAliveExecutor;

    private UserDTO currentUser;
    private String sessionToken;

    private Subscription playerQueueSubscription;
    private Consumer<GameStateDTO> pendingMatchCallback;

    private Subscription sessionTopicSubscription;
    private volatile GameStateDTO currentGameState;
    private volatile Consumer<GameStateDTO> gameUpdateListener;

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

    public void joinQueue(int gameTypeId, Consumer<GameStateDTO> onMatched, Runnable onWaiting, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.joinQueue(sessionToken, gameTypeId),
                result -> {
                    if (result != null) {
                        enterGame(result);
                        onMatched.accept(result);
                    } else {
                        pendingMatchCallback = onMatched;
                        playerQueueSubscription = serverConnection.subscribeToPlayerQueue(
                                currentUser.getId(), this::onPlayerQueueEvent);
                        onWaiting.run();
                    }
                },
                onError);
    }

    public void cancelQueue(Runnable onCancelled, Consumer<Throwable> onError) {
        runAsync(() -> { serverConnection.cancelQueue(sessionToken); return null; },
                ignored -> {
                    closePlayerQueueSubscription();
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

    public GameStateDTO attachGameUpdateListener(Consumer<GameStateDTO> listener) {
        this.gameUpdateListener = listener;
        return currentGameState;
    }

    public void leaveGame() {
        gameUpdateListener = null;
        if (sessionTopicSubscription != null) {
            sessionTopicSubscription.close();
            sessionTopicSubscription = null;
        }
        currentGameState = null;
    }

    public void shutdown() {
        if (sessionToken != null) {
            serverConnection.logout(sessionToken);
            sessionToken = null;
        }
        backgroundExecutor.shutdownNow();
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
        }
    }

    private void startKeepAlive() {
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
        }
        keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "keep-alive");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = keepAliveInterval.toMillis();
        keepAliveExecutor.scheduleAtFixedRate(() -> {
            try {
                serverConnection.keepAlive(sessionToken);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "keepAlive failed", e);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void enterGame(GameStateDTO initialState) {
        currentGameState = initialState;
        sessionTopicSubscription = serverConnection.subscribeToSessionTopic(
                initialState.getSessionId(), this::onSessionTopicEvent);
    }

    private void onPlayerQueueEvent(GameEventDTO event) {
        if (event.getType() != GameEventType.MATCH_FOUND) {
            return;
        }
        Platform.runLater(() -> {
            if (playerQueueSubscription != null) {
                playerQueueSubscription.close();
                playerQueueSubscription = null;
            }
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

    private void closePlayerQueueSubscription() {
        if (playerQueueSubscription != null) {
            playerQueueSubscription.close();
            playerQueueSubscription = null;
        }
        pendingMatchCallback = null;
    }

    private <T> void runAsync(ThrowingSupplier<T> action, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        backgroundExecutor.submit(() -> {
            try {
                T result = action.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e));
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
