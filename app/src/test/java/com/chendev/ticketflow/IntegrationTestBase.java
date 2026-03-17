package com.chendev.ticketflow;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // Singleton pattern: one container for the entire JVM process.
    // Started once in the static initializer; Testcontainers registers
    // a JVM shutdown hook to stop it when the process exits.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ticketflow_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("postgres -c max_connections=300");

    static {
        postgres.start();
    }

    // Registers container URL into Spring's environment BEFORE context creation.
    // Because this always returns the same URL (singleton container),
    // all test classes share one Spring ApplicationContext.
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    protected void cleanDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE order_status_history, orders, inventories, " +
                        "ticket_types, events, users RESTART IDENTITY CASCADE"
        );
    }
}