# Design: JMS Broker-Level Authentication & Authorization (Roadmap Step 10, part 2)

## Context

Roadmap step 10's fifth piece, found during Milestone 9's per-session-authorization audit (2026-08-14) and deliberately deferred there — see `docs/build-plan.md`'s "Deferred: JMS broker-level authentication/authorization" section for the original sketch, written at discovery time. This doc records what was actually decided and built, including one deviation from that sketch (below).

**Grounding facts, confirmed by reading the code:**
- `EmbeddedJmsBroker.start(port)` bound `broker.addConnector("tcp://0.0.0.0:" + port)` with zero security plugin installed.
- All three call sites (`JmsConnectionFactory.createForBroker`, `RmiJmsServerConnection`, `RmiJmsAdminConnection`) called `factory.createConnection()` with no credentials.
- Destination names are entirely predictable: `session.{sessionId}.events` (`Topic`) / `player.{userId}.events` (`Queue`), both built from small auto-increment integers, no secret involved.
- `SessionManager.resolve(token)` is already the identity source every authenticated RMI method uses — the natural JMS credential to reuse rather than inventing a second one.
- `AdminServiceImpl.requireAdmin()` already resolves admin status as `userDao.findById(userId).admin()` — reused verbatim.
- `PlayerServiceImpl.makeMove()`/`legalContinuations()` already check session participancy inline as `session.getPlayer1Id() != userId && session.getPlayer2Id() != userId` against `gameSessionDao.findActiveById(sessionId)` — there's no narrower DAO method for "is this user a participant," so the JMS-side check reuses the identical pattern rather than adding one.
- `ActiveMqGameEventPublisher` is the only JMS producer anywhere in the codebase (confirmed by grep) — both clients only ever call `session.createConsumer(...)`, never `createProducer`.
- ActiveMQ 5.19.7 ("Classic," pre-Artemis) — the broker is built entirely in Java (`BrokerService` constructed programmatically, no `activemq.xml`), so `BrokerService.setPlugins(BrokerPlugin[])` is the natural extension point, not JAAS/config-file-based security.

**The concrete impact that justified doing this now rather than leaving it a documented limitation:** `session.{id}.events` is a `Topic` (broadcast — every subscriber gets every message), so a rogue listener there is a snooping/privacy problem. `player.{userId}.events` is a `Queue` — JMS delivers each message to exactly *one* consumer, whichever is attached. A rogue subscriber on a player queue doesn't just snoop; it can outright steal a real player's `MATCH_FOUND` push, and that player never learns they were matched. That's a correctness/availability bug triggerable by anyone who can open a TCP connection to the port, not just a privacy gap.

## Decisions

1. **One custom `BrokerFilter`, not the two-plugin split originally sketched.** The original "Deferred" note called for ActiveMQ's real `AuthenticationPlugin`/JAAS plus a separate `AuthorizationPlugin`/`AuthorizationMap`. Both of those are built for static, admin-configured ACLs — awkward for authorization that depends on live, per-session DB state (who's a participant right now). Since authentication and authorization both need to consult the same in-process `SessionManager`/`UserDao`/`GameSessionDao` objects anyway (no RPC needed — the broker runs embedded in the same JVM as the RMI services), a single `JmsSecurityPlugin` (`BrokerPlugin` whose `installPlugin()` returns a `BrokerFilter`) overrides `addConnection`/`addConsumer`/`addProducer` directly. This is the same extension point ActiveMQ's own `SimpleAuthenticationPlugin`/`AuthorizationBroker` are built on (confirmed by inspecting their bytecode: both throw plain `java.lang.SecurityException`, which ActiveMQ's transport layer converts to a client-side `JMSSecurityException` — `JmsSecurityPlugin` follows the identical convention rather than throwing `JMSSecurityException` directly).

2. **Authentication reuses the RMI session token as the JMS password**, exactly as originally sketched: `factory.createConnection(String.valueOf(userId), sessionToken)`. In `addConnection()`: if the username/password match a per-process service credential (a random UUID `ServerMain` generates once at startup, never exposed to client/admin code), the identity is `service`. Otherwise, `sessionManager.resolve(password)` resolves the token to a `userId` (an invalid/expired token throws `AuthenticationException`, mapped to `SecurityException`); `username` must equal `String.valueOf(userId)` (catches a token/username mismatch); the admin flag is read via `userDao.findById(userId).admin()`. The resolved identity is stashed on the `ConnectionContext` via a small `SecurityContext` subclass, read back by `addConsumer`/`addProducer` later on the same connection.

3. **Authorization**, in `addConsumer()`: the destination's physical name is regex-matched against `player.(\d+).events` / `session.(\d+).events`.
   - Player queue: only the matching `userId` (or `service`) may consume.
   - Session topic: `service` or an admin identity is always allowed; otherwise `gameSessionDao.findActiveById(sessionId)` must return a session where the caller is `player1Id`/`player2Id` — the identical check `PlayerServiceImpl` already performs for moves, reused rather than duplicated with a new DAO method.
   - Anything else — including a destination name that doesn't match either pattern — is denied by default, except `ActiveMQ.Advisory.*` destinations, which are allowlisted through unchecked so the broker's own internal bookkeeping (which the broker itself may use those destinations for) isn't accidentally broken by this filter.
   - `addProducer()`: only the `service` identity may publish, to any destination — a blanket rule, not per-destination logic, since `ActiveMqGameEventPublisher` is confirmed to be the only producer anywhere in the system.

4. **Real, structural consequence for existing code, exactly as the original sketch anticipated:** `RmiJmsServerConnection`/`RmiJmsAdminConnection` opened their JMS `Connection` in the constructor, before RMI login ever happened — that can't work anymore, since the token (the new credential) doesn't exist yet at that point. Both move the JMS connect logic into a private `connectJms(userId, sessionToken)`, called from `login()` immediately after `authService.login(...)` succeeds. Confirmed safe by tracing every real call site: `GameClientService`/`AdminClientService` both structurally gate every subscribe call behind a successful login (via `currentUser`/`sessionToken` fields only set in `login()`'s success path, and the UI flow itself — Login → Lobby/Dashboard — enforces the same ordering), so no code path can reach a subscribe call before login. `ServerConnection`/`AdminConnection` interfaces and their `InMemory*` test fakes are unaffected — this is purely internal to the real implementations. `close()` on both classes gains a null-guard, since the JMS connection field is no longer guaranteed non-null (e.g. the app is closed before ever logging in).

5. **Deliberately out of scope, exactly as the original sketch flagged:** only checked at JMS-connect time, not continuously — an already-open JMS connection isn't forcibly dropped if its token is later invalidated (e.g. by `SessionWatchdog`'s disconnect detection). Revisit only if that turns out to matter in practice. Also out of scope, decided during this pass: the broker's bind address stays `tcp://0.0.0.0` — clients connect across separate processes/hosts by design (`host` is a constructor parameter on both connection classes), so binding to localhost only would break that; authentication is what closes the actual risk here, not the bind address. No TLS on the connector.

## Architecture

```
server/jms/
├── JmsSecurityPlugin.java        new -- BrokerPlugin whose installPlugin() returns a
│                                    BrokerFilter doing both authn (addConnection) and
│                                    authz (addConsumer/addProducer)
├── EmbeddedJmsBroker.java        start(port, JmsSecurityPlugin) -- installs it via
│                                    broker.setPlugins(new BrokerPlugin[]{plugin})
└── JmsConnectionFactory.java     + createForBroker(url, username, password) overload
                                     (existing no-arg overload kept, used only by the
                                     disposable vm:// test broker, which has no plugin)

server/ServerMain.java            generates a per-process service credential (random UUID),
                                    builds JmsSecurityPlugin from the already-constructed
                                    sessionManager/userDao/gameSessionDao, wires both into
                                    EmbeddedJmsBroker.start() and
                                    JmsConnectionFactory.createForBroker()

client/communication/RmiJmsServerConnection.java   JMS connect moved out of the constructor
                                                     into connectJms(userId, token), called
                                                     from login() right after RMI login
                                                     succeeds; close() null-guarded
admin/communication/RmiJmsAdminConnection.java      identical restructuring, mirrored

src/test/.../server/jms/EmbeddedJmsBrokerTest.java  rewritten -- a real broker with the real
                                                      plugin installed, against
                                                      InMemoryUserDao/InMemoryGameSessionDao,
                                                      8 cases covering every authn/authz rule
```

## Data flow

**Connect:** a client calls `login()` over RMI and gets back a `LoginResultDTO(UserDTO, sessionToken)`. Still inside `login()`, it opens a JMS `Connection` with `username = String.valueOf(userId)`, `password = sessionToken`, against the same broker. `JmsSecurityPlugin.addConnection()` authenticates it against `SessionManager.resolve(...)` and stashes the resolved identity on the `ConnectionContext`.

**Subscribe:** `subscribeToPlayerQueue`/`subscribeToSessionTopic` create the destination and call `session.createConsumer(...)`. `JmsSecurityPlugin.addConsumer()` checks the stashed identity against the destination name before the broker attaches the consumer; a rejected attempt throws `SecurityException` broker-side, which surfaces to the caller as a `javax.jms.JMSSecurityException` synchronously from `createConsumer(...)`.

**Publish:** `ServerMain`'s single `ActiveMqGameEventPublisher` connection authenticates with the service credential at startup. Every `publishToPlayer`/`publishToSession` call goes through `JmsSecurityPlugin.addProducer()`, which allows only that identity.

## Wiring

- `ServerMain.startWithImpls()`: generate `jmsServiceUsername`/`jmsServicePassword` (`UUID.randomUUID()`), construct `JmsSecurityPlugin(sessionManager, userDao, gameSessionDao, jmsServiceUsername, jmsServicePassword)`, pass it into `EmbeddedJmsBroker.start(jmsPort, plugin)`, and pass the same credential into `JmsConnectionFactory.createForBroker(url, jmsServiceUsername, jmsServicePassword)` for the server's own publisher connection.

## Testing

- **`EmbeddedJmsBrokerTest`** (Docker-free — real broker + real `JmsSecurityPlugin`, against `InMemoryUserDao`/`InMemoryGameSessionDao`, no `docker compose` needed): anonymous connection rejected; connection with an invalid/unknown token rejected; a player cannot subscribe to another player's queue; a player can subscribe to their own; a non-participant cannot subscribe to a session topic; an admin can subscribe to any session topic; a non-service connection cannot publish (attempted via `createProducer` + `send`); and the pre-existing cross-process-delivery case, now requiring a valid token end-to-end (two fully independent `tcp://` connections — one publishing with the service credential, one subscribing with a real player token — still see the message, proving the fix doesn't break the legitimate path).
- Full suite: 191/191 (up from 184/184 before this milestone; 7 net-new assertions in the rewritten `EmbeddedJmsBrokerTest`, one existing case adapted for credentials).

## Out of scope (deferred, not forgotten)

- Live revocation of an already-open JMS connection when its token is later invalidated (e.g. by `SessionWatchdog`'s disconnect detection) — only checked once, at connect time.
- TLS on the JMS connector.
- Changing the broker's `tcp://0.0.0.0` bind address — clients connect across separate processes/hosts by design; authentication is what closes the actual risk.
