package com.chendev.ticketflow;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    // Singleton: one container for the entire JVM process.
    // All test classes share one Spring ApplicationContext because
    // DynamicPropertySource always returns the same connection URL.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ticketflow_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("postgres -c max_connections=300");

    // ── Redis ─────────────────────────────────────────────────────────────────

    // Singleton alongside PostgreSQL: same lifecycle, same rationale.
    //
    // Why Redis in IntegrationTestBase (not just in future Redis-specific tests)?
    //
    // Spring Boot auto-configures a RedisConnectionFactory as soon as
    // spring-boot-starter-data-redis is on the classpath. If no Redis is
    // reachable, the first actual Redis operation throws — but startup succeeds
    // (Lettuce connects lazily). However, once RedisInventoryAdapter is @Primary,
    // every test that triggers createOrder() will attempt a Redis call.
    //
    // Adding Redis here ensures:
    //   1. All existing tests continue to pass today (Redis available but unused).
    //   2. No test configuration changes are needed when RedisInventoryAdapter
    //      becomes active in Step 1-H — the container is already there.
    //   3. The single shared ApplicationContext is preserved — adding Redis
    //      to DynamicPropertySource here avoids a context reload per test class.
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    static {
        // Start both containers in parallel to keep total startup time low.
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);

        // Redis — Testcontainers maps the container's 6379 to a random host port.
        // We register that mapped port so Spring connects to the right place.
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    protected void cleanDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE order_status_history, orders, inventories, " +
                        "ticket_types, events, users RESTART IDENTITY CASCADE"
        );
        // Redis is not flushed here intentionally.
        // Phase 1 tests do not write to Redis.
        // When RedisInventoryAdapter becomes active (Step 1-H), individual
        // test classes that need a clean Redis state will call
        // redisTemplate.getConnectionFactory().getConnection().flushDb()
        // in their own @BeforeEach — keeping the cleanup scope as narrow
        // as possible.
    }
}