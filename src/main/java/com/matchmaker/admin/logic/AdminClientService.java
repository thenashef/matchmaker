package com.matchmaker.admin.logic;

import com.matchmaker.admin.communication.AdminConnection;
import com.matchmaker.admin.communication.Subscription;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class AdminClientService {

    private final AdminConnection adminConnection;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "admin-communication");
        thread.setDaemon(true);
        return thread;
    });

    private UserDTO currentUser;
    private String sessionToken;
    private Subscription sessionSubscription;

    public AdminClientService(AdminConnection adminConnection) {
        this.adminConnection = adminConnection;
    }

    public UserDTO getCurrentUser() {
        return currentUser;
    }

    public void login(String username, String password, Consumer<UserDTO> onSuccess, Runnable onNotAdmin,
                       Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.login(username, password),
                result -> {
                    if (!result.getUser().isAdmin()) {
                        onNotAdmin.run();
                        return;
                    }
                    currentUser = result.getUser();
                    sessionToken = result.getSessionToken();
                    onSuccess.accept(result.getUser());
                },
                onError);
    }

    public void listGameTypes(Consumer<List<GameTypeDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listGameTypes(sessionToken), onSuccess, onError);
    }

    public void addGameType(GameTypeDTO newGameType, Consumer<GameTypeDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.addGameType(sessionToken, newGameType), onSuccess, onError);
    }

    public void listUsers(Consumer<List<UserDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listUsers(sessionToken), onSuccess, onError);
    }

    public void listActiveSessions(Consumer<List<GameStateDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listActiveSessions(sessionToken), onSuccess, onError);
    }

    public void forceEndSession(int gameSessionId, Runnable onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> { adminConnection.forceEndSession(sessionToken, gameSessionId); return null; },
                ignored -> onSuccess.run(), onError);
    }

    public void monitorSession(int sessionId, Consumer<GameEventDTO> onEvent) {
        sessionSubscription = adminConnection.subscribeToSessionTopic(sessionId, onEvent::accept);
    }

    public void stopMonitoring() {
        if (sessionSubscription != null) {
            sessionSubscription.close();
            sessionSubscription = null;
        }
    }

    public void shutdown() {
        backgroundExecutor.shutdownNow();
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
