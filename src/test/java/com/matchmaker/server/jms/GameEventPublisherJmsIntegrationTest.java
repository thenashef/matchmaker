package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;
import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.enums.GameEventType;
import com.matchmaker.common.enums.GameStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GameEventPublisherJmsIntegrationTest {

    private Connection connection;
    private Session session;
    private GameEventPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        connection = JmsConnectionFactory.create();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        publisher = new ActiveMqGameEventPublisher(session);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void publishToPlayer_realConsumerReceivesTheEvent() throws Exception {
        int waitingPlayerUserId = 42;
        Queue queue = session.createQueue("player." + waitingPlayerUserId + ".events");
        MessageConsumer consumer = session.createConsumer(queue);

        GameStateDTO matchedSession = new GameStateDTO(7, 1, waitingPlayerUserId, 99,
                GameStatus.ACTIVE, waitingPlayerUserId, null, null);
        GameEventDTO event = new GameEventDTO(GameEventType.MATCH_FOUND, 7, matchedSession);

        publisher.publishToPlayer(waitingPlayerUserId, event);

        Message received = consumer.receive(2000);

        assertNotNull(received, "expected a message to arrive on the player's queue");
        assertInstanceOf(ObjectMessage.class, received);
        GameEventDTO receivedEvent = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.MATCH_FOUND, receivedEvent.getType());
        assertEquals(7, receivedEvent.getSessionId());
        assertEquals(waitingPlayerUserId, receivedEvent.getGameState().getPlayer1Id());
    }
}
