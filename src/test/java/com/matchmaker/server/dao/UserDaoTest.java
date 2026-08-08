package com.matchmaker.server.dao;

import com.matchmaker.server.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Optional;

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
}
