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
import org.apache.activemq.broker.region.Subscription;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ConnectionInfo;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.security.SecurityContext;

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
            ActiveMQDestination destination = info.getDestination();
            if (destination != null && !isAdvisory(destination) && !identity(context).service) {
                throw new SecurityException("Not authorized to publish to " + destination.getPhysicalName());
            }
            super.addProducer(context, info);
        }

        private Identity authenticate(String username, String password) {
            if (serviceUsername.equals(username) && servicePassword.equals(password)) {
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
            return (Identity) context.getSecurityContext();
        }

        private boolean isAdvisory(ActiveMQDestination destination) {
            return destination.getPhysicalName().startsWith("ActiveMQ.Advisory.");
        }
    }

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
