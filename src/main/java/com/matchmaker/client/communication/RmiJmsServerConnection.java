package com.matchmaker.client.communication;

import com.matchmaker.common.communication.ServerEventListener;
import com.matchmaker.common.communication.Subscription;
import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameHistoryDTO;
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
import com.matchmaker.common.jms.JmsClientHandle;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.common.rmi.PlayerService;

import javax.jms.JMSException;
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
    private final JmsClientHandle jms = new JmsClientHandle();

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
            jms.connect(host, jmsPort, userId, sessionToken);
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
    public GameStateDTO resign(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotParticipantException {
        try {
            return playerService.resign(sessionToken, gameSessionId);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("resign() failed", e);
        }
    }

    @Override
    public void sendChatMessage(String sessionToken, int gameSessionId, String content)
            throws AuthenticationException, NotParticipantException {
        try {
            playerService.sendChatMessage(sessionToken, gameSessionId, content);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("sendChatMessage() failed", e);
        }
    }

    @Override
    public List<ChatMessageDTO> getChatHistory(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotParticipantException {
        try {
            return playerService.getChatHistory(sessionToken, gameSessionId);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("getChatHistory() failed", e);
        }
    }

    @Override
    public GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws AuthenticationException, NotParticipantException, AlreadyInGameException {
        try {
            return playerService.rematch(sessionToken, finishedSessionId);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("rematch() failed", e);
        }
    }

    @Override
    public List<GameHistoryDTO> getHistory(String sessionToken) throws AuthenticationException {
        try {
            return playerService.getHistory(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("getHistory() failed", e);
        }
    }

    @Override
    public List<UserDTO> listLeaderboard(String sessionToken) throws AuthenticationException {
        try {
            return playerService.listLeaderboard(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("listLeaderboard() failed", e);
        }
    }

    @Override
    public UserDTO getProfile(String sessionToken) throws AuthenticationException {
        try {
            return playerService.getProfile(sessionToken);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("getProfile() failed", e);
        }
    }

    @Override
    public UserDTO getOpponentProfile(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotParticipantException {
        try {
            return playerService.getOpponentProfile(sessionToken, gameSessionId);
        } catch (RemoteException e) {
            throw new ServerCommunicationException("getOpponentProfile() failed", e);
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

    @Override
    public void close() {
        jms.close(LOG);
    }

    private Subscription subscribe(JmsClientHandle.DestinationFactory destinationFactory, ServerEventListener listener) {
        try {
            return jms.subscribe(destinationFactory, listener, LOG);
        } catch (JMSException e) {
            throw new ServerCommunicationException("Failed to subscribe", e);
        }
    }
}
