package com.matchmaker.server.jms;

import com.matchmaker.common.jms.JmsClientSupport;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import java.util.UUID;

public class JmsConnectionFactory {

    public static Connection create() throws JMSException {
        return createForBroker("vm://matchmaker-" + UUID.randomUUID() + "?broker.persistent=false");
    }

    public static Connection createForBroker(String brokerUrl) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setTrustedPackages(JmsClientSupport.TRUSTED_PACKAGES);

        Connection connection = factory.createConnection();
        connection.start();
        return connection;
    }

    public static Connection createForBroker(String brokerUrl, String username, String password) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setTrustedPackages(JmsClientSupport.TRUSTED_PACKAGES);

        Connection connection = factory.createConnection(username, password);
        connection.start();
        return connection;
    }
}
