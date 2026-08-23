package com.matchmaker.client.communication;

import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.util.List;

public interface ServerConnection {

    UserDTO register(String username, String password)
            throws UsernameTakenException, InvalidRegistrationException;

    LoginResultDTO login(String username, String password) throws AuthenticationException;

    void keepAlive(String sessionToken) throws AuthenticationException;

    /** Revokes the session token server-side. Never throws on an unknown token. */
    void logout(String sessionToken);

    List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException;

    GameStateDTO joinQueue(String sessionToken, int gameTypeId)
            throws AuthenticationException, AlreadyInGameException;

    void cancelQueue(String sessionToken) throws AuthenticationException;

    GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

    List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayload)
            throws AuthenticationException, NotParticipantException, NotYourTurnException;

    GameStateDTO resign(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotParticipantException;

    void sendChatMessage(String sessionToken, int gameSessionId, String content)
            throws AuthenticationException, NotParticipantException;

    List<ChatMessageDTO> getChatHistory(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotParticipantException;

    GameStateDTO rematch(String sessionToken, int finishedSessionId)
            throws AuthenticationException, NotParticipantException, AlreadyInGameException;

    List<UserDTO> listLeaderboard(String sessionToken) throws AuthenticationException;

    UserDTO getProfile(String sessionToken) throws AuthenticationException;

    UserDTO getOpponentProfile(String sessionToken, int gameSessionId)
            throws AuthenticationException, NotParticipantException;

    Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener);

    Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener);
}
