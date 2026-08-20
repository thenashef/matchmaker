package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.dto.GameTypeDTO;
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

    /**
     * Joins the matchmaking queue for the given game type.
     *
     * <p>Returns {@code null} if no waiting opponent was found — the caller is now queued
     * and waiting for someone else to join. Returns a non-null {@link GameStateDTO} if an
     * opponent was already waiting: the two players were matched immediately and this call
     * created the {@code GameSession}, which is returned directly to the caller.
     *
     * <p>Note the asymmetry this creates: the player who was <em>already</em> queued (and
     * whose own {@code joinQueue()} call already returned {@code null}) has no way to learn
     * that a match happened from this call alone — their {@code joinQueue()} invocation is
     * long over by the time the match occurs. Closing that gap is exactly what JMS
     * (roadmap step 6) will add: a server-pushed notification to the already-waiting player
     * the moment they're paired.
     */
    GameStateDTO joinQueue(String sessionToken, int gameTypeId)
        throws RemoteException, AuthenticationException, AlreadyInGameException;

    void cancelQueue(String sessionToken)
        throws RemoteException, AuthenticationException;

    GameStateDTO makeMove(String sessionToken, int gameSessionId, String movePayload)
        throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException, IllegalMoveException;

    /**
     * Given a partial move (possibly {@code {"path":[]}} for "nothing picked yet"), returns
     * every legal way to extend it by exactly one more step, each as a full move-payload JSON
     * string in the same shape {@code movePayload} already has -- read-only, doesn't touch
     * game state. An empty result means the given partial move is already complete (or the
     * session has no legal moves left from here).
     */
    List<String> legalContinuations(String sessionToken, int gameSessionId, String partialMovePayload)
        throws RemoteException, AuthenticationException, NotParticipantException, NotYourTurnException;

    void sendChatMessage(String sessionToken, int gameSessionId, String content)
        throws RemoteException, AuthenticationException, NotParticipantException;

    void resign(String sessionToken, int gameSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    GameStateDTO rematch(String sessionToken, int finishedSessionId)
        throws RemoteException, AuthenticationException, NotParticipantException;

    List<GameStateDTO> getHistory(String sessionToken)
        throws RemoteException, AuthenticationException;
}
