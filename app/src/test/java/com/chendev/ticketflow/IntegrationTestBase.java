package com.chendev.ticketflow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

//base class for integration tests. One container instance shared across the suite;
//per-class containers would reload the Spring context on every test class.
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES;

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("ticketflow_test")
                .withUsername("test")
                .withPassword("test");
        //wal_level=logical required for Debezium CDC; fsync=off preserves startup speed
        POSTGRES.setCommand("postgres", "-c", "fsync=off", "-c", "wal_level=logical");
        POSTGRES.start();

        REDIS = new GenericContainer<>("redis:7.4.1-alpine")
                .withExposedPorts(6379);
        REDIS.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        //Testcontainers assigns a random host port; can't hardcode 6379
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}