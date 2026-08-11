package com.matchmaker.server.jms;

import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.Session;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JmsConnectionFactoryTest {

    @Test
    void create_returnsAStartedUsableConnection() throws Exception {
        Connection connection = JmsConnectionFactory.create();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            assertNotNull(session);
        } finally {
            connection.close();
        }
    }
}
