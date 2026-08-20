package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.common.rmi.PlayerService;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RmiJmsServerConnection implements ServerConnection {

    private static final Logger LOG = Logger.getLogger(RmiJmsServerConnection.class.getName());

    private final AuthService authService;
    private final PlayerService playerService;
    private final String host;
    private final int jmsPort;
    private Connection jmsConnection;
    private Session jmsSession;

    public RmiJmsServerConnection(String host, int rmiPort, int jmsPort) {
        this.host = host;
        this.jmsPort = jmsPort;
        try {
            Registry registry = LocateRegistry.getRegistry(host, rmiPort);
            authService = (AuthService) registry.lookup("AuthService");
            playerService = (PlayerService) registry.lookup("PlayerService");
        } catch (RemoteException | NotBoundException e) {
            throw new ServerCommunicationException("Failed to connect to RMI registry at " + host + ":" + rmiPort, e);
        }
    }

    private void connectJms(int userId, String sessionToken) {
        try {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://" + host + ":" + jmsPort);
            factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));
            jmsConnection = factory.createConnection(String.valueOf(userId), sessionToken);
            jmsConnection.start();
            jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        } catch (JMSException e) {
            throw new ServerCommunicationException("Failed to connect to JMS broker at " + host + ":" + jmsPort, e);
        }
    }

    @Override
    public UserDTO register(String username, String password)
            throws UsernameTakenException, InvalidRegistrationException {
        try {
            return authService.register(username, password);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("register() failed", e);
        }
    }

    @Override
    public LoginResultDTO login(String username, String password) throws AuthenticationException {
        LoginResultDTO result;
        try {
            result = authService.login(username, password);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("login() failed", e);
        }
        connectJms(result.getUser().getId(), result.getSessionToken());
        return result;
    }

    @Override
    public void keepAlive(String sessionToken) throws AuthenticationException {
        try {
            authService.keepAlive(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("keepAlive() failed", e);
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
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException {
        try {
            return playerService.listGameTypes(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("listGameTypes() failed", e);
        }
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId)
            throws AuthenticationException, AlreadyInGameException {
        try {
            return playerService.joinQueue(sessionToken, gameTypeId);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("joinQueue() failed", e);
        }
    }

    @Override
    public void cancelQueue(String sessionToken) throws AuthenticationException {
        try {
            playerService.cancelQueue(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("cancelQueue() failed", e);
        }
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException {
        try {
            return playerService.makeMove(sessionToken, gameSessionId, movePayload);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("makeMove() failed", e);
        }
    }

    @Override
    public List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayload)
            throws AuthenticationException, NotParticipantException, NotYourTurnException {
        try {
            return playerService.legalContinuations(sessionToken, gameSessionId, partialMovePayload);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("legalContinuations() failed", e);
        }
    }

    @Override
    public Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener) {
        return subscribe(session -> session.createQueue("player." + userId + ".events"), listener);
    }

    @Override
    public Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener) {
        return subscribe(session -> session.createTopic("session." + sessionId + ".events"), listener);
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

    private synchronized Subscription subscribe(DestinationFactory destinationFactory, ServerEventListener listener) {
        try {
            Destination destination = destinationFactory.create(jmsSession);
            MessageConsumer consumer = jmsSession.createConsumer(destination);
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
            throw new ServerCommunicationException("Failed to subscribe", e);
        }
    }

    @FunctionalInterface
    private interface DestinationFactory {
        Destination create(Session session) throws JMSException;
    }
}
