package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.UsernameTakenException;
import com.matchmaker.common.rmi.AuthService;
import com.matchmaker.server.SessionManager;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {

    private static final int TEST_USER_ID = 1;
    private static final String TEST_USERNAME = "test";
    private static final String TEST_PASSWORD = "test1234";

    private final SessionManager sessionManager;

    public AuthServiceImpl(SessionManager sessionManager) throws RemoteException {
        super();
        this.sessionManager = sessionManager;
    }

    @Override
    public UserDTO register(String username, String password) throws RemoteException, UsernameTakenException {
        if (TEST_USERNAME.equals(username)) {
            throw new UsernameTakenException("Username '" + username + "' is already taken");
        }
        throw new UnsupportedOperationException(
            "register not implemented yet -- see build-plan.md step 4");
    }

    @Override
    public LoginResultDTO login(String username, String password) throws RemoteException, AuthenticationException {
        if (!TEST_USERNAME.equals(username) || !TEST_PASSWORD.equals(password)) {
            throw new AuthenticationException("Invalid username or password");
        }
        UserDTO user = new UserDTO(TEST_USER_ID, TEST_USERNAME, false, 0, 0, 0, 1200);
        String token = sessionManager.createSession(TEST_USER_ID);
        return new LoginResultDTO(user, token);
    }

    @Override
    public void keepAlive(String sessionToken) throws RemoteException, AuthenticationException {
        sessionManager.resolve(sessionToken);
    }
}
