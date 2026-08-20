package com.matchmaker.common.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakerExceptionHierarchyTest {

    @Test
    void matchmakerException_carriesMessage_andIsAnException() {
        MatchmakerException ex = new MatchmakerException("base");
        assertEquals("base", ex.getMessage());
        assertTrue(ex instanceof Exception);
    }

    @Test
    void authenticationException_carriesMessage_andExtendsBase() {
        AuthenticationException ex = new AuthenticationException("bad credentials");
        assertEquals("bad credentials", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void usernameTakenException_carriesMessage_andExtendsBase() {
        UsernameTakenException ex = new UsernameTakenException("username exists");
        assertEquals("username exists", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void notParticipantException_carriesMessage_andExtendsBase() {
        NotParticipantException ex = new NotParticipantException("not a participant");
        assertEquals("not a participant", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void notYourTurnException_carriesMessage_andExtendsBase() {
        NotYourTurnException ex = new NotYourTurnException("not your turn");
        assertEquals("not your turn", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void illegalMoveException_carriesMessage_andExtendsBase() {
        IllegalMoveException ex = new IllegalMoveException("illegal move");
        assertEquals("illegal move", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void alreadyInGameException_carriesMessage_andExtendsBase() {
        AlreadyInGameException ex = new AlreadyInGameException("already playing");
        assertEquals("already playing", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void invalidRegistrationException_carriesMessage_andExtendsBase() {
        InvalidRegistrationException ex = new InvalidRegistrationException("username too short");
        assertEquals("username too short", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }

    @Test
    void notAdminException_carriesMessage_andExtendsBase() {
        NotAdminException ex = new NotAdminException("not an admin");
        assertEquals("not an admin", ex.getMessage());
        assertTrue(ex instanceof MatchmakerException);
    }
}
