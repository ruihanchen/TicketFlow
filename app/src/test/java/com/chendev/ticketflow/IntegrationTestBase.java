package com.chendev.ticketflow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

// singleton containers shared across the entire suite.
// per-class containers would reload the Spring context on every test class.
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // must match docker-compose.yml(update both together).
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String REDIS_IMAGE    = "redis:7.4.1-alpine";
    private static final int    REDIS_PORT     = 6379;

    // PID-scoped so repeated local runs and parallel Surefire forks never share stale offsets
    private static final String DEBEZIUM_TEMP_DIR =
            System.getProperty("java.io.tmpdir") + "/ticketflow-debezium-" +
                    ProcessHandle.current().pid();

    // never closed explicitly, testcontainers' Ryuk reaper handles cleanup on JVM exit
    static final PostgreSQLContainer<?> POSTGRES;
    static final GenericContainer<?>    REDIS;

    static {
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("ticketflow_test")
                .withUsername("test")
                .withPassword("test");
        // wal_level=logical required for Debezium; fsync=off cuts startup time safely in tests
        pg.setCommand("postgres", "-c", "fsync=off", "-c", "wal_level=logical");
        pg.start();
        POSTGRES = pg;

        @SuppressWarnings("resource")
        GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(REDIS_PORT);
        redis.start();
        REDIS = redis;
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Testcontainers assigns a random host port; can't hardcode 6379
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));

        // each run gets a fresh Postgres container with a new WAL sequence.
        // without isolation, Debezium reads the stale offset and CDC silently stalls.
        registry.add("debezium.offset.storage-file",
                () -> DEBEZIUM_TEMP_DIR + "/offsets.dat");
        registry.add("debezium.offset.schema-history-file",
                () -> DEBEZIUM_TEMP_DIR + "/schema-history.dat");
    }
}