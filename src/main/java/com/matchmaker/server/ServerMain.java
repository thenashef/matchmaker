package com.matchmaker.server;

import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {

    public static final int PORT = 1099;

    public static void main(String[] args) throws Exception {
        SessionManager sessionManager = new SessionManager();

        AuthServiceImpl authService = new AuthServiceImpl(sessionManager);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager);

        Registry registry = LocateRegistry.createRegistry(PORT);
        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        System.out.println("MatchMaker RMI registry started on port " + PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");
    }
}
