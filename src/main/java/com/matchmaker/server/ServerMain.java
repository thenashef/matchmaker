package com.matchmaker.server;

import com.matchmaker.server.dao.DataSourceFactory;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.JdbcGameSessionDao;
import com.matchmaker.server.dao.JdbcGameTypeDao;
import com.matchmaker.server.dao.JdbcUserDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.jms.ActiveMqGameEventPublisher;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsConnectionFactory;
import com.matchmaker.server.matchmaking.JdbcMatchmakingQueue;
import com.matchmaker.server.matchmaking.MatchmakingQueue;
import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.sql.DataSource;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {

    public static final int PORT = 1099;

    public static void main(String[] args) throws Exception {
        start(PORT);

        System.out.println("MatchMaker RMI registry started on port " + PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");

        // RMI's exported objects are served by daemon-ish background threads that don't, on their
        // own, keep the launching JVM alive -- under `mvn exec:java` Maven exits as soon as main()
        // returns and port 1099 closes with it. Block the main thread forever so the server stays
        // up until the process is killed (Ctrl-C / SIGTERM).
        Thread.currentThread().join();
    }

    public static Registry start(int port) throws RemoteException, JMSException {
        return startWithImpls(port).registry();
    }

    /**
     * Package-private variant of {@link #start(int)} that also hands back the constructed
     * service impls (not just the registry), so tests can unexport each one individually in
     * teardown -- {@code registry.lookup(...)} only ever returns a client-side stub, which
     * can't be passed to {@code UnicastRemoteObject.unexportObject}.
     */
    static Started startWithImpls(int port) throws RemoteException, JMSException {
        SessionManager sessionManager = new SessionManager();

        DataSource dataSource = DataSourceFactory.create();
        UserDao userDao = new JdbcUserDao(dataSource);
        GameSessionDao gameSessionDao = new JdbcGameSessionDao(dataSource);
        GameTypeDao gameTypeDao = new JdbcGameTypeDao(dataSource);
        MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(dataSource);

        Connection jmsConnection = JmsConnectionFactory.create();
        Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        GameEventPublisher gameEventPublisher = new ActiveMqGameEventPublisher(jmsSession);

        Registry registry = LocateRegistry.createRegistry(port);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue, gameEventPublisher);
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
