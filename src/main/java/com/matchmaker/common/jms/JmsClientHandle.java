package com.matchmaker.common.jms;

import com.matchmaker.common.communication.ServerEventListener;
import com.matchmaker.common.communication.Subscription;
import com.matchmaker.common.dto.GameEventDTO;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class JmsClientHandle {

    private Connection connection;
    private Session session;

    public synchronized void connect(String host, int port, int userId, String token) throws JMSException {
        closeQuietly();
        connection = JmsClientSupport.open(host, port, userId, token);
        session = JmsClientSupport.createSession(connection);
    }

    public synchronized Subscription subscribe(DestinationFactory destinations, ServerEventListener listener,
                                               Logger log) throws JMSException {
        if (session == null) {
            throw new IllegalStateException("JMS is not connected");
        }
        Destination destination = destinations.create(session);
        MessageConsumer consumer = session.createConsumer(destination);
        consumer.setMessageListener(message -> {
            try {
                GameEventDTO event = (GameEventDTO) ((ObjectMessage) message).getObject();
                listener.onEvent(event);
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to read a JMS event", e);
            }
        });
        return () -> {
            try {
                consumer.close();
            } catch (JMSException e) {
                log.log(Level.WARNING, "Failed to close a JMS subscription", e);
            }
        };
    }

    public synchronized void close(Logger log) {
        Session toAbandon = session;
        Connection toClose = connection;
        session = null;
        connection = null;
        if (toAbandon == null && toClose == null) {
            return;
        }
        try {
            if (toClose != null) {
                toClose.close();
            }
        } catch (JMSException e) {
            log.log(Level.WARNING, "Failed to close JMS connection", e);
        }
    }

    private void closeQuietly() {
        session = null;
        JmsClientSupport.closeQuietly(connection);
        connection = null;
    }

    @FunctionalInterface
    public interface DestinationFactory {
        Destination create(Session session) throws JMSException;
    }
}
