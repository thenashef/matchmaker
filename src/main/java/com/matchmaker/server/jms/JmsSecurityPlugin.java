package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameStateDTO;
import com.matchmaker.common.exceptions.AuthenticationException;
import com.matchmaker.server.SessionManager;
import com.matchmaker.server.dao.GameSessionDao;
import com.matchmaker.server.dao.UserDao;
import org.apache.activemq.broker.Broker;
import org.apache.activemq.broker.BrokerFilter;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.ProducerBrokerExchange;
import org.apache.activemq.broker.region.Subscription;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ConnectionInfo;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.security.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JmsSecurityPlugin implements BrokerPlugin {

    private final SessionManager sessionManager;
    private final UserDao userDao;
    private final GameSessionDao gameSessionDao;
    private final String serviceUsername;
    private final String servicePassword;

    public JmsSecurityPlugin(SessionManager sessionManager, UserDao userDao, GameSessionDao gameSessionDao,
                              String serviceUsername, String servicePassword) {
        this.sessionManager = sessionManager;
        this.userDao = userDao;
        this.gameSessionDao = gameSessionDao;
        this.serviceUsername = serviceUsername;
        this.servicePassword = servicePassword;
    }

    @Override
    public Broker installPlugin(Broker broker) {
        return new SecurityBrokerFilter(broker);
    }

    private final class SecurityBrokerFilter extends BrokerFilter {

        private final Pattern playerQueuePattern = Pattern.compile("player\\.(\\d+)\\.events");
        private final Pattern sessionTopicPattern = Pattern.compile("session\\.(\\d+)\\.events");

        SecurityBrokerFilter(Broker next) {
            super(next);
        }

        @Override
        public void addConnection(ConnectionContext context, ConnectionInfo info) throws Exception {
            context.setSecurityContext(authenticate(info.getUserName(), info.getPassword()));
            super.addConnection(context, info);
        }

        @Override
        public Subscription addConsumer(ConnectionContext context, ConsumerInfo info) throws Exception {
            authorizeConsumer(identity(context), info.getDestination());
            return super.addConsumer(context, info);
        }

        @Override
        public void addProducer(ConnectionContext context, ProducerInfo info) throws Exception {
            authorizeProducer(identity(context), info.getDestination());
            super.addProducer(context, info);
        }

        @Override
        public void send(ProducerBrokerExchange producerExchange, Message messageSend) throws Exception {
            authorizeProducer(identity(producerExchange.getConnectionContext()), messageSend.getDestination());
            super.send(producerExchange, messageSend);
        }

        private void authorizeProducer(Identity identity, ActiveMQDestination destination) {
            if (destination == null || isAdvisory(destination) || identity.service) {
                return;
            }
            throw new SecurityException("Not authorized to publish to " + destination.getPhysicalName());
        }

        private Identity authenticate(String username, String password) {
            if (constantTimeEquals(serviceUsername, username) && constantTimeEquals(servicePassword, password)) {
                return new Identity(username, true, false, -1);
            }
            int userId;
            try {
                userId = sessionManager.resolve(password);
            } catch (AuthenticationException e) {
                throw new SecurityException("Invalid or expired session token");
            }
            if (!String.valueOf(userId).equals(username)) {
                throw new SecurityException("Username does not match session token");
            }
            boolean admin = userDao.findById(userId).map(record -> record.admin()).orElse(false);
            return new Identity(username, false, admin, userId);
        }

        private void authorizeConsumer(Identity identity, ActiveMQDestination destination) {
            if (destination == null || isAdvisory(destination)) {
                return;
            }
            String name = destination.getPhysicalName();

            Matcher playerMatch = playerQueuePattern.matcher(name);
            if (playerMatch.matches()) {
                int ownerId = Integer.parseInt(playerMatch.group(1));
                if (identity.service || identity.userId == ownerId) {
                    return;
                }
                throw new SecurityException("Not authorized for " + name);
            }

            Matcher sessionMatch = sessionTopicPattern.matcher(name);
            if (sessionMatch.matches()) {
                if (identity.service || identity.admin) {
                    return;
                }
                int sessionId = Integer.parseInt(sessionMatch.group(1));
                GameStateDTO session = gameSessionDao.findActiveById(sessionId).orElse(null);
                if (session != null
                        && (session.getPlayer1Id() == identity.userId || session.getPlayer2Id() == identity.userId)) {
                    return;
                }
                throw new SecurityException("Not authorized for " + name);
            }

            throw new SecurityException("Unknown destination " + name);
        }

        private Identity identity(ConnectionContext context) {
            SecurityContext securityContext = context == null ? null : context.getSecurityContext();
            if (securityContext == null) {
                throw new SecurityException("Connection has no security context -- it never authenticated");
            }
            if (securityContext.isBrokerContext()) {
                return BROKER_IDENTITY;
            }
            if (securityContext instanceof Identity identity) {
                return identity;
            }
            throw new SecurityException(
                    "Unexpected security context type: " + securityContext.getClass().getName());
        }

        private boolean isAdvisory(ActiveMQDestination destination) {
            return destination.getPhysicalName().startsWith("ActiveMQ.Advisory.");
        }

        private static boolean constantTimeEquals(String expected, String actual) {
            if (expected == null || actual == null) {
                return false;
            }
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final Identity BROKER_IDENTITY = new Identity("broker", true, true, -1);

    private static final class Identity extends SecurityContext {
        private final boolean service;
        private final boolean admin;
        private final int userId;

        Identity(String userName, boolean service, boolean admin, int userId) {
            super(userName);
            this.service = service;
            this.admin = admin;
            this.userId = userId;
        }

        @Override
        public Set<Principal> getPrincipals() {
            return Set.of();
        }
    }
}
