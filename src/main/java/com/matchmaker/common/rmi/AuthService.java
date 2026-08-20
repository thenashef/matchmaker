package com.matchmaker.common.rmi;

import com.matchmaker.common.dto.LoginResultDTO;
import com.matchmaker.common.dto.UserDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.common.exceptions.InvalidRegistrationException;
import com.matchmaker.common.exceptions.UsernameTakenException;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface AuthService extends Remote {
    UserDTO register(String username, String password)
        throws RemoteException, UsernameTakenException, InvalidRegistrationException;

    LoginResultDTO login(String username, String password)
        throws RemoteException, AuthenticationException;

    void keepAlive(String sessionToken)
        throws RemoteException, AuthenticationException;

    /** No-op for unknown or already-expired tokens. */
    void logout(String sessionToken)
        throws RemoteException;
}
