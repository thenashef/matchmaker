package com.matchmaker.server.jms;

import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerService;

public class EmbeddedJmsBroker {

    public static BrokerService start(int port, JmsSecurityPlugin securityPlugin) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setBrokerName("matchmaker-" + port);
        broker.setPersistent(false);
        broker.setUseJmx(false);
        // JmsSecurityPlugin has to let every authenticated connection consume ActiveMQ.Advisory.>
        // -- the client library subscribes to advisory topics itself, so denying them outright
        // breaks connecting. That left any logged-in player able to watch broker-wide advisories:
        // a live feed of who is connected and which sessions exist. Nothing in MatchMaker uses
        // advisories (no temp destinations, no request-reply), so the cheaper fix is not to
        // publish them at all, which removes the destinations rather than guarding them.
        broker.setAdvisorySupport(false);
        broker.setPlugins(new BrokerPlugin[] {securityPlugin});
        broker.addConnector("tcp://0.0.0.0:" + port);
        broker.start();
        return broker;
    }
}
