package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
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

public class RmiJmsServerConnection implements ServerConnection {

    private final AuthService authService;
    private final PlayerService playerService;
    private final Connection jmsConnection;
    private final Session jmsSession;

    public RmiJmsServerConnection(String host, int rmiPort, int jmsPort) {
        try {
            Registry registry = LocateRegistry.getRegistry(host, rmiPort);
            authService = (AuthService) registry.lookup("AuthService");
            playerService = (PlayerService) registry.lookup("PlayerService");
        } catch (RemoteException | NotBoundException e) {
            throw new ServerCommunicationException("Failed to connect to RMI registry at " + host + ":" + rmiPort, e);
        }

        try {
            // Deliberately not reusing server.jms.JmsConnectionFactory -- client code never
            // imports from com.matchmaker.server.* (see the implementation plan's Global
            // Constraints), so the handful of lines it would have saved are duplicated instead.
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://" + host + ":" + jmsPort);
            factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));
            jmsConnection = factory.createConnection();
            jmsConnection.start();
            jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        } catch (JMSException e) {
            throw new ServerCommunicationException("Failed to connect to JMS broker at " + host + ":" + jmsPort, e);
        }
    }

    @Override
    public UserDTO register(String username, String password) throws UsernameTakenException {
        try {
            return authService.register(username, password);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("register() failed", e);
        }
    }

    @Override
    public LoginResultDTO login(String username, String password) throws AuthenticationException {
        try {
            return authService.login(username, password);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("login() failed", e);
        }
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
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException {
        try {
            return playerService.listGameTypes(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("listGameTypes() failed", e);
        }
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws AuthenticationException {
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
        try {
            jmsConnection.close();
        } catch (JMSException e) {
            System.err.println("Failed to close JMS connection: " + e.getMessage());
        }
    }

    // A javax.jms.Session may only be used by one thread at a time -- see the identical note on
    // ActiveMqGameEventPublisher, which this mirrors.
    private synchronized Subscription subscribe(DestinationFactory destinationFactory, ServerEventListener listener) {
        try {
            Destination destination = destinationFactory.create(jmsSession);
            MessageConsumer consumer = jmsSession.createConsumer(destination);
            consumer.setMessageListener(message -> {
                try {
                    GameEventDTO event = (GameEventDTO) ((ObjectMessage) message).getObject();
                    listener.onEvent(event);
                } catch (JMSException e) {
                    System.err.println("Failed to read a JMS event: " + e.getMessage());
                }
            });
            return () -> {
                try {
                    consumer.close();
                } catch (JMSException e) {
                    System.err.println("Failed to close a JMS subscription: " + e.getMessage());
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
