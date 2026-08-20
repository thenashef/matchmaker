package com.matchmaker.server.jms;

import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerService;

public class EmbeddedJmsBroker {

    public static BrokerService start(int port, JmsSecurityPlugin securityPlugin) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setBrokerName("matchmaker-" + port);
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setAdvisorySupport(false);
        broker.setPlugins(new BrokerPlugin[] {securityPlugin});
        broker.addConnector("tcp://0.0.0.0:" + port);
        broker.start();
        return broker;
    }
}
