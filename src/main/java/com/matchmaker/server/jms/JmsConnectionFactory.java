package com.matchmaker.server.jms;

import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import java.util.List;
import java.util.UUID;

public class JmsConnectionFactory {

    public static Connection create() throws JMSException {
        return createForBroker("vm://matchmaker-" + UUID.randomUUID() + "?broker.persistent=false");
    }

    public static Connection createForBroker(String brokerUrl) throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setTrustedPackages(List.of("com.matchmaker.common.dto", "com.matchmaker.common.enums"));

        Connection connection = factory.createConnection();
        connection.start();
        return connection;
    }
}
