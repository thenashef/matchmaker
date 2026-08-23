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
import javax.jms.Topic;

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

    @Test
    void publishToSession_realConsumerReceivesTheEvent() throws Exception {
        int sessionId = 7;
        Topic topic = session.createTopic("session." + sessionId + ".events");
        MessageConsumer consumer = session.createConsumer(topic);

        GameStateDTO updatedSession = new GameStateDTO(sessionId, 1, 42, 99,
                GameStatus.ACTIVE, 99, null, "{\"pieces\":{}}");
        GameEventDTO event = new GameEventDTO(GameEventType.MOVE_MADE, sessionId, updatedSession);

        publisher.publishToSession(sessionId, event);

        Message received = consumer.receive(2000);

        assertNotNull(received, "expected a message to arrive on the session's topic");
        assertInstanceOf(ObjectMessage.class, received);
        GameEventDTO receivedEvent = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.MOVE_MADE, receivedEvent.getType());
        assertEquals(sessionId, receivedEvent.getSessionId());
    }

    @Test
    void publishToSession_chatMessageEvent_realConsumerReceivesItOverTheWire() throws Exception {
        // Regression guard: ChatMessageDTO carries a java.time.LocalDateTime, and ActiveMQ's
        // trusted-package allow-list here (see RmiJmsServerConnection/RmiJmsAdminConnection/
        // JmsConnectionFactory) does not include java.time. A CHAT_MESSAGE event must therefore
        // carry its payload as plain String/Integer fields on GameEventDTO, never a nested
        // ChatMessageDTO -- this test is the only tier that would catch a regression back to that,
        // since plain ObjectOutputStream/ObjectInputStream round-trips (unit tests) don't apply
        // ActiveMQ's ClassLoadingAwareObjectInputStream trusted-package check at all.
        int sessionId = 7;
        Topic topic = session.createTopic("session." + sessionId + ".events");
        MessageConsumer consumer = session.createConsumer(topic);

        GameEventDTO event = new GameEventDTO(GameEventType.CHAT_MESSAGE, sessionId, 42, "good luck");

        publisher.publishToSession(sessionId, event);

        Message received = consumer.receive(2000);

        assertNotNull(received, "expected the chat event to arrive on the session's topic");
        assertInstanceOf(ObjectMessage.class, received);
        GameEventDTO receivedEvent = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.CHAT_MESSAGE, receivedEvent.getType());
        assertEquals(42, receivedEvent.getChatSenderUserId());
        assertEquals("good luck", receivedEvent.getChatContent());
    }

    @Test
    void publishToSession_bothSubscribersReceiveTheirOwnCopy() throws Exception {
        int sessionId = 7;
        Topic topic = session.createTopic("session." + sessionId + ".events");
        MessageConsumer player1Consumer = session.createConsumer(topic);
        MessageConsumer player2Consumer = session.createConsumer(topic);

        GameStateDTO updatedSession = new GameStateDTO(sessionId, 1, 42, 99,
                GameStatus.ACTIVE, 99, null, "{\"pieces\":{}}");
        GameEventDTO event = new GameEventDTO(GameEventType.MOVE_MADE, sessionId, updatedSession);

        publisher.publishToSession(sessionId, event);

        Message receivedByPlayer1 = player1Consumer.receive(2000);
        Message receivedByPlayer2 = player2Consumer.receive(2000);

        assertNotNull(receivedByPlayer1, "expected player 1's subscription to receive the event");
        assertNotNull(receivedByPlayer2, "expected player 2's subscription to receive the event -- "
                + "a JMS Queue would only have delivered this to one of the two consumers");
    }
}
