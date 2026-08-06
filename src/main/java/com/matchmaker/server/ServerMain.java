package com.matchmaker.server;

import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {

    public static final int PORT = 1099;

    public static void main(String[] args) throws Exception {
        start(PORT);

        System.out.println("MatchMaker RMI registry started on port " + PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");
    }

    public static Registry start(int port) throws RemoteException {
        return startWithImpls(port).registry();
    }

    /**
     * Package-private variant of {@link #start(int)} that also hands back the constructed
     * service impls (not just the registry), so tests can unexport each one individually in
     * teardown -- {@code registry.lookup(...)} only ever returns a client-side stub, which
     * can't be passed to {@code UnicastRemoteObject.unexportObject}.
     */
    static Started startWithImpls(int port) throws RemoteException {
        SessionManager sessionManager = new SessionManager();

        Registry registry = LocateRegistry.createRegistry(port);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);

        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        return new Started(registry, authService, playerService, adminService);
    }

    record Started(Registry registry, AuthServiceImpl authService, PlayerServiceImpl playerService,
                    AdminServiceImpl adminService) {
    }
}
