package com.matchmaker.admin.communication;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.NotAdminException;
import com.matchmaker.common.rmi.AdminService;
import com.matchmaker.common.rmi.AuthService;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.Topic;
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
    private Connection jmsConnection;
    private Session jmsSession;

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

    // The broker requires an authenticated connection (see JmsSecurityPlugin), so the JMS
    // connection can't be opened until we have a session token to authenticate with -- it's
    // deferred here and opened right after a successful login, rather than in the constructor.
    private void connectJms(int userId, String sessionToken) {
        try {
            // Deliberately not shared with client.communication.RmiJmsServerConnection -- admin
            // stays fully independent of the player client, same reasoning as keeping both
            // independent of server.*.
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://" + host + ":" + jmsPort);
            factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));
            jmsConnection = factory.createConnection(String.valueOf(userId), sessionToken);
            jmsConnection.start();
            jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
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
            // Called from client shutdown, where there is no longer anywhere useful to report
            // this -- and the token ages out on its own regardless.
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
    public synchronized Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        try {
            Topic topic = jmsSession.createTopic("session." + sessionId + ".events");
            MessageConsumer consumer = jmsSession.createConsumer(topic);
            consumer.setMessageListener(message -> {
                try {
                    GameEventDTO event = (GameEventDTO) ((ObjectMessage) message).getObject();
                    listener.onEvent(event);
                } catch (JMSException e) {
                    LOG.log(Level.WARNING, "Failed to read a JMS event", e);
                }
            });
            return () -> {
                try {
                    consumer.close();
                } catch (JMSException e) {
                    LOG.log(Level.WARNING, "Failed to close a JMS subscription", e);
                }
            };
        } catch (JMSException e) {
            throw new AdminCommunicationException("Failed to subscribe to session " + sessionId, e);
        }
    }

    public void close() {
        if (jmsConnection == null) {
            return;
        }
        try {
            jmsConnection.close();
        } catch (JMSException e) {
            LOG.log(Level.WARNING, "Failed to close JMS connection", e);
        }
    }
}
