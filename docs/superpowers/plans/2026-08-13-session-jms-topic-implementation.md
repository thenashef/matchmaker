# Per-Session JMS Topic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the opponent-notification gap left after Milestone 6 (game engine): today `PlayerServiceImpl.makeMove()` only tells the *mover* about the updated board (the synchronous RMI return value) — the opponent has no way to learn a move happened until they poll again. This plan adds an async push for both players, mirroring the pattern Milestone 5 already established for `joinQueue()` → `MATCH_FOUND`.

**Design decisions** (worked through in chat before this plan was written; no separate design doc this time, same as the generic-`GameEngine`-interface refactor — captured here instead):

1. **Topic, not Queue.** `MATCH_FOUND` uses a per-*player* Queue (`player.{userId}.events`) because exactly one client should consume each message. A move needs to reach *both* players in the session, and step 9's future admin live-monitor needs to observe it too, read-only, without stealing the message from a player. A JMS Topic (pub/sub — every subscriber gets every message) is the right primitive; a Queue would hand the message to only one subscriber. Destination name: `session.{sessionId}.events`, mirroring the existing `player.{userId}.events` naming.
2. **One new event type, `MOVE_MADE`.** No separate `GAME_ENDED` type — `GameStateDTO.status` already distinguishes `ACTIVE`/`FINISHED` (and `winnerId` is already on the DTO), so a subscriber inspects the payload it already gets rather than needing a second event type carrying the same information a different way.
3. **Publish unconditionally to both players**, not just the non-mover. Unlike `joinQueue()`'s Queue-per-player asymmetry (which exists only because a Queue can't broadcast), a Topic naturally reaches everyone subscribed to the session — the mover's own client can just always subscribe to its session topic and ignore/dedupe against the RMI response it already got.
4. **A publish failure must never fail the calling player's `makeMove()`** — same reasoning and same try/catch-and-log shape `joinQueue()` already uses in `PlayerServiceImpl`. The move already committed to the DB by the time we publish; a failed notification shouldn't undo or fail that.

**Architecture:** `GameEventPublisher` (interface, `server/jms/`) gains a second method, `publishToSession(int sessionId, GameEventDTO event)`, implemented in `ActiveMqGameEventPublisher` against a JMS `Topic` instead of the existing `Queue`. `PlayerServiceImpl.makeMove()` calls it once the move is durably recorded, publishing a `MOVE_MADE` event carrying the same `GameStateDTO` it's about to return to the RMI caller. No `ServerMain` changes are needed — it already constructs one `ActiveMqGameEventPublisher` and injects it into `PlayerServiceImpl`; that same instance just gets used a second way.

**Tech Stack:** unchanged — Java 21, JUnit 5, embedded ActiveMQ (`vm://` transport).

## Global Constraints

- No Docker required — the new JMS integration test runs against the same embedded `vm://` broker as the existing `GameEventPublisherJmsIntegrationTest`.
- Every class implementing `GameEventPublisher` (real and test fakes) must implement both methods — adding `publishToSession` to the interface is a breaking change for `InMemoryGameEventPublisher` and `FailingGameEventPublisher`, both of which need updating in Task 1.
- `PlayerServiceImpl`'s constructor signature does not change — `gameEventPublisher` is already a field, injected the same way `joinQueue()` already uses it.

---

### Task 1: `publishToSession` on `GameEventPublisher`

**Files:**
- Modify: `src/main/java/com/matchmaker/common/enums/GameEventType.java` (add `MOVE_MADE`)
- Modify: `src/main/java/com/matchmaker/server/jms/GameEventPublisher.java` (add method to interface)
- Modify: `src/main/java/com/matchmaker/server/jms/ActiveMqGameEventPublisher.java` (Topic-based implementation)
- Modify: `src/test/java/com/matchmaker/server/jms/InMemoryGameEventPublisher.java` (track session publishes)
- Modify: `src/test/java/com/matchmaker/server/jms/FailingGameEventPublisher.java` (implement new method)
- Modify: `src/test/java/com/matchmaker/server/jms/GameEventPublisherJmsIntegrationTest.java` (real-broker proof)

**Interfaces:**
- Produces: `GameEventPublisher.publishToSession(int sessionId, GameEventDTO event)` — Task 2's `PlayerServiceImpl.makeMove()` calls this.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/matchmaker/server/jms/GameEventPublisherJmsIntegrationTest.java` (add `import javax.jms.Topic;` alongside the existing `javax.jms.*` imports):

```java
    @Test
    void publishToSession_realConsumerReceivesTheEvent() throws Exception {
        int sessionId = 7;
        Topic topic = session.createTopic("session." + sessionId + ".events");
        MessageConsumer consumer = session.createConsumer(topic);

        GameStateDTO updatedSession = new GameStateDTO(sessionId, 1, 42, 99,
                GameStatus.ACTIVE, 99, null, "{\"pieces\":{}}");
        GameEventDTO event = new GameEventDTO(GameEventType.MOVE_MADE, sessionId, updatedSession);

        publisher.publishToSession(sessionId, event);

        Message received = consumer.receive(2000);

        assertNotNull(received, "expected a message to arrive on the session's topic");
        assertInstanceOf(ObjectMessage.class, received);
        GameEventDTO receivedEvent = (GameEventDTO) ((ObjectMessage) received).getObject();
        assertEquals(GameEventType.MOVE_MADE, receivedEvent.getType());
        assertEquals(sessionId, receivedEvent.getSessionId());
    }

    @Test
    void publishToSession_bothSubscribersReceiveTheirOwnCopy() throws Exception {
        int sessionId = 7;
        Topic topic = session.createTopic("session." + sessionId + ".events");
        MessageConsumer player1Consumer = session.createConsumer(topic);
        MessageConsumer player2Consumer = session.createConsumer(topic);

        GameStateDTO updatedSession = new GameStateDTO(sessionId, 1, 42, 99,
                GameStatus.ACTIVE, 99, null, "{\"pieces\":{}}");
        GameEventDTO event = new GameEventDTO(GameEventType.MOVE_MADE, sessionId, updatedSession);

        publisher.publishToSession(sessionId, event);

        Message receivedByPlayer1 = player1Consumer.receive(2000);
        Message receivedByPlayer2 = player2Consumer.receive(2000);

        assertNotNull(receivedByPlayer1, "expected player 1's subscription to receive the event");
        assertNotNull(receivedByPlayer2, "expected player 2's subscription to receive the event -- "
                + "a JMS Queue would only have delivered this to one of the two consumers");
    }
```

This second test is the one that actually proves the Topic-vs-Queue design decision, not just that publishing works at all.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=GameEventPublisherJmsIntegrationTest`
Expected: compile error — `publishToSession` does not exist yet on `GameEventPublisher`/the `publisher` variable's type.

- [ ] **Step 3: Add `MOVE_MADE` to `GameEventType`**

```java
package com.matchmaker.common.enums;

public enum GameEventType {
    MATCH_FOUND,
    MOVE_MADE
}
```

- [ ] **Step 4: Add the method to the `GameEventPublisher` interface**

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

public interface GameEventPublisher {

    void publishToPlayer(int userId, GameEventDTO event);

    void publishToSession(int sessionId, GameEventDTO event);
}
```

- [ ] **Step 5: Implement it in `ActiveMqGameEventPublisher`**

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.Topic;

public class ActiveMqGameEventPublisher implements GameEventPublisher {

    private final Session session;

    public ActiveMqGameEventPublisher(Session session) {
        this.session = session;
    }

    // A javax.jms.Session (and everything created from it) may only be used by one thread at a
    // time per the JMS spec -- this Session is shared by every RMI caller for the life of the
    // server, so publishes must be serialized here rather than relying on the broker client's
    // undocumented internal locking.
    @Override
    public synchronized void publishToPlayer(int userId, GameEventDTO event) {
        try {
            Queue queue = session.createQueue("player." + userId + ".events");
            publish(queue, event);
        } catch (JMSException e) {
            throw new JmsPublishException("Failed to publish event to player " + userId, e);
        }
    }

    @Override
    public synchronized void publishToSession(int sessionId, GameEventDTO event) {
        try {
            Topic topic = session.createTopic("session." + sessionId + ".events");
            publish(topic, event);
        } catch (JMSException e) {
            throw new JmsPublishException("Failed to publish event to session " + sessionId, e);
        }
    }

    private void publish(Destination destination, GameEventDTO event) throws JMSException {
        MessageProducer producer = session.createProducer(destination);
        try {
            ObjectMessage message = session.createObjectMessage(event);
            producer.send(message);
        } finally {
            producer.close();
        }
    }
}
```

- [ ] **Step 6: Update the test fakes**

`src/test/java/com/matchmaker/server/jms/InMemoryGameEventPublisher.java`:

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

import java.util.ArrayList;
import java.util.List;

public class InMemoryGameEventPublisher implements GameEventPublisher {

    public record PublishedEvent(int userId, GameEventDTO event) {
    }

    public record PublishedSessionEvent(int sessionId, GameEventDTO event) {
    }

    private final List<PublishedEvent> published = new ArrayList<>();
    private final List<PublishedSessionEvent> publishedToSessions = new ArrayList<>();

    @Override
    public void publishToPlayer(int userId, GameEventDTO event) {
        published.add(new PublishedEvent(userId, event));
    }

    @Override
    public void publishToSession(int sessionId, GameEventDTO event) {
        publishedToSessions.add(new PublishedSessionEvent(sessionId, event));
    }

    public List<PublishedEvent> published() {
        return published;
    }

    public List<PublishedSessionEvent> publishedToSessions() {
        return publishedToSessions;
    }
}
```

`src/test/java/com/matchmaker/server/jms/FailingGameEventPublisher.java`:

```java
package com.matchmaker.server.jms;

import com.matchmaker.common.dto.GameEventDTO;

public class FailingGameEventPublisher implements GameEventPublisher {

    @Override
    public void publishToPlayer(int userId, GameEventDTO event) {
        throw new JmsPublishException("simulated JMS failure", new RuntimeException("broker unreachable"));
    }

    @Override
    public void publishToSession(int sessionId, GameEventDTO event) {
        throw new JmsPublishException("simulated JMS failure", new RuntimeException("broker unreachable"));
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn test -Dtest=GameEventPublisherJmsIntegrationTest`
Expected: PASS (4 tests: the 2 pre-existing `publishToPlayer` tests plus the 2 new `publishToSession` tests).

- [ ] **Step 8: Run the full suite to confirm nothing else broke**

Run: `mvn test`
Expected: all existing tests still pass — nothing outside `server/jms` should reference `GameEventPublisher` yet, but this confirms the interface-widening compiled cleanly everywhere.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/matchmaker/common/enums/GameEventType.java \
        src/main/java/com/matchmaker/server/jms/GameEventPublisher.java \
        src/main/java/com/matchmaker/server/jms/ActiveMqGameEventPublisher.java \
        src/test/java/com/matchmaker/server/jms/InMemoryGameEventPublisher.java \
        src/test/java/com/matchmaker/server/jms/FailingGameEventPublisher.java \
        src/test/java/com/matchmaker/server/jms/GameEventPublisherJmsIntegrationTest.java
git commit -m "Add per-session JMS Topic to GameEventPublisher"
```

---

### Task 2: Wire `MOVE_MADE` into `PlayerServiceImpl.makeMove()`

**Files:**
- Modify: `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`
- Modify: `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`

**Interfaces:**
- Consumes: `GameEventPublisher.publishToSession(int, GameEventDTO)` (Task 1).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java`, alongside the other `makeMove_*` tests:

```java
    @Test
    void makeMove_legalMove_publishesMoveMadeEventToSession() throws Exception {
        String initialBoard = new CheckersEngine().initialState();
        gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

        GameStateDTO result = playerService.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"a4\"]}");

        assertEquals(1, gameEventPublisher.publishedToSessions().size());
        InMemoryGameEventPublisher.PublishedSessionEvent published = gameEventPublisher.publishedToSessions().get(0);
        assertEquals(1, published.sessionId());
        assertEquals(GameEventType.MOVE_MADE, published.event().getType());
        assertEquals(result.getCurrentTurnUserId(), published.event().getGameState().getCurrentTurnUserId());
    }

    @Test
    void makeMove_publisherThrows_stillReturnsCallersOwnUpdatedState() throws Exception {
        PlayerServiceImpl playerServiceWithFailingPublisher = new PlayerServiceImpl(
                sessionManager, gameSessionDao, gameTypeDao, matchmakingQueue,
                new FailingGameEventPublisher(), new CheckersEngine());
        try {
            String initialBoard = new CheckersEngine().initialState();
            gameSessionDao.addActiveSession(new GameStateDTO(1, 1, 1, 2, GameStatus.ACTIVE, 1, null, initialBoard));

            GameStateDTO result = assertDoesNotThrow(
                    () -> playerServiceWithFailingPublisher.makeMove(sessionToken, 1, "{\"path\":[\"b3\",\"a4\"]}"));

            assertEquals(2, result.getCurrentTurnUserId());
        } finally {
            UnicastRemoteObject.unexportObject(playerServiceWithFailingPublisher, true);
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: compile error — `gameEventPublisher.publishedToSessions()` doesn't exist yet on the type used in this test... actually it does after Task 1; the real failure here is a logic failure, not compile: `publishedToSessions()` is empty (0, not 1) since `makeMove()` doesn't publish yet. Run and confirm that specific assertion failure.

- [ ] **Step 3: Update `makeMove()`**

In `src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java`, replace the tail of `makeMove()` (the final `try`/`catch(ConcurrentGameUpdateException)`/`return` block) with:

```java
        GameStateDTO persistedSession;
        try {
            persistedSession = gameSessionDao.recordMove(updatedSession, userId, movePayload);
        } catch (ConcurrentGameUpdateException e) {
            // Someone else's call for this same session committed first since we read it --
            // from this caller's perspective that means the turn/status they validated
            // against is stale, which is exactly what NotYourTurnException communicates.
            throw new NotYourTurnException("Session " + gameSessionId + " changed since it was read -- "
                    + "it is no longer user " + userId + "'s turn (or the game already ended)");
        }

        try {
            gameEventPublisher.publishToSession(gameSessionId,
                    new GameEventDTO(GameEventType.MOVE_MADE, gameSessionId, persistedSession));
        } catch (JmsPublishException e) {
            // The move already committed to the DB -- a failed notification shouldn't undo or
            // fail the mover's own already-successful result. Mirrors joinQueue()'s handling.
            System.err.println("Failed to notify session " + gameSessionId + " of move: " + e.getMessage());
        }

        return persistedSession;
    }
```

(No new imports needed — `GameEventDTO`, `GameEventType`, and `JmsPublishException` are already imported in this file for `joinQueue()`.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn test -Dtest=PlayerServiceImplTest`
Expected: PASS, including all pre-existing `makeMove_*` tests (the return value/exception behavior is unchanged, only the extra publish side effect is new).

- [ ] **Step 5: Run the full suite**

Run: `mvn test` (Docker not required for anything touched by this task, but run `docker compose up -d && mvn test` if you want the full 4-DAO-test tier too)
Expected: all tests pass.

- [ ] **Step 6: Manually confirm the server still starts**

Run: `mvn exec:java`
Expected: the usual startup banner, no exceptions. Stop with Ctrl-C.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matchmaker/server/rmi/PlayerServiceImpl.java \
        src/test/java/com/matchmaker/server/rmi/PlayerServiceImplTest.java
git commit -m "Publish MOVE_MADE to the session's JMS topic on every makeMove()"
```

---

## Post-plan status update

After Task 2's commit, update `docs/build-plan.md`: fold this work in as a short addendum to Milestone 6 (or a small "Milestone 6.5" — the user's call), noting the notification gap it closes, and update "Next Steps" to point at step 8 (JavaFX player client) as the sole remaining item, since this was the one thing the build plan said to close "before or alongside step 8." Also update `docs/project-structure.md`'s `server/jms/` bullet, which currently says "there's no per-session JMS *Topic* yet ... deferred" — that sentence becomes stale the moment Task 1 merges. Same for the `GameEventPublisher`/`GameEventType` mentions elsewhere in that file. This is a direct doc edit once both tasks are verified green, not a plan task with its own test — same pattern used after every prior milestone.
