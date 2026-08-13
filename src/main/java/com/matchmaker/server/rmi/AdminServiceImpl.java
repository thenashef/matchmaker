package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.dao.UserRecord;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsPublishException;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class AdminServiceImpl extends UnicastRemoteObject implements AdminService {

    private final SessionManager sessionManager;
    private final UserDao userDao;
    private final GameTypeDao gameTypeDao;
    private final GameSessionDao gameSessionDao;
    private final GameEventPublisher gameEventPublisher;

    public AdminServiceImpl(SessionManager sessionManager, UserDao userDao, GameTypeDao gameTypeDao,
                             GameSessionDao gameSessionDao, GameEventPublisher gameEventPublisher)
            throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.userDao = userDao;
        this.gameTypeDao = gameTypeDao;
        this.gameSessionDao = gameSessionDao;
        this.gameEventPublisher = gameEventPublisher;
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
        return gameTypeDao.insert(newGameType);
    }

    @Override
    public List<UserDTO> listUsers(String sessionToken)
            throws RemoteException, AuthenticationException, NotAdminException {
        requireAdmin(sessionToken);
        return userDao.findAll().stream()
                .map(record -> new UserDTO(record.id(), record.username(), record.admin(),
                        record.wins(), record.losses(), record.draws(), record.rating()))
                .toList();
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
        gameSessionDao.forceEnd(gameSessionId).ifPresent(ended -> {
            try {
                gameEventPublisher.publishToSession(gameSessionId,
                        new GameEventDTO(GameEventType.SESSION_FORCE_ENDED, gameSessionId, ended));
            } catch (JmsPublishException e) {
                // The DB update already committed -- a failed notification shouldn't fail this
                // admin call. Mirrors PlayerServiceImpl.makeMove()'s identical handling.
                System.err.println("Failed to notify session " + gameSessionId + " of force-end: " + e.getMessage());
            }
        });
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
}
