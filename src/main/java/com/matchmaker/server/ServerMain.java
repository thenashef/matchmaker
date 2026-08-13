package com.matchmaker.server;

import com.matchmaker.server.dao.DataSourceFactory;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.JdbcGameSessionDao;
import com.matchmaker.server.dao.JdbcGameTypeDao;
import com.matchmaker.server.dao.JdbcUserDao;
import com.matchmaker.server.dao.UserDao;
import com.matchmaker.server.game.GameEngine;
import com.matchmaker.server.game.checkers.CheckersEngine;
import com.matchmaker.server.jms.ActiveMqGameEventPublisher;
import com.matchmaker.server.jms.EmbeddedJmsBroker;
import com.matchmaker.server.jms.GameEventPublisher;
import com.matchmaker.server.jms.JmsConnectionFactory;
import com.matchmaker.server.matchmaking.JdbcMatchmakingQueue;
import com.matchmaker.server.matchmaking.MatchmakingQueue;
import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;
import org.apache.activemq.broker.BrokerService;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.sql.DataSource;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class ServerMain {

    public static final int RMI_PORT = 1099;
    public static final int JMS_PORT = 61616;

    public static void main(String[] args) throws Exception {
        start(RMI_PORT, JMS_PORT);

        System.out.println("MatchMaker RMI registry started on port " + RMI_PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");
        System.out.println("JMS broker listening on tcp://localhost:" + JMS_PORT);

        // RMI's exported objects are served by daemon-ish background threads that don't, on their
        // own, keep the launching JVM alive -- under `mvn exec:java` Maven exits as soon as main()
        // returns and port 1099 closes with it. Block the main thread forever so the server stays
        // up until the process is killed (Ctrl-C / SIGTERM).
        Thread.currentThread().join();
    }

    public static Registry start(int rmiPort, int jmsPort) throws Exception {
        return startWithImpls(rmiPort, jmsPort).registry();
    }

    /**
     * Package-private variant of {@link #start(int, int)} that also hands back the constructed
     * service impls (not just the registry), so tests can unexport each one individually in
     * teardown -- {@code registry.lookup(...)} only ever returns a client-side stub, which
     * can't be passed to {@code UnicastRemoteObject.unexportObject}.
     */
    static Started startWithImpls(int rmiPort, int jmsPort) throws Exception {
        SessionManager sessionManager = new SessionManager();

        DataSource dataSource = DataSourceFactory.create();
        UserDao userDao = new JdbcUserDao(dataSource);
        GameSessionDao gameSessionDao = new JdbcGameSessionDao(dataSource);
        GameTypeDao gameTypeDao = new JdbcGameTypeDao(dataSource);
        MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(dataSource);

        BrokerService jmsBroker = EmbeddedJmsBroker.start(jmsPort);
        Connection jmsConnection = JmsConnectionFactory.createForBroker("tcp://localhost:" + jmsPort);
        Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        GameEventPublisher gameEventPublisher = new ActiveMqGameEventPublisher(jmsSession);
        GameEngine gameEngine = new CheckersEngine();

        Registry registry = LocateRegistry.createRegistry(rmiPort);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao,
                matchmakingQueue, gameEventPublisher, gameEngine);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager, userDao, gameTypeDao,
                gameSessionDao, gameEventPublisher);

        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        return new Started(jmsBroker, registry, authService, playerService, adminService);
    }

    record Started(BrokerService jmsBroker, Registry registry, AuthServiceImpl authService,
                    PlayerServiceImpl playerService, AdminServiceImpl adminService) {
    }
}
