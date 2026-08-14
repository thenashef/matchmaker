package com.matchmaker.client.logic;

import com.matchmaker.client.communication.ServerConnection;
import com.matchmaker.client.communication.Subscription;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class GameClientService {

    private final ServerConnection serverConnection;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "server-communication");
        thread.setDaemon(true);
        return thread;
    });

    private UserDTO currentUser;
    private String sessionToken;

    private Subscription playerQueueSubscription;
    private Consumer<GameStateDTO> pendingMatchCallback;

    private Subscription sessionTopicSubscription;
    private volatile GameStateDTO currentGameState;
    private volatile Consumer<GameStateDTO> gameUpdateListener;

    public GameClientService(ServerConnection serverConnection) {
        this.serverConnection = serverConnection;
    }

    public UserDTO getCurrentUser() {
        return currentUser;
    }

    public void login(String username, String password, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> serverConnection.login(username, password),
                result -> {
                    currentUser = result.getUser();
                    sessionToken = result.getSessionToken();
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

    /** Registers for live session-topic pushes and returns whatever the latest known state is,
     *  so the caller (GameBoardController) can render immediately even if it attaches slightly
     *  after enterGame() already subscribed -- nothing published in that gap is missed. */
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
        backgroundExecutor.shutdownNow();
    }

    /** Subscribes to the session's topic the instant a session id is known -- before returning
     *  control to any caller -- per the design doc's Queue-vs-Topic delivery-guarantee note:
     *  a Topic gives no retention to a late subscriber, so this must happen first, always. */
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
            // Deliberately not closePlayerQueueSubscription() here -- that helper also clears
            // pendingMatchCallback, which is still needed a few lines below to actually fire the
            // match. Only the subscription itself needs closing at this point.
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
