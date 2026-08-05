package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
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

    void joinQueue(String sessionToken, int gameTypeId)
        throws RemoteException, AuthenticationException;

    void cancelQueue(String sessionToken)
        throws RemoteException, AuthenticationException;

    GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
        throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

    void sendChatMessage(String sessionToken, int gameSessionId, String content)
        throws RemoteException, AuthenticationException, NotParticipantException;

    void resign(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    GameStateDTO rematch(String sessionToken, int finishedSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    List<GameStateDTO> getHistory(String sessionToken)
        throws RemoteException, AuthenticationException;
}
