package com.matchmaker.common.jms;

import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import java.util.List;

public final class JmsClientSupport {

    private JmsClientSupport() {
    }

    public static Connection open(String host, int port, int userId, String token) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://" + host + ":" + port);
        factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));
        Connection connection = factory.createConnection(String.valueOf(userId), token);
        connection.start();
        return connection;
    }

    public static Session createSession(Connection connection) throws JMSException {
        return connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    }

    public static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (JMSException ignored) {
        }
    }
}
