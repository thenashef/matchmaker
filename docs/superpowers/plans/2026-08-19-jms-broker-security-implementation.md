# JMS Broker-Level Authentication & Authorization Implementation Plan

**Goal:** Implement `docs/specs/2026-08-19-jms-broker-security-design.md` in full — broker-side authentication (RMI session token reused as the JMS credential) and per-destination authorization (own player queue, participant-or-admin on a session topic, service-only publishing). See that doc for full rationale; this plan is the "how."

**Note on how this landed:** decided and executed directly in chat against the smallest-possible-change framing (not a subagent-driven multi-task branch like most earlier milestones) — one pass, task-ordered below to match this project's usual plan shape, written up afterward once the code and tests were green. `mvn test`/full-suite checkpoints below are what was actually run, not aspirational.

**Tech stack:** unchanged — Java 21, JUnit 5, ActiveMQ 5.19.7's `BrokerFilter`/`BrokerPlugin` extension point (`org.apache.activemq.broker`), no new dependencies.

## Global constraints

- No new credential system — the JMS password *is* the existing RMI session token; the JMS broker validates it through the same `SessionManager` the RMI services already share (same JVM, no RPC).
- `EmbeddedJmsBroker.start()` and `JmsConnectionFactory.createForBroker()` are the only signature-breaking changes; both have exactly one caller each in `main` (`ServerMain`) plus test call sites, all updated in this plan.
- Match existing style: no comments except where a hidden constraint/invariant needs explaining.

---

### Task 1: `JmsSecurityPlugin` (authentication + authorization)

**Files:**
- New: `src/main/java/com/matchmaker/server/jms/JmsSecurityPlugin.java`

**Steps:**
1. Inspected the ActiveMQ 5.19.7 broker/client jars directly (`javap` against the extracted classes) to confirm the exact extension-point shape before writing any code: `BrokerPlugin.installPlugin(Broker)` returns a `Broker`; `BrokerFilter` overrides `addConnection`/`addConsumer`/`addProducer` (all `throws Exception`); `ConnectionContext.setSecurityContext(SecurityContext)`/`getSecurityContext()`; `SecurityContext` is abstract, constructed with a username, only `getPrincipals()` is abstract to implement; `ConnectionInfo.getUserName()`/`getPassword()`; `ConsumerInfo`/`ProducerInfo.getDestination()`; `ActiveMQDestination.getPhysicalName()`. Also confirmed (by inspecting `SimpleAuthenticationBroker`/`AuthorizationBroker`'s bytecode) that ActiveMQ's own security brokers throw plain `java.lang.SecurityException`, not `javax.jms.JMSSecurityException` — the transport layer converts it — so `JmsSecurityPlugin` follows that same convention.
2. `JmsSecurityPlugin implements BrokerPlugin`, constructor takes `SessionManager`, `UserDao`, `GameSessionDao`, `serviceUsername`, `servicePassword`. `installPlugin(Broker)` returns a private inner `SecurityBrokerFilter extends BrokerFilter`.
3. `addConnection`: authenticate per Decision 2 in the design doc (service-credential check first, else `sessionManager.resolve(password)` + username/userId cross-check + admin lookup), stash the result as a private `Identity extends SecurityContext` via `context.setSecurityContext(...)`.
4. `addConsumer`: regex-match the destination's physical name against `player.(\d+).events`/`session.(\d+).events`, apply the ownership/participancy/admin rules from Decision 3, allow `ActiveMQ.Advisory.*` through unchecked, deny anything else by default.
5. `addProducer`: allow only the service identity (again allowlisting `ActiveMQ.Advisory.*`).
6. Compiled standalone (`mvn compile`) before wiring anything else in, to catch any API mismatch early.

---

### Task 2: Wire the plugin into `EmbeddedJmsBroker` and `JmsConnectionFactory`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/jms/EmbeddedJmsBroker.java`
- Modify: `src/main/java/com/matchmaker/server/jms/JmsConnectionFactory.java`

**Steps:**
1. `EmbeddedJmsBroker.start(int port, JmsSecurityPlugin securityPlugin)` — added the parameter, `broker.setPlugins(new BrokerPlugin[]{securityPlugin})` before `addConnector(...)`.
2. `JmsConnectionFactory`: added `createForBroker(String brokerUrl, String username, String password)` calling `factory.createConnection(username, password)`. Left the existing no-arg `createForBroker(String brokerUrl)` untouched — it's only used by the disposable `vm://` test broker (`create()`), which never gets the security plugin installed and doesn't need credentials.

---

### Task 3: Wire service credentials into `ServerMain`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/ServerMain.java`

**Steps:**
1. In `startWithImpls()`, generate `jmsServiceUsername = "matchmaker-service"` and `jmsServicePassword = UUID.randomUUID().toString()` right before starting the broker.
2. Construct `JmsSecurityPlugin` from the `sessionManager`/`userDao`/`gameSessionDao` instances already built earlier in the same method (no new wiring needed there — they already exist for the RMI service impls).
3. Pass the plugin into `EmbeddedJmsBroker.start(jmsPort, jmsSecurityPlugin)` and the service credential into `JmsConnectionFactory.createForBroker(url, jmsServiceUsername, jmsServicePassword)`.

---

### Task 4: Defer JMS connect to post-login in both client connection classes

**Files:**
- Modify: `src/main/java/com/matchmaker/client/communication/RmiJmsServerConnection.java`
- Modify: `src/main/java/com/matchmaker/admin/communication/RmiJmsAdminConnection.java`

**Steps:**
1. Before touching either file, traced every real call site of `subscribeToPlayerQueue`/`subscribeToSessionTopic` (both client and admin) to confirm none can be reached before a successful `login()` — `GameClientService`/`AdminClientService` both gate their whole flow behind `currentUser`/`sessionToken`, only set in `login()`'s success path, and the UI navigation (Login → Lobby/Dashboard) reinforces the same ordering. Confirmed `close()` is never called before construction succeeds either (a constructor failure never assigns the field, and callers null-check before calling `close()`), so moving the JMS connect out of the constructor doesn't introduce a new NPE risk on that path — but the field itself is no longer guaranteed non-null once login can fail or never happen, so `close()` needed its own guard regardless.
2. `jmsConnection`/`jmsSession` fields changed from `final` to mutable; constructor keeps only the RMI registry lookups plus stashing `host`/`jmsPort`.
3. New private `connectJms(int userId, String sessionToken)` holds the JMS connect logic that used to live in the constructor, now authenticating via `factory.createConnection(String.valueOf(userId), sessionToken)`.
4. `login(...)` calls `authService.login(...)` first (unchanged error handling), then calls `connectJms(result.getUser().getId(), result.getSessionToken())` before returning the result.
5. `close()` gains `if (jmsConnection == null) return;` at the top.
6. Mirrored identically in `RmiJmsAdminConnection`.

---

### Task 5: Rewrite `EmbeddedJmsBrokerTest`

**Files:**
- Modify: `src/test/java/com/matchmaker/server/jms/EmbeddedJmsBrokerTest.java`

**Steps:**
1. Confirmed `InMemoryUserDao`/`InMemoryGameSessionDao` (already existing test fakes under `src/test/java/com/matchmaker/server/dao/`) had everything needed — `insert()`/`markAdmin()`/`findById()` and `addActiveSession()`/`findActiveById()` respectively — so no new test fixture classes were needed.
2. `@BeforeEach` now builds a real `SessionManager`/`InMemoryUserDao`/`InMemoryGameSessionDao`, constructs a real `JmsSecurityPlugin` from them plus a fixed test service credential, and starts a real broker with it via `EmbeddedJmsBroker.start(TEST_PORT, plugin)`.
3. Added `registerUser(username)`, `serviceConnection()`, `userConnection(userId, token)` helpers; every opened `Connection` is tracked in a list and closed in `@AfterEach` alongside `broker.stop()`.
4. Adapted the original cross-process-delivery test to use a real registered user + token instead of an anonymous connection, and to seed an active session the subscribing user actually participates in (needed now that the topic subscribe is authorized).
5. Added 7 new cases: `anonymousConnectionIsRejected`, `connectionWithAnInvalidTokenIsRejected`, `playerCannotSubscribeToAnotherPlayersQueue`, `playerCanSubscribeToTheirOwnQueue`, `nonParticipantCannotSubscribeToASessionTopic`, `adminCanSubscribeToAnySessionTopic`, `nonServiceConnectionCannotPublish` (this last one both creates a producer and sends a message, to catch the rejection whichever point ActiveMQ enforces it at).
6. Ran `mvn -o test -Dtest=EmbeddedJmsBrokerTest,ServerMainTest` — 9/9 green (8 in `EmbeddedJmsBrokerTest`, 1 in `ServerMainTest`, confirming `ServerMain`'s own startup path still works with the new signatures).

---

### Task 6: Full suite

**Steps:**
1. `mvn -o compile` and `mvn -o test-compile` — both clean, no signature mismatches anywhere else in the tree.
2. `mvn -o test` (full suite) — 191/191 passing (up from 184/184 before this work), no Docker required for any of the JMS-security-related tests.

---

## Post-plan status update

Once the full suite was confirmed green: updated `docs/build-plan.md` — folded this work in as Milestone 9.5 (matching the Milestone 6.5 numbering precedent for a same-day, roadmap-adjacent addition), moved the "JMS broker-security work" bullet in "Next Steps" from deferred to done, replaced the old "Deferred: JMS broker-level authentication/authorization" section with a pointer to the new milestone, and updated the passing-test count. Updated `docs/project-structure.md`'s `server/jms/` section for `JmsSecurityPlugin`/the new `EmbeddedJmsBroker`/`JmsConnectionFactory` signatures, the `server/ServerMain.java` bullet for the service-credential wiring, both `client/communication`/`admin/communication` bullets for the post-login JMS connect, and the docs tree listing for this design doc + implementation plan pair.
