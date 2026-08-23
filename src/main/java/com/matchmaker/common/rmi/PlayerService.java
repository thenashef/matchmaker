package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.ChatMessageDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AlreadyInGameException;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.IllegalMoveException;
import com.matchmaker.common.exceptions.NotParticipantException;
import com.matchmaker.common.exceptions.NotYourTurnException;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface PlayerService extends Remote {
    List<GameTypeDTO> listGameTypes(String sessionToken)
        throws RemoteException, AuthenticationException;

    /** @return the new session if paired immediately, or null if the caller was enqueued. */
    GameStateDTO joinQueue(String sessionToken, int gameTypeId)
        throws RemoteException, AuthenticationException, AlreadyInGameException;

    void cancelQueue(String sessionToken)
        throws RemoteException, AuthenticationException;

    GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
        throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

    List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayload)
        throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException;

    void sendChatMessage(String sessionToken, int gameSessionId, String content)
        throws RemoteException, AuthenticationException, NotParticipantException;

    List<ChatMessageDTO> getChatHistory(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    /** @return the authoritative final state of the session (which may credit the opponent even if
     *  the game had already ended a moment before this call landed). */
    GameStateDTO resign(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    GameStateDTO rematch(String sessionToken, int finishedSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException, AlreadyInGameException;

    List<GameStateDTO> getHistory(String sessionToken)
        throws RemoteException, AuthenticationException;

    /** Fresh copy of the caller's own profile (e.g. to pick up a rating change since login). */
    UserDTO getProfile(String sessionToken)
        throws RemoteException, AuthenticationException;

    /** The other participant's profile, for any session (active or finished) the caller played in. */
    UserDTO getOpponentProfile(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;
}
