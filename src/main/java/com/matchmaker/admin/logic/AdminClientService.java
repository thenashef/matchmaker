package com.matchmaker.admin.logic;

import com.matchmaker.admin.communication.AdminConnection;
import com.matchmaker.common.communication.Subscription;
import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.fx.FxAsync;
import javafx.application.Platform;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AdminClientService {

    private static final Logger LOG = Logger.getLogger(AdminClientService.class.getName());

    private final AdminConnection adminConnection;
    private final Duration keepAliveInterval;
    private final ExecutorService backgroundExecutor = FxAsync.daemonExecutor("admin-communication");
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

    public void listOnlineUsers(Consumer<List<UserDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listOnlineUsers(sessionToken), onSuccess, onError);
    }

    public void promoteToAdmin(int userId, Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.promoteToAdmin(sessionToken, userId), onSuccess, onError);
    }

    public void createUser(String username, String password, boolean isAdmin,
                            Consumer<UserDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.createUser(sessionToken, username, password, isAdmin), onSuccess, onError);
    }

    public void listActiveSessions(Consumer<List<GameStateDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listActiveSessions(sessionToken), onSuccess, onError);
    }

    public void forceEndSession(int gameSessionId, Runnable onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> { adminConnection.forceEndSession(sessionToken, gameSessionId); return null; },
                ignored -> onSuccess.run(), onError);
    }

    public void getDashboardStats(Consumer<AdminDashboardStatsDTO> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.getDashboardStats(sessionToken), onSuccess, onError);
    }

    public void listMoves(int gameSessionId, Consumer<List<MoveDTO>> onSuccess, Consumer<Throwable> onError) {
        runAsync(() -> adminConnection.listMoves(sessionToken, gameSessionId), onSuccess, onError);
    }

    public void monitorSession(int sessionId, Consumer<GameEventDTO> onEvent) {
        stopMonitoring();
        sessionSubscription = adminConnection.subscribeToSessionTopic(sessionId,
                event -> Platform.runLater(() -> onEvent.accept(event)));
    }

    public void stopMonitoring() {
        if (sessionSubscription != null) {
            sessionSubscription.close();
            sessionSubscription = null;
        }
    }

    /** Ends the current session so another account can log in on this client. Leaves the
     *  connection and background executor running. */
    public void logout() {
        stopMonitoring();
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
            keepAliveExecutor = null;
        }
        if (sessionToken != null) {
            try {
                adminConnection.logout(sessionToken);
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

    private void startKeepAlive() {
        if (keepAliveExecutor != null) {
            keepAliveExecutor.shutdownNow();
        }
        keepAliveExecutor = FxAsync.startKeepAlive(keepAliveInterval,
                () -> adminConnection.keepAlive(sessionToken), LOG);
    }

    private <T> void runAsync(FxAsync.ThrowingSupplier<T> action, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        FxAsync.run(backgroundExecutor, action, onSuccess, onError);
    }
}
