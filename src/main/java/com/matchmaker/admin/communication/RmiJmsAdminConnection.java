package com.matchmaker.admin.communication;

import com.matchmaker.common.communication.ServerEventListener;
import com.matchmaker.common.communication.Subscription;
import com.matchmaker.common.dto.AdminDashboardStatsDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.MoveDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.jms.JmsClientHandle;
import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.common.rmi.AuthService;

import javax.jms.JMSException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RmiJmsAdminConnection implements AdminConnection {

    private static final Logger LOG = Logger.getLogger(RmiJmsAdminConnection.class.getName());

    private final AuthService authService;
    private final AdminService adminService;
    private final String host;
    private final int jmsPort;
    private final JmsClientHandle jms = new JmsClientHandle();

    public RmiJmsAdminConnection(String host, int rmiPort, int jmsPort) {
        this.host = host;
        this.jmsPort = jmsPort;
        try {
            Registry registry = LocateRegistry.getRegistry(host, rmiPort);
            authService = (AuthService) registry.lookup("AuthService");
            adminService = (AdminService) registry.lookup("AdminService");
        } catch (RemoteException | NotBoundException e) {
            throw new AdminCommunicationException("Failed to connect to RMI registry at " + host + ":" + rmiPort, e);
        }
    }

    private void connectJms(int userId, String sessionToken) {
        try {
            jms.connect(host, jmsPort, userId, sessionToken);
        } catch (JMSException e) {
            throw new AdminCommunicationException("Failed to connect to JMS broker at " + host + ":" + jmsPort, e);
        }
    }

    @Override
    public LoginResultDTO login(String username, String password) throws AuthenticationException {
        LoginResultDTO result;
        try {
            result = authService.login(username, password);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("login() failed", e);
        }
        connectJms(result.getUser().getId(), result.getSessionToken());
        return result;
    }

    @Override
    public void keepAlive(String sessionToken) throws AuthenticationException {
        try {
            authService.keepAlive(sessionToken);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("keepAlive() failed", e);
        }
    }

    @Override
    public void logout(String sessionToken) {
        try {
            authService.logout(sessionToken);
        } catch (RemoteException e) {
            LOG.log(Level.WARNING, "logout() failed", e);
        }
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException, NotAdminException {
        try {
            return adminService.listGameTypes(sessionToken);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("listGameTypes() failed", e);
        }
    }

    @Override
    public GameTypeDTO addGameType(String sessionToken, GameTypeDTO newGameType)
            throws AuthenticationException, NotAdminException {
        try {
            return adminService.addGameType(sessionToken, newGameType);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("addGameType() failed", e);
        }
    }

    @Override
    public List<UserDTO> listUsers(String sessionToken) throws AuthenticationException, NotAdminException {
        try {
            return adminService.listUsers(sessionToken);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("listUsers() failed", e);
        }
    }

    @Override
    public List<UserDTO> listOnlineUsers(String sessionToken) throws AuthenticationException, NotAdminException {
        try {
            return adminService.listOnlineUsers(sessionToken);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("listOnlineUsers() failed", e);
        }
    }

    @Override
    public UserDTO promoteToAdmin(String sessionToken, int userId) throws AuthenticationException, NotAdminException {
        try {
            return adminService.promoteToAdmin(sessionToken, userId);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("promoteToAdmin() failed", e);
        }
    }

    @Override
    public UserDTO createUser(String sessionToken, String username, String password, boolean isAdmin)
            throws AuthenticationException, NotAdminException, UsernameTakenException, InvalidRegistrationException {
        try {
            return adminService.createUser(sessionToken, username, password, isAdmin);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("createUser() failed", e);
        }
    }

    @Override
    public List<GameStateDTO> listActiveSessions(String sessionToken)
            throws AuthenticationException, NotAdminException {
        try {
            return adminService.listActiveSessions(sessionToken);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("listActiveSessions() failed", e);
        }
    }

    @Override
    public void forceEndSession(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotAdminException {
        try {
            adminService.forceEndSession(sessionToken, gameSessionId);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("forceEndSession() failed", e);
        }
    }

    @Override
    public AdminDashboardStatsDTO getDashboardStats(String sessionToken)
            throws AuthenticationException, NotAdminException {
        try {
            return adminService.getDashboardStats(sessionToken);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("getDashboardStats() failed", e);
        }
    }

    @Override
    public List<MoveDTO> listMoves(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotAdminException {
        try {
            return adminService.listMoves(sessionToken, gameSessionId);
        } catch (RemoteException e) {
            throw new AdminCommunicationException("listMoves() failed", e);
        }
    }

    @Override
    public synchronized Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        try {
            return jms.subscribe(session -> session.createTopic("session." + sessionId + ".events"), listener, LOG);
        } catch (JMSException e) {
            throw new AdminCommunicationException("Failed to subscribe to session " + sessionId, e);
        }
    }

    @Override
    public void close() {
        jms.close(LOG);
    }
}
