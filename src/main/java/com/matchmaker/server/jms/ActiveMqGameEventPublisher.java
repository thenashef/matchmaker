package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.Topic;

public class ActiveMqGameEventPublisher implements GameEventPublisher {

    private final Session session;

    public ActiveMqGameEventPublisher(Session session) {
        this.session = session;
    }

    @Override
    public synchronized void publishToPlayer(int userId, GameEventDTO event) {
        try {
            Queue queue = session.createQueue("player." + userId + ".events");
            publish(queue, event);
        } catch (JMSException e) {
            throw new JmsPublishException("Failed to publish event to player " + userId, e);
        }
    }

    @Override
    public synchronized void publishToSession(int sessionId, GameEventDTO event) {
        try {
            Topic topic = session.createTopic("session." + sessionId + ".events");
            publish(topic, event);
        } catch (JMSException e) {
            throw new JmsPublishException("Failed to publish event to session " + sessionId, e);
        }
    }

    private void publish(Destination destination, GameEventDTO event) throws JMSException {
        MessageProducer producer = session.createProducer(destination);
        try {
            ObjectMessage message = session.createObjectMessage(event);
            producer.send(message);
        } finally {
            producer.close();
        }
    }
}
