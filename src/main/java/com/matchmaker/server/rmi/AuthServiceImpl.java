package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.dao.UserRecord;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Optional;

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {

    private final SessionManager sessionManager;
    private final UserDao userDao;

    public AuthServiceImpl(SessionManager sessionManager, UserDao userDao) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
        this.userDao = userDao;
    }

    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_BYTES = 72;

    @Override
    public UserDTO register(String username, String password)
            throws RemoteException, UsernameTakenException, InvalidRegistrationException {
        validateRegistration(username, password);
        String passwordHash = BCrypt.hashpw(password, BCrypt.gensalt());
        Optional<UserRecord> inserted = userDao.insert(username, passwordHash);
        if (inserted.isEmpty()) {
            throw new UsernameTakenException("Username '" + username + "' is already taken");
        }
        return toUserDTO(inserted.get());
    }

    @Override
    public LoginResultDTO login(String username, String password) throws RemoteException, AuthenticationException {
        Optional<UserRecord> found = userDao.findByUsername(username);
        if (found.isEmpty() || !BCrypt.checkpw(password, found.get().passwordHash())) {
            throw new AuthenticationException("Invalid username or password");
        }
        UserRecord record = found.get();
        String token = sessionManager.createSession(record.id());
        return new LoginResultDTO(toUserDTO(record), token);
    }

    @Override
    public void keepAlive(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
    }

    @Override
    public void logout(String sessionToken) throws RemoteException {
        sessionManager.invalidate(sessionToken);
    }

    private static void validateRegistration(String username, String password)
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
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidRegistrationException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidRegistrationException(
                    "Password must be at most " + MAX_PASSWORD_BYTES + " bytes.");
        }
    }

    private static UserDTO toUserDTO(UserRecord record) {
        return new UserDTO(record.id(), record.username(), record.admin(),
                record.wins(), record.losses(), record.draws(), record.rating());
    }
}
