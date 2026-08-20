package com.matchmaker.common.exceptions;

/**
 * Thrown by {@code AuthService.register} when the requested username or password doesn't meet
 * the account rules. Distinct from {@link UsernameTakenException}, which is about a name that
 * is well-formed but already in use.
 *
 * <p>Exists so the client has something it can show the user. Without it, an over-long
 * username reached the {@code VARCHAR(50)} column as-is and came back as a wrapped
 * {@code SQLException} — surfacing in the login screen's status label as an RMI
 * {@code ServerException} rather than "that name is too long."
 */
public class InvalidRegistrationException extends MatchmakerException {
    public InvalidRegistrationException(String message) {
        super(message);
    }
}
