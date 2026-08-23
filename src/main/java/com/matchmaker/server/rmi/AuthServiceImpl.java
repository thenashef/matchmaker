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

    @Override
    public UserDTO register(String username, String password)
            throws RemoteException, UsernameTakenException, InvalidRegistrationException {
        UserValidation.validateRegistration(username, password, false);
        String passwordHash = UserValidation.hashPassword(password);
        Optional<UserRecord> inserted = userDao.insert(username, passwordHash);
        if (inserted.isEmpty()) {
            throw new UsernameTakenException("Username '" + username + "' is already taken");
        }
        return inserted.get().toUserDTO();
    }

    @Override
    public LoginResultDTO login(String username, String password) throws RemoteException, AuthenticationException {
        Optional<UserRecord> found = userDao.findByUsername(username);
        if (found.isEmpty() || !UserValidation.passwordMatches(password, found.get().passwordHash())) {
            throw new AuthenticationException("Invalid username or password");
        }
        UserRecord record = found.get();
        String token = sessionManager.createSession(record.id());
        return new LoginResultDTO(record.toUserDTO(), token);
    }

    @Override
    public void keepAlive(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
    }

    @Override
    public void logout(String sessionToken) throws RemoteException {
        sessionManager.invalidate(sessionToken);
    }
}
