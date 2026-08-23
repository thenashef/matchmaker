package com.matchmaker.server;

import com.matchmaker.server.dao.ChatMessageDao;
import com.matchmaker.server.dao.DataSourceFactory;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.GameTypeDao;
import com.matchmaker.server.dao.JdbcChatMessageDao;
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
import com.matchmaker.server.jms.JmsSecurityPlugin;
import com.matchmaker.server.matchmaking.JdbcMatchmakingQueue;
import com.matchmaker.server.matchmaking.MatchmakingQueue;
import com.matchmaker.server.rmi.AdminServiceImpl;
import com.matchmaker.server.rmi.AuthServiceImpl;
import com.matchmaker.server.rmi.PlayerServiceImpl;
import org.apache.activemq.broker.BrokerService;

import javax.jms.Connection;
import javax.jms.Session;
import javax.sql.DataSource;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerMain {

    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    public static final int RMI_PORT = 1099;
    public static final int JMS_PORT = 61616;
    private static final Duration DISCONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration WATCHDOG_TICK_INTERVAL = Duration.ofSeconds(5);

    public static void main(String[] args) throws Exception {
        // Without this, RMI's first network call resolves the machine's own hostname via
        // InetAddress.getLocalHost() -- on macOS, a ".local" mDNS hostname with no /etc/hosts
        // entry makes that resolution take ~5s. Pinning it to loopback skips that lookup entirely.
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");

        Started started = startWithImpls(RMI_PORT, JMS_PORT);
        Runtime.getRuntime().addShutdownHook(new Thread(started::close, "server-shutdown"));

        System.out.println("MatchMaker RMI registry started on port " + RMI_PORT);
        System.out.println("Bound services: AuthService, PlayerService, AdminService");
        System.out.println("JMS broker listening on tcp://localhost:" + JMS_PORT);

        Thread.currentThread().join();
    }

    public static Registry start(int rmiPort, int jmsPort) throws Exception {
        return startWithImpls(rmiPort, jmsPort).registry();
    }

    static Started startWithImpls(int rmiPort, int jmsPort) throws Exception {
        SessionManager sessionManager = new SessionManager();

        DataSource dataSource = DataSourceFactory.create();
        UserDao userDao = new JdbcUserDao(dataSource);
        GameSessionDao gameSessionDao = new JdbcGameSessionDao(dataSource);
        GameTypeDao gameTypeDao = new JdbcGameTypeDao(dataSource);
        MatchmakingQueue matchmakingQueue = new JdbcMatchmakingQueue(dataSource);
        ChatMessageDao chatMessageDao = new JdbcChatMessageDao(dataSource);

        String jmsServiceUsername = "matchmaker-service";
        String jmsServicePassword = UUID.randomUUID().toString();
        JmsSecurityPlugin jmsSecurityPlugin = new JmsSecurityPlugin(
                sessionManager, userDao, gameSessionDao, jmsServiceUsername, jmsServicePassword);

        BrokerService jmsBroker = EmbeddedJmsBroker.start(jmsPort, jmsSecurityPlugin);
        Connection jmsConnection = JmsConnectionFactory.createForBroker(
                "tcp://localhost:" + jmsPort, jmsServiceUsername, jmsServicePassword);
        Session jmsSession = jmsConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        GameEventPublisher gameEventPublisher = new ActiveMqGameEventPublisher(jmsSession);
        GameEngine gameEngine = new CheckersEngine();

        Registry registry = LocateRegistry.createRegistry(rmiPort);
        AuthServiceImpl authService = new AuthServiceImpl(sessionManager, userDao);
        PlayerServiceImpl playerService = new PlayerServiceImpl(sessionManager, gameSessionDao, gameTypeDao,
                matchmakingQueue, gameEventPublisher, gameEngine, chatMessageDao, userDao);
        AdminServiceImpl adminService = new AdminServiceImpl(sessionManager, userDao, gameTypeDao,
                gameSessionDao, gameEventPublisher, matchmakingQueue, DISCONNECT_TIMEOUT);

        registry.rebind("AuthService", authService);
        registry.rebind("PlayerService", playerService);
        registry.rebind("AdminService", adminService);

        SessionWatchdog sessionWatchdog = new SessionWatchdog(
                sessionManager, gameSessionDao, gameEventPublisher, DISCONNECT_TIMEOUT, TURN_TIMEOUT);
        sessionWatchdog.start(WATCHDOG_TICK_INTERVAL);

        return new Started(jmsBroker, jmsConnection, dataSource, registry, authService, playerService,
                adminService, sessionWatchdog);
    }

    record Started(BrokerService jmsBroker, Connection jmsConnection, DataSource dataSource, Registry registry,
                    AuthServiceImpl authService, PlayerServiceImpl playerService, AdminServiceImpl adminService,
                    SessionWatchdog sessionWatchdog) {

        void close() {
            closing("session watchdog", sessionWatchdog::stop);
            closing("JMS connection", jmsConnection::close);
            closing("JMS broker", jmsBroker::stop);
            if (dataSource instanceof AutoCloseable closeablePool) {
                closing("database pool", closeablePool::close);
            }
        }

        private static void closing(String what, ThrowingRunnable action) {
            try {
                action.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to shut down " + what, e);
            }
        }

        @FunctionalInterface
        private interface ThrowingRunnable {
            void run() throws Exception;
        }
    }
}
