package com.matchmaker.server.rmi;

import com.matchmaker.common.exceptions.InvalidRegistrationException;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

final class UserValidation {

    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_BYTES = 72;

    private UserValidation() {
    }

    static String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    static boolean passwordMatches(String password, String passwordHash) {
        return BCrypt.checkpw(password, passwordHash);
    }

    static void validateRegistration(String username, String password, boolean isAdmin)
            throws InvalidRegistrationException {
        if (username == null || username.isBlank()) {
            throw new InvalidRegistrationException("Username can't be empty.");
        }
        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
            throw new InvalidRegistrationException(
                    "Username must be between " + MIN_USERNAME_LENGTH + " and " + MAX_USERNAME_LENGTH
                            + " characters.");
        }
        if (!username.equals(username.strip())) {
            throw new InvalidRegistrationException("Username can't start or end with a space.");
        }
        int minPasswordLength = isAdmin ? MIN_ADMIN_PASSWORD_LENGTH : MIN_PASSWORD_LENGTH;
        if (password == null || password.length() < minPasswordLength) {
            throw new InvalidRegistrationException(
                    "Password must be at least " + minPasswordLength + " characters"
                            + (isAdmin ? " for an admin account." : "."));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRegistrationException("Password must be at most " + MAX_PASSWORD_BYTES + " bytes.");
        }
    }
}
