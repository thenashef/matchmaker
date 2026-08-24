package com.matchmaker.server.dao;

import com.matchmaker.server.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserDaoTest {

    private static final DataSource DATA_SOURCE = DataSourceFactory.create();

    private final UserDao userDao = new JdbcUserDao(DATA_SOURCE);

    @BeforeEach
    void cleanTables() throws Exception {
        TestDatabase.cleanAll(DATA_SOURCE);
    }

    @Test
    void insert_newUsername_returnsRecordWithDefaults() {
        Optional<UserRecord> result = userDao.insert("alice", "hashed-password");

        assertTrue(result.isPresent());
        UserRecord record = result.get();
        assertTrue(record.id() > 0);
        assertEquals("alice", record.username());
        assertEquals("hashed-password", record.passwordHash());
        assertFalse(record.admin());
        assertEquals(0, record.wins());
        assertEquals(0, record.losses());
        assertEquals(0, record.draws());
        assertEquals(1200, record.rating());
        assertNotNull(record.createdAt());
    }

    @Test
    void insert_withIsAdminTrue_persistsTheAdminFlag() {
        Optional<UserRecord> result = userDao.insert("newadmin", "hashed-password", true);

        assertTrue(result.isPresent());
        assertTrue(result.get().admin());
        assertTrue(userDao.findById(result.get().id()).get().admin(), "the flag must actually be persisted, not just returned");
    }

    @Test
    void insert_twoArgOverload_stillDefaultsToNonAdmin() {
        Optional<UserRecord> result = userDao.insert("alice", "hashed-password");

        assertFalse(result.get().admin());
    }

    @Test
    void findAdminUserIds_returnsOnlyAdmins() {
        int adminId = userDao.insert("admin1", "hash", true).get().id();
        userDao.insert("player1", "hash", false);

        Set<Integer> adminIds = userDao.findAdminUserIds();

        assertEquals(Set.of(adminId), adminIds);
    }

    @Test
    void insert_duplicateUsername_returnsEmpty() {
        userDao.insert("bob", "hash1");

        Optional<UserRecord> result = userDao.insert("bob", "hash2");

        assertTrue(result.isEmpty());
    }

    @Test
    void findByUsername_existingUser_returnsRecord() {
        userDao.insert("carol", "hash");

        Optional<UserRecord> result = userDao.findByUsername("carol");

        assertTrue(result.isPresent());
        assertEquals("carol", result.get().username());
    }

    @Test
    void findByUsername_unknownUser_returnsEmpty() {
        Optional<UserRecord> result = userDao.findByUsername("nobody");

        assertTrue(result.isEmpty());
    }

    @Test
    void findById_existingUser_returnsRecord() {
        Optional<UserRecord> inserted = userDao.insert("carol", "hash");

        Optional<UserRecord> found = userDao.findById(inserted.get().id());

        assertTrue(found.isPresent());
        assertEquals("carol", found.get().username());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        assertTrue(userDao.findById(999999).isEmpty());
    }

    @Test
    void findAll_returnsEveryUser() {
        userDao.insert("carol", "hash");
        userDao.insert("dave", "hash");

        List<UserRecord> all = userDao.findAll();

        assertEquals(2, all.size());
    }

    @Test
    void setAdmin_existingUser_persistsTheFlag() {
        int id = userDao.insert("alice", "hash").get().id();

        Optional<UserRecord> updated = userDao.setAdmin(id, true);

        assertTrue(updated.isPresent());
        assertTrue(updated.get().admin());
        assertTrue(userDao.findById(id).get().admin(), "the flag must actually be persisted, not just returned");
    }

    @Test
    void setAdmin_unknownId_returnsEmpty() {
        assertTrue(userDao.setAdmin(999999, true).isEmpty());
    }
}
