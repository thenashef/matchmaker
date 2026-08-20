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

/**
 * Authenticates every JMS connection and restricts each one to the destinations it owns.
 * Player/admin connections authenticate with (userId, RMI session token) -- the token is
 * verified against the same {@link SessionManager} the RMI services use, so there is no
 * separate credential store. The server's own publisher connection authenticates with a
 * random per-process service credential and is the only connection allowed to produce.
 */
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
            // Only catches producers created with an explicit destination, so those fail fast at
            // creation time rather than on their first send. An *anonymous* producer -- JMS's
            // session.createProducer(null), where the destination travels per-message on
            // send(destination, message) instead -- has a null destination here and is deliberately
            // let through, exactly as ActiveMQ's own AuthorizationBroker does. That is precisely
            // why send() below has to repeat this check rather than trusting this one: without it,
            // an anonymous producer is an unchecked write path to every destination on the broker.
            authorizeProducer(identity(context), info.getDestination());
            super.addProducer(context, info);
        }

        @Override
        public void send(ProducerBrokerExchange producerExchange, Message messageSend) throws Exception {
            // The real write-side gate. Every message passes through here regardless of how its
            // producer was created, which is what makes the anonymous-producer path above safe.
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

        /**
         * Resolves the {@link Identity} that {@link #addConnection} attached to this connection.
         *
         * <p>Deliberately not a bare cast. Client connections do go through {@code addConnection}
         * and so always carry an {@code Identity}, but the broker also drives work on its own
         * internal contexts -- advisory generation being the one that reaches {@link #send} on
         * every publish -- and those carry {@link SecurityContext#BROKER_SECURITY_CONTEXT}
         * instead. Casting that to {@code Identity} would throw a {@code ClassCastException} out
         * of the middle of the broker. ActiveMQ's own {@code AuthorizationBroker} routes every
         * check through an equivalent helper for the same reason; this mirrors it, and treats
         * the broker's own work as fully authorized rather than failing it.
         */
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

        /** Compares credentials without short-circuiting on the first differing byte. */
        private static boolean constantTimeEquals(String expected, String actual) {
            if (expected == null || actual == null) {
                return false;
            }
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Stands in for the broker's own internal work (see {@code SecurityBrokerFilter.identity}).
     * Marked as the service identity because that is the authorization level broker-generated
     * traffic needs -- it is the broker itself, not a client that could have forged its way here.
     */
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
