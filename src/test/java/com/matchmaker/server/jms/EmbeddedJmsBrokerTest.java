package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.Topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmbeddedJmsBrokerTest {

    private static final int TEST_PORT = 61617; // distinct from ServerMain's production JMS_PORT

    private BrokerService broker;
    private Connection publisherConnection;
    private Connection clientConnection;

    @AfterEach
    void tearDown() throws Exception {
        if (publisherConnection != null) publisherConnection.close();
        if (clientConnection != null) clientConnection.close();
        if (broker != null) broker.stop();
    }

    @Test
    void aSecondIndependentTcpConnectionCanReachTheBroker() throws Exception {
        broker = EmbeddedJmsBroker.start(TEST_PORT);

        // Simulates ServerMain's own publisher connection.
        publisherConnection = JmsConnectionFactory.createForBroker("tcp://localhost:" + TEST_PORT);
        Session publisherSession = publisherConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        ActiveMqGameEventPublisher publisher = new ActiveMqGameEventPublisher(publisherSession);

        // Simulates a real player client in a separate JVM/process -- a totally independent
        // Connection, not sharing anything in-process with the publisher above. This is exactly
        // what the old vm://matchmaker-<uuid> broker could never support.
        clientConnection = JmsConnectionFactory.createForBroker("tcp://localhost:" + TEST_PORT);
        Session clientSession = clientConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = clientSession.createTopic("session.7.events");
        MessageConsumer consumer = clientSession.createConsumer(topic);

        GameStateDTO state = new GameStateDTO(7, 1, 1, 2, GameStatus.ACTIVE, 2, null, "{\"pieces\":{}}");
        publisher.publishToSession(7, new GameEventDTO(GameEventType.MOVE_MADE, 7, state));

        Message received = consumer.receive(2000);

        assertNotNull(received, "a genuinely separate tcp:// connection should still receive the event");
        GameEventDTO event = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.MOVE_MADE, event.getType());
    }
}
