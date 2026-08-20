package com.matchmaker.admin.logic;

import com.matchmaker.admin.communication.AdminConnection;
import com.matchmaker.admin.communication.Subscription;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import javafx.application.Platform;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AdminClientService {

    private final AdminConnection adminConnection;
    private final Duration keepAliveInterval;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "admin-communication");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledExecutorService keepAliveExecutor;

    private UserDTO currentUser;
    private String sessionToken;
    private Subscription sessionSubscription;

    public AdminClientService(AdminConnection adminConnection) {
        this(adminConnection, Duration.ofSeconds(15));
    }

    AdminClientService(AdminConnection adminConnection, Duration keepAliveInterval) {
        this.adminConnection = adminConnection;
        this.keepAliveInterval = keepAliveInterval;
    }

    public UserDTO getCurrentUser() {
        return currentUser;
    }

    public void login(String username, String password, Consumer<UserDTO> onSuccess, Runnable onNotAdmin,
                       Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.login(username, password),
                result -> {
                    if (!result.getUser().isAdmin()) {
                        // adminConnection.login() opens the JMS connection as part of logging
                        // in, so by now a non-admin already holds an authenticated broker
                        // connection. Server-side authorization still rejects them everywhere
                        // that matters -- this is about not leaving the socket open.
                        adminConnection.logout(result.getSessionToken());
                        onNotAdmin.run();
                        return;
                    }
                    currentUser = result.getUser();
                    sessionToken = result.getSessionToken();
                    startKeepAlive();
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
        // Replacing the field without closing what it held would leave the old consumer
        // attached to the broker, still receiving a session nobody is watching.
        stopMonitoring();
        sessionSubscription = adminConnection.subscribeToSessionTopic(sessionId, onEvent::accept);
    }

    public void stopMonitoring() {
        if (sessionSubscription != null) {
            sessionSubscription.close();
            sessionSubscription = null;
        }
    }

    /** Mirrors {@code GameClientService.shutdown()} -- see the note there on running inline. */
    public void shutdown() {
        if (sessionToken != null) {
            adminConnection.logout(sessionToken);
            sessionToken = null;
        }
        stopMonitoring();
        backgroundExecutor.shutdownNow();
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
        }
    }

    private void startKeepAlive() {
        // See GameClientService.startKeepAlive(): a second login must not orphan the first loop.
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
                adminConnection.keepAlive(sessionToken);
            } catch (Exception e) {
                System.err.println("keepAlive failed: " + e.getMessage());
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
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
