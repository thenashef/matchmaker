package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.InMemoryGameSessionDao;
import com.matchmaker.server.dao.InMemoryUserDao;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddedJmsBrokerTest {

    private static final int TEST_PORT = 61617; // distinct from ServerMain's production JMS_PORT
    private static final String SERVICE_USERNAME = "matchmaker-service";
    private static final String SERVICE_PASSWORD = "test-service-secret";

    private final SessionManager sessionManager = new SessionManager();
    private final InMemoryUserDao userDao = new InMemoryUserDao();
    private final InMemoryGameSessionDao gameSessionDao = new InMemoryGameSessionDao();
    private final List<Connection> openConnections = new ArrayList<>();

    private BrokerService broker;

    @BeforeEach
    void setUp() throws Exception {
        JmsSecurityPlugin plugin = new JmsSecurityPlugin(
                sessionManager, userDao, gameSessionDao, SERVICE_USERNAME, SERVICE_PASSWORD);
        broker = EmbeddedJmsBroker.start(TEST_PORT, plugin);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Connection connection : openConnections) {
            connection.close();
        }
        broker.stop();
    }

    @Test
    void aSecondIndependentTcpConnectionWithAValidTokenCanReachTheBroker() throws Exception {
        int player1 = registerUser("player1");
        int player2 = registerUser("player2");
        gameSessionDao.addActiveSession(
                new GameStateDTO(7, 1, player1, player2, GameStatus.ACTIVE, player1, null, "{\"pieces\":{}}"));
        String token = sessionManager.createSession(player1);

        // Simulates ServerMain's own publisher connection.
        Connection publisherConnection = serviceConnection();
        Session publisherSession = publisherConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        ActiveMqGameEventPublisher publisher = new ActiveMqGameEventPublisher(publisherSession);

        // Simulates a real player client in a separate JVM/process -- a totally independent
        // Connection, not sharing anything in-process with the publisher above. This is exactly
        // what the old vm://matchmaker-<uuid> broker could never support.
        Connection clientConnection = userConnection(player1, token);
        Session clientSession = clientConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = clientSession.createTopic("session.7.events");
        MessageConsumer consumer = clientSession.createConsumer(topic);

        GameStateDTO state = new GameStateDTO(7, 1, player1, player2, GameStatus.ACTIVE, player2, null,
                "{\"pieces\":{}}");
        publisher.publishToSession(7, new GameEventDTO(GameEventType.MOVE_MADE, 7, state));

        Message received = consumer.receive(2000);

        assertNotNull(received, "a genuinely separate tcp:// connection with a valid token should still receive events");
        GameEventDTO event = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.MOVE_MADE, event.getType());
    }

    @Test
    void anonymousConnectionIsRejected() {
        assertThrows(JMSException.class,
                () -> JmsConnectionFactory.createForBroker("tcp://localhost:" + TEST_PORT));
    }

    @Test
    void connectionWithAnInvalidTokenIsRejected() {
        assertThrows(JMSException.class, () -> userConnection(1, "not-a-real-token"));
    }

    @Test
    void playerCannotSubscribeToAnotherPlayersQueue() throws Exception {
        int self = registerUser("self");
        int other = registerUser("other");
        String token = sessionManager.createSession(self);
        Session session = userConnection(self, token).createSession(false, Session.AUTO_ACKNOWLEDGE);

        assertThrows(JMSException.class,
                () -> session.createConsumer(session.createQueue("player." + other + ".events")));
    }

    @Test
    void playerCanSubscribeToTheirOwnQueue() throws Exception {
        int self = registerUser("self");
        String token = sessionManager.createSession(self);
        Session session = userConnection(self, token).createSession(false, Session.AUTO_ACKNOWLEDGE);

        MessageConsumer consumer = session.createConsumer(session.createQueue("player." + self + ".events"));
        assertNotNull(consumer);
    }

    @Test
    void nonParticipantCannotSubscribeToASessionTopic() throws Exception {
        int player1 = registerUser("player1");
        int player2 = registerUser("player2");
        int bystander = registerUser("bystander");
        gameSessionDao.addActiveSession(
                new GameStateDTO(9, 1, player1, player2, GameStatus.ACTIVE, player1, null, "{\"pieces\":{}}"));
        String token = sessionManager.createSession(bystander);
        Session session = userConnection(bystander, token).createSession(false, Session.AUTO_ACKNOWLEDGE);

        assertThrows(JMSException.class,
                () -> session.createConsumer(session.createTopic("session.9.events")));
    }

    @Test
    void adminCanSubscribeToAnySessionTopic() throws Exception {
        int player1 = registerUser("player1");
        int player2 = registerUser("player2");
        int admin = registerUser("admin");
        userDao.markAdmin(admin);
        gameSessionDao.addActiveSession(
                new GameStateDTO(11, 1, player1, player2, GameStatus.ACTIVE, player1, null, "{\"pieces\":{}}"));
        String token = sessionManager.createSession(admin);
        Session session = userConnection(admin, token).createSession(false, Session.AUTO_ACKNOWLEDGE);

        MessageConsumer consumer = session.createConsumer(session.createTopic("session.11.events"));
        assertNotNull(consumer);
    }

    @Test
    void nonServiceConnectionCannotPublish() throws Exception {
        int self = registerUser("self");
        String token = sessionManager.createSession(self);
        Session session = userConnection(self, token).createSession(false, Session.AUTO_ACKNOWLEDGE);

        assertThrows(JMSException.class, () -> {
            MessageProducer producer = session.createProducer(session.createQueue("player." + self + ".events"));
            TextMessage message = session.createTextMessage("should never be sent");
            producer.send(message);
        });
    }

    // The three tests below cover the *anonymous* producer -- session.createProducer(null), where
    // the destination travels per-message on send(destination, message) rather than being fixed at
    // creation. nonServiceConnectionCannotPublish() above only exercises a producer created with an
    // explicit destination, which is checked in addProducer(); an anonymous one carries a null
    // destination there and is let through by design, so without a check on the send() path it was
    // an unchecked write channel to every destination on the broker.

    @Test
    void nonServiceConnectionCannotPublishViaAnAnonymousProducer() throws Exception {
        int player1 = registerUser("player1");
        int player2 = registerUser("player2");
        gameSessionDao.addActiveSession(
                new GameStateDTO(21, 1, player1, player2, GameStatus.ACTIVE, player1, null, "{\"pieces\":{}}"));
        String token = sessionManager.createSession(player1);
        Session session = userConnection(player1, token).createSession(false, Session.AUTO_ACKNOWLEDGE);

        // Creating it is fine -- there is no destination to authorize yet.
        MessageProducer anonymous = session.createProducer(null);

        // Sending is not, even to a topic this player is a legitimate *subscriber* of: publishing
        // is the server's job alone, or a player could forge a game result onto their opponent.
        assertThrows(JMSException.class,
                () -> anonymous.send(session.createTopic("session.21.events"),
                        session.createTextMessage("forged move")));
    }

    @Test
    void anonymousProducerCannotPublishToAnotherPlayersQueue() throws Exception {
        int self = registerUser("self");
        int other = registerUser("other");
        String token = sessionManager.createSession(self);
        Session session = userConnection(self, token).createSession(false, Session.AUTO_ACKNOWLEDGE);

        MessageProducer anonymous = session.createProducer(null);

        assertThrows(JMSException.class,
                () -> anonymous.send(session.createQueue("player." + other + ".events"),
                        session.createTextMessage("forged match")));
    }

    @Test
    void serviceConnectionCanStillPublishViaAnAnonymousProducer() throws Exception {
        int player1 = registerUser("player1");
        int player2 = registerUser("player2");
        gameSessionDao.addActiveSession(
                new GameStateDTO(23, 1, player1, player2, GameStatus.ACTIVE, player1, null, "{\"pieces\":{}}"));
        String token = sessionManager.createSession(player1);

        Session consumerSession = userConnection(player1, token).createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = consumerSession.createConsumer(consumerSession.createTopic("session.23.events"));

        Session serviceSession = serviceConnection().createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageProducer anonymous = serviceSession.createProducer(null);
        anonymous.send(serviceSession.createTopic("session.23.events"),
                serviceSession.createTextMessage("legitimate"));

        assertNotNull(consumer.receive(2000), "the send() check must not lock out the server's own publisher");
    }

    @Test
    void advisoryTopicsCarryNothingForAPlayerToHarvest() throws Exception {
        // JmsSecurityPlugin has to let any authenticated connection consume ActiveMQ.Advisory.>,
        // because the client library subscribes to advisories itself and denying them breaks
        // connecting outright. That left a logged-in player able to watch broker-wide advisories
        // -- who is connected, which destinations exist. The broker no longer publishes them at
        // all, so subscribing still succeeds but yields nothing.
        int snooper = registerUser("snooper");
        String token = sessionManager.createSession(snooper);
        Session snooperSession = userConnection(snooper, token).createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer advisories =
                snooperSession.createConsumer(snooperSession.createTopic("ActiveMQ.Advisory.Connection"));

        // Generate exactly the activity that would have produced an advisory: a new connection,
        // a new consumer and a real publish.
        int other = registerUser("other");
        gameSessionDao.addActiveSession(
                new GameStateDTO(31, 1, other, snooper, GameStatus.ACTIVE, other, null, "{\"pieces\":{}}"));
        Session otherSession = userConnection(other, sessionManager.createSession(other))
                .createSession(false, Session.AUTO_ACKNOWLEDGE);
        otherSession.createConsumer(otherSession.createTopic("session.31.events"));
        new ActiveMqGameEventPublisher(serviceConnection().createSession(false, Session.AUTO_ACKNOWLEDGE))
                .publishToSession(31, new GameEventDTO(GameEventType.MOVE_MADE, 31,
                        new GameStateDTO(31, 1, other, snooper, GameStatus.ACTIVE, snooper, null, "{\"pieces\":{}}")));

        assertNull(advisories.receive(500), "advisory traffic should not be observable by a player");
    }

    private int registerUser(String username) {
        return userDao.insert(username, "unused-hash").orElseThrow().id();
    }

    private Connection serviceConnection() throws JMSException {
        Connection connection = JmsConnectionFactory.createForBroker(
                "tcp://localhost:" + TEST_PORT, SERVICE_USERNAME, SERVICE_PASSWORD);
        openConnections.add(connection);
        return connection;
    }

    private Connection userConnection(int userId, String token) throws JMSException {
        Connection connection = JmsConnectionFactory.createForBroker(
                "tcp://localhost:" + TEST_PORT, String.valueOf(userId), token);
        openConnections.add(connection);
        return connection;
    }
}
