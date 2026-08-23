package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.rmi.PlayerService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.ChatMessageDao;
import com.matchmaker.server.dao.ConcurrentGameUpdateException;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.dao.UserRecord;
import com.matchmaker.server.game.GameEngine;
import com.matchmaker.server.game.GameResult;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsPublishException;
import com.matchmaker.server.matchmaking.MatchmakingQueue;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PlayerServiceImpl extends UnicastRemoteObject implements PlayerService {

    private static final Logger LOG = Logger.getLogger(PlayerServiceImpl.class.getName());
    private static final int MAX_CHAT_MESSAGE_LENGTH = 500;
    private static final Duration OPPONENT_RECENTLY_ACTIVE_WINDOW = Duration.ofSeconds(30);

    private final SessionManager sessionManager;
    private final GameSessionDao gameSessionDao;
    private final GameTypeDao gameTypeDao;
    private final MatchmakingQueue matchmakingQueue;
    private final GameEventPublisher gameEventPublisher;
    private final GameEngine gameEngine;
    private final ChatMessageDao chatMessageDao;
    private final UserDao userDao;

    public PlayerServiceImpl(SessionManager sessionManager, GameSessionDao gameSessionDao, GameTypeDao gameTypeDao,
                              MatchmakingQueue matchmakingQueue, GameEventPublisher gameEventPublisher,
                              GameEngine gameEngine, ChatMessageDao chatMessageDao, UserDao userDao)
            throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.gameSessionDao = gameSessionDao;
        this.gameTypeDao = gameTypeDao;
        this.matchmakingQueue = matchmakingQueue;
        this.gameEventPublisher = gameEventPublisher;
        this.gameEngine = gameEngine;
        this.chatMessageDao = chatMessageDao;
        this.userDao = userDao;
    }

    @Override
    public List<GameTypeDTO> listGameTypes(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
        return gameTypeDao.findAll();
    }

    @Override
    public GameStateDTO joinQueue(String sessionToken, int gameTypeId)
            throws RemoteException, AuthenticationException, AlreadyInGameException {
        int userId = sessionManager.resolve(sessionToken);
        GameStateDTO result = matchmakingQueue.join(userId, gameTypeId, gameEngine.initialState());

        if (result != null) {
            Integer opponentUserId;
            if (result.getPlayer1Id() == userId) {
                opponentUserId = result.getPlayer2Id();
            } else if (result.getPlayer2Id() == userId) {
                opponentUserId = result.getPlayer1Id();
            } else {
                opponentUserId = null;
                LOG.log(Level.WARNING, "joinQueue: matched session " + result.getSessionId()
                        + " does not include caller " + userId + " as either player -- skipping opponent notification");
            }

            if (opponentUserId != null) {
                try {
                    gameEventPublisher.publishToPlayer(opponentUserId,
                            new GameEventDTO(GameEventType.MATCH_FOUND, result.getSessionId(), result));
                } catch (JmsPublishException e) {
                    LOG.log(Level.WARNING, "Failed to notify opponent " + opponentUserId + " of match", e);
                }
            }
        }

        return result;
    }

    @Override
    public void cancelQueue(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        matchmakingQueue.cancel(userId);
    }

    @Override
    public GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException {
        int userId = sessionManager.resolve(sessionToken);

        GameStateDTO session = gameSessionDao.findActiveById(gameSessionId)
                .orElseThrow(() -> new IllegalMoveException("No active game session " + gameSessionId));

        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }
        if (session.getCurrentTurnUserId() == null || session.getCurrentTurnUserId() != userId) {
            throw new NotYourTurnException("It is not user " + userId + "'s turn in session " + gameSessionId);
        }

        String currentBoardState = session.getBoardState();
        boolean isPlayer1Turn = session.getPlayer1Id() == userId;
        if (!gameEngine.isLegalMove(currentBoardState, isPlayer1Turn, movePayload)) {
            throw new IllegalMoveException("Illegal move for session " + gameSessionId + ": " + movePayload);
        }

        String newBoardState = gameEngine.applyMove(currentBoardState, isPlayer1Turn, movePayload);
        GameResult result = gameEngine.checkWinner(newBoardState, !isPlayer1Turn);

        int opponentId = isPlayer1Turn ? session.getPlayer2Id() : session.getPlayer1Id();
        GameStateDTO updatedSession;
        if (result == GameResult.CONTINUE) {
            updatedSession = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                    session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.ACTIVE, opponentId, null, newBoardState);
        } else {
            Integer winnerId = result == GameResult.PLAYER1_WINS ? session.getPlayer1Id()
                    : result == GameResult.PLAYER2_WINS ? session.getPlayer2Id() : null;
            updatedSession = new GameStateDTO(session.getSessionId(), session.getGameTypeId(),
                    session.getPlayer1Id(), session.getPlayer2Id(), GameStatus.FINISHED, null, winnerId, newBoardState);
        }

        GameStateDTO persistedSession;
        try {
            persistedSession = gameSessionDao.recordMove(updatedSession, userId, movePayload);
        } catch (ConcurrentGameUpdateException e) {
            throw new NotYourTurnException("Session " + gameSessionId + " changed since it was read -- "
                    + "it is no longer user " + userId + "'s turn (or the game already ended)");
        }

        try {
            gameEventPublisher.publishToSession(gameSessionId,
                    new GameEventDTO(GameEventType.MOVE_MADE, gameSessionId, persistedSession));
        } catch (JmsPublishException e) {
            LOG.log(Level.WARNING, "Failed to notify session " + gameSessionId + " of move", e);
        }

        return persistedSession;
    }

    @Override
    public List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayload)
            throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException {
        int userId = sessionManager.resolve(sessionToken);

        GameStateDTO session = gameSessionDao.findActiveById(gameSessionId)
                .orElseThrow(() -> new NotParticipantException("No active game session " + gameSessionId));

        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }
        if (session.getCurrentTurnUserId() == null || session.getCurrentTurnUserId() != userId) {
            throw new NotYourTurnException("It is not user " + userId + "'s turn in session " + gameSessionId);
        }

        boolean isPlayer1Turn = session.getPlayer1Id() == userId;
        return gameEngine.legalContinuations(session.getBoardState(), isPlayer1Turn, partialMovePayload);
    }

    @Override
    public void sendChatMessage(String sessionToken, int gameSessionId, String content)
            throws RemoteException, AuthenticationException, NotParticipantException {
        int userId = sessionManager.resolve(sessionToken);
        requireActiveParticipant(gameSessionId, userId);

        String safeContent = content == null ? "" : content;
        if (safeContent.length() > MAX_CHAT_MESSAGE_LENGTH) {
            safeContent = safeContent.substring(0, MAX_CHAT_MESSAGE_LENGTH);
        }

        chatMessageDao.insert(gameSessionId, userId, safeContent);
        try {
            gameEventPublisher.publishToSession(gameSessionId,
                    new GameEventDTO(GameEventType.CHAT_MESSAGE, gameSessionId, userId, safeContent));
        } catch (JmsPublishException e) {
            LOG.log(Level.WARNING, "Failed to notify session " + gameSessionId + " of chat message", e);
        }
    }

    @Override
    public List<ChatMessageDTO> getChatHistory(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        int userId = sessionManager.resolve(sessionToken);
        requireActiveParticipant(gameSessionId, userId);
        return chatMessageDao.findBySession(gameSessionId);
    }

    private void requireActiveParticipant(int gameSessionId, int userId) throws NotParticipantException {
        GameStateDTO session = gameSessionDao.findActiveById(gameSessionId)
                .orElseThrow(() -> new NotParticipantException("No active game session " + gameSessionId));
        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }
    }

    @Override
    public GameStateDTO resign(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        int userId = sessionManager.resolve(sessionToken);

        GameStateDTO session = gameSessionDao.findActiveById(gameSessionId)
                .orElseThrow(() -> new NotParticipantException("No active game session " + gameSessionId));

        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }

        int opponentId = session.getPlayer1Id() == userId ? session.getPlayer2Id() : session.getPlayer1Id();
        Optional<GameStateDTO> abandoned = gameSessionDao.abandon(gameSessionId, opponentId);
        if (abandoned.isEmpty()) {
            // Session already ended between the read above and this call (e.g. the opponent's
            // winning move landed first) -- resign is then a no-op, matching SessionWatchdog.abandon().
            // Return the real outcome rather than the caller's synthesized "I lost" guess: the caller
            // may in fact have won on the move that beat their resignation to the server.
            return gameSessionDao.findById(gameSessionId)
                    .orElseThrow(() -> new NotParticipantException("Session " + gameSessionId + " no longer exists"));
        }

        GameStateDTO finalState = abandoned.get();
        try {
            gameEventPublisher.publishToSession(gameSessionId,
                    new GameEventDTO(GameEventType.SESSION_ABANDONED, gameSessionId, finalState));
        } catch (JmsPublishException e) {
            LOG.log(Level.WARNING, "Failed to notify session " + gameSessionId + " of resignation", e);
        }
        return finalState;
    }

    @Override
    public GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException, AlreadyInGameException {
        int userId = sessionManager.resolve(sessionToken);

        GameStateDTO finished = gameSessionDao.findById(finishedSessionId)
                .orElseThrow(() -> new NotParticipantException("No such game session " + finishedSessionId));
        if (finished.getPlayer1Id() != userId && finished.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + finishedSessionId);
        }
        if (finished.getStatus() == GameStatus.ACTIVE) {
            throw new NotParticipantException("Session " + finishedSessionId + " is still active");
        }

        int opponentId = finished.getPlayer1Id() == userId ? finished.getPlayer2Id() : finished.getPlayer1Id();
        // onlineUserIds() requires both a currently-valid token and recent activity, so a cleanly
        // logged-out opponent is excluded immediately rather than staying "recently active" for
        // the rest of the liveness window (see SessionManager.invalidate).
        if (!sessionManager.onlineUserIds(OPPONENT_RECENTLY_ACTIVE_WINDOW).contains(opponentId)) {
            throw new AlreadyInGameException(
                    "Opponent isn't currently online -- matchmake a new game instead of a rematch");
        }

        GameStateDTO newSession = gameSessionDao.createRematch(finishedSessionId, gameEngine.initialState());

        int otherUserId = newSession.getPlayer1Id() == userId ? newSession.getPlayer2Id() : newSession.getPlayer1Id();
        try {
            gameEventPublisher.publishToPlayer(otherUserId,
                    new GameEventDTO(GameEventType.REMATCH_CREATED, newSession.getSessionId(), newSession));
        } catch (JmsPublishException e) {
            LOG.log(Level.WARNING, "Failed to notify " + otherUserId + " of rematch", e);
        }

        return newSession;
    }

    @Override
    public List<GameStateDTO> getHistory(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        return gameSessionDao.findFinishedSessionsForUser(userId);
    }

    @Override
    public UserDTO getProfile(String sessionToken) throws RemoteException, AuthenticationException {
        int userId = sessionManager.resolve(sessionToken);
        UserRecord record = userDao.findById(userId)
                .orElseThrow(() -> new AuthenticationException("User " + userId + " no longer exists"));
        return toUserDTO(record);
    }

    @Override
    public UserDTO getOpponentProfile(String sessionToken, int gameSessionId)
            throws RemoteException, AuthenticationException, NotParticipantException {
        int userId = sessionManager.resolve(sessionToken);
        GameStateDTO session = gameSessionDao.findById(gameSessionId)
                .orElseThrow(() -> new NotParticipantException("No such game session " + gameSessionId));
        if (session.getPlayer1Id() != userId && session.getPlayer2Id() != userId) {
            throw new NotParticipantException("User " + userId + " is not a participant in session " + gameSessionId);
        }
        int opponentId = session.getPlayer1Id() == userId ? session.getPlayer2Id() : session.getPlayer1Id();
        UserRecord record = userDao.findById(opponentId)
                .orElseThrow(() -> new NotParticipantException("Opponent " + opponentId + " no longer exists"));
        return toUserDTO(record);
    }

    private static UserDTO toUserDTO(UserRecord record) {
        return new UserDTO(record.id(), record.username(), record.admin(),
                record.wins(), record.losses(), record.draws(), record.rating());
    }
}
