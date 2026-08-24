package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.dao.UserRecord;
import com.matchmaker.server.game.GameEngineRegistry;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsPublishException;
import com.matchmaker.server.matchmaking.MatchmakingQueue;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AdminServiceImpl extends UnicastRemoteObject implements AdminService {

    private static final Logger LOG = Logger.getLogger(AdminServiceImpl.class.getName());

    private final SessionManager sessionManager;
    private final UserDao userDao;
    private final GameTypeDao gameTypeDao;
    private final GameSessionDao gameSessionDao;
    private final GameEventPublisher gameEventPublisher;
    private final MatchmakingQueue matchmakingQueue;
    private final GameEngineRegistry gameEngines;
    private final Duration onlineLivenessWindow;

    public AdminServiceImpl(SessionManager sessionManager, UserDao userDao, GameTypeDao gameTypeDao,
                             GameSessionDao gameSessionDao, GameEventPublisher gameEventPublisher,
                             MatchmakingQueue matchmakingQueue, GameEngineRegistry gameEngines,
                             Duration onlineLivenessWindow)
            throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.userDao = userDao;
        this.gameTypeDao = gameTypeDao;
        this.gameSessionDao = gameSessionDao;
        this.gameEventPublisher = gameEventPublisher;
        this.matchmakingQueue = matchmakingQueue;
        this.gameEngines = gameEngines;
        this.onlineLivenessWindow = onlineLivenessWindow;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        if (!gameEngines.isRegistered(newGameType.getName())) {
            throw new IllegalArgumentException(
                    "No game engine is registered for '" + newGameType.getName() + "'");
        }
        return gameTypeDao.insert(newGameType);
    }

    @Override
    public List<UserDTO> listUsers(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return userDao.findAll().stream().map(UserRecord::toUserDTO).toList();
    }

    @Override
    public List<UserDTO> listOnlineUsers(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return sessionManager.onlineUserIds(onlineLivenessWindow).stream()
                .map(userDao::findById)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(UserRecord::username, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(UserRecord::id))
                .map(UserRecord::toUserDTO)
                .toList();
    }

    @Override
    public UserDTO promoteToAdmin(String sessionToken, int userId)
            throws RemoteException, AuthenticationException, NotAdminException {
        UserRecord actingAdmin = requireAdmin(sessionToken);
        UserRecord existing = userDao.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("No such user " + userId));
        if (existing.admin()) {
            return existing.toUserDTO();
        }
        UserRecord updated = userDao.setAdmin(userId, true)
                .orElseThrow(() -> new IllegalArgumentException("No such user " + userId));
        LOG.log(Level.INFO, "User '" + updated.username() + "' promoted to admin by admin user " + actingAdmin.id());
        return updated.toUserDTO();
    }

    @Override
    public UserDTO createUser(String sessionToken, String username, String password, boolean isAdmin)
            throws RemoteException, AuthenticationException, NotAdminException, UsernameTakenException,
                   InvalidRegistrationException {
        UserRecord creatingAdmin = requireAdmin(sessionToken);
        UserValidation.validateRegistration(username, password, isAdmin);
        String passwordHash = UserValidation.hashPassword(password);
        Optional<UserRecord> inserted = userDao.insert(username, passwordHash, isAdmin);
        if (inserted.isEmpty()) {
            throw new UsernameTakenException("Username '" + username + "' is already taken");
        }
        if (isAdmin) {
            LOG.log(Level.INFO, "Admin account '" + username + "' created by admin user " + creatingAdmin.id());
        }
        return inserted.get().toUserDTO();
    }

    @Override
    public List<GameStateDTO> listActiveSessions(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return gameSessionDao.findAllActive();
    }

    @Override
    public void forceEndSession(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        gameSessionDao.forceEnd(gameSessionId).ifPresent(ended ->
                publishToSessionQuietly(gameSessionId,
                        new GameEventDTO(GameEventType.SESSION_FORCE_ENDED, gameSessionId, ended),
                        "force-end"));
    }

    @Override
    public AdminDashboardStatsDTO getDashboardStats(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        Set<Integer> adminIds = userDao.findAdminUserIds();
        long onlinePlayers = sessionManager.onlineUserIds(onlineLivenessWindow).stream()
                .filter(userId -> !adminIds.contains(userId))
                .count();
        return new AdminDashboardStatsDTO((int) onlinePlayers, gameSessionDao.countActive(),
                gameSessionDao.countStartedToday(), matchmakingQueue.countWaiting());
    }

    @Override
    public List<MoveDTO> listMoves(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return gameSessionDao.findMovesForSession(gameSessionId);
    }

    private UserRecord requireAdmin(String sessionToken) throws AuthenticationException, NotAdminException {
        int userId = sessionManager.resolve(sessionToken);
        UserRecord record = userDao.findById(userId)
                .orElseThrow(() -> new NotAdminException("User " + userId + " no longer exists"));
        if (!record.admin()) {
            throw new NotAdminException("User " + userId + " is not an admin");
        }
        return record;
    }

    private void publishToSessionQuietly(int sessionId, GameEventDTO event, String what) {
        try {
            gameEventPublisher.publishToSession(sessionId, event);
        } catch (JmsPublishException e) {
            LOG.log(Level.WARNING, "Failed to notify session " + sessionId + " of " + what, e);
        }
    }
}
