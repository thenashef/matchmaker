package com.matchmaker.server.dao;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DataSourceFactoryTest {

    @Test
    void create_buildsHikariDataSourceFromDbProperties() {
        DataSource dataSource = DataSourceFactory.create();

        HikariDataSource hikari = assertInstanceOf(HikariDataSource.class, dataSource);
        assertEquals("jdbc:mysql://localhost:3306/matchmaker_test?connectionTimeZone=SERVER", hikari.getJdbcUrl());
        assertEquals("matchmaker", hikari.getUsername());

        hikari.close();
    }
}
