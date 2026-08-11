package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;

public class ActiveMqGameEventPublisher implements GameEventPublisher {

    private final Session session;

    public ActiveMqGameEventPublisher(Session session) {
        this.session = session;
    }

    // A javax.jms.Session (and everything created from it) may only be used by one thread at a
    // time per the JMS spec -- this Session is shared by every RMI caller for the life of the
    // server, so publishes must be serialized here rather than relying on the broker client's
    // undocumented internal locking.
    @Override
    public synchronized void publishToPlayer(int userId, GameEventDTO event) {
        try {
            Queue queue = session.createQueue("player." + userId + ".events");
            MessageProducer producer = session.createProducer(queue);
            try {
                ObjectMessage message = session.createObjectMessage(event);
                producer.send(message);
            } finally {
                producer.close();
            }
        } catch (JMSException e) {
            throw new JmsPublishException("Failed to publish event to player " + userId, e);
        }
    }
}
