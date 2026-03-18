package com.chendev.ticketflow;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // ── PostgreSQL ────────────────────────────────────────────────────────────

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ticketflow_test")
                    .withUsername("test")
                    .withPassword("test")
                    // max_connections must exceed the HikariCP pool ceiling (420).
                    // 500 = 420 pool + 80 buffer for internal Postgres background connections.
                    // Phase 1 used 300 (sized for the 150-connection pool).
                    .withCommand("postgres -c max_connections=500");

    // ── Redis ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.data.redis.host",      redis::getHost);
        registry.add("spring.data.redis.port",      () -> redis.getMappedPort(6379));
    }

    @Autowired private JdbcTemplate        jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    protected void cleanDatabase() {
        // Wipe all DB tables and reset sequences.
        jdbcTemplate.execute(
                "TRUNCATE TABLE order_status_history, orders, inventories, " +
                        "ticket_types, events, users RESTART IDENTITY CASCADE"
        );

        // Flush all Redis keys written by the previous test.
        //
        // Why flush here rather than selectively deleting inventory keys?
        // Selective deletion (KEYS pattern + DEL) is fragile — if a new key
        // prefix is introduced, tests start leaking state silently. FLUSHDB
        // is unconditional and cheap in a test container with no real data.
        //
        // This flush is essential now that RedisInventoryAdapter is @Primary:
        // a stale "inventory:ticketType:1 = 0" key left by the previous test
        // would bypass lazy-load and cause the next test to see sold-out
        // inventory from the start, producing non-deterministic failures.
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();
    }
}