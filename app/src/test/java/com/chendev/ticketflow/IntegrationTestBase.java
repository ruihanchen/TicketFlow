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
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // ── PostgreSQL ────────────────────────────────────────────────────────────

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ticketflow_test")
                    .withUsername("test")
                    .withPassword("test")
                    // max_connections must exceed HikariCP pool ceiling (420).
                    // 500 = 420 pool + 80 buffer for Postgres background processes.
                    .withCommand("postgres -c max_connections=500");

    // ── Redis ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    // ── Kafka ─────────────────────────────────────────────────────────────────
    //
    // KafkaContainer uses Confluent's cp-kafka image which supports
    // single-node KRaft mode out of the box — no Zookeeper needed.
    //
    // Why add Kafka to IntegrationTestBase (not just Kafka-specific tests)?
    // spring-kafka auto-configures a KafkaAdmin and producer/consumer on startup.
    // If no Kafka broker is reachable, the health check warns but the app still
    // starts (Kafka connects lazily). However, once consumers are registered,
    // the first message delivery attempt requires a live broker.
    //
    // Adding Kafka here ensures:
    //   1. All existing tests continue to pass today (Kafka available but unused).
    //   2. No test configuration changes needed when Step 3-C activates the consumer.
    //   3. The single shared ApplicationContext is preserved — registering Kafka
    //      in DynamicPropertySource here avoids a context reload per test class.
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        // Start all three containers in parallel to minimise total startup time.
        postgres.start();
        redis.start();
        kafka.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);

        // Redis
        registry.add("spring.data.redis.host",      redis::getHost);
        registry.add("spring.data.redis.port",      () -> redis.getMappedPort(6379));

        // Kafka — Testcontainers maps the broker port to a random host port.
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
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
        // FLUSHDB is unconditional and cheap in a test container.
        // Prevents stale inventory or idempotency keys leaking between tests.
        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();

        // Kafka topics are not cleaned between tests — each test uses unique
        // consumer group IDs or dedicated topics to avoid message interference.
    }
}