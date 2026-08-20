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

    /**
     * Invalidates {@code sessionToken} immediately, rather than leaving it valid until it
     * ages out. Deliberately does not throw on an unknown or already-expired token: it is
     * called from client shutdown, where "the thing you asked to revoke is already gone" is
     * the desired outcome, not an error to report to a window that is closing anyway.
     */
    void logout(String sessionToken)
        throws RemoteException;
}
