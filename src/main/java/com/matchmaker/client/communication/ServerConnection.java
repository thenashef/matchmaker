package com.matchmaker.client.communication;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.util.List;

public interface ServerConnection {

    UserDTO register(String username, String password) throws UsernameTakenException;

    LoginResultDTO login(String username, String password) throws AuthenticationException;

    List<GameTypeDTO> listGameTypes(String sessionToken) throws AuthenticationException;

    GameStateDTO joinQueue(String sessionToken, int gameTypeId) throws AuthenticationException;

    void cancelQueue(String sessionToken) throws AuthenticationException;

    GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
            throws AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

    Subscription subscribeToPlayerQueue(int userId, ServerEventListener listener);

    Subscription subscribeToSessionTopic(int sessionId, ServerEventListener listener);
}
