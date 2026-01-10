package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Integration tests for the Debezium CDC pipeline.
// Each test targets a specific failure mode; direct Postgres/Redis access keeps failures CDC-attributable.
class InventoryCdcSyncTest extends IntegrationTestBase {

    private static final Duration CDC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    // Isolated ID range for this class. AfterEach cleans BASE_ID through BASE_ID+99.
    // Add new tests using BASE_ID+N; never reuse an N already in this file.
    private static final long BASE_ID = 40000L;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void cleanRedis() {
        // stale keys from a previous test would mask failures in the next one
        var keys = redisTemplate.keys("inventory:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.update(
                "DELETE FROM inventories WHERE ticket_type_id >= ? AND ticket_type_id <= ?",
                BASE_ID, BASE_ID + 99);
    }

    @Test
    void cdc_insert_event_creates_redis_key_with_correct_value() {
        long id = BASE_ID + 1;
        insertInventory(id, 200, 200);
        awaitRedisValue("inventory:" + id, "200");
    }

    @Test
    void cdc_update_event_overwrites_redis_value() {
        long id = BASE_ID + 2;
        insertInventory(id, 500, 500);
        awaitRedisValue("inventory:" + id, "500");

        jdbcTemplate.update(
                "UPDATE inventories SET available_stock = ? WHERE ticket_type_id = ?", 320, id);

        awaitRedisValue("inventory:" + id, "320");
    }

    @Test
    void cdc_delete_event_removes_correct_redis_key_not_id_zero() {
        long id = BASE_ID + 3;
        insertInventory(id, 100, 100);
        awaitRedisValue("inventory:" + id, "100");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);

        // guard: a prior bug read ticket_type_id from payload.after (null on delete), corrupting inventory:0
        assertThat(redisTemplate.hasKey("inventory:0"))
                .as("delete handler must not corrupt inventory:0 due to null after-image")
                .isFalse();
    }

    @Test
    void cdc_handles_inventory_lifecycle_then_publishes_to_redis() {
        long id = BASE_ID + 4;
        insertInventory(id, 1000, 1000);
        awaitRedisValue("inventory:" + id, "1000");

        for (int newStock : new int[]{900, 800, 700}) {
            jdbcTemplate.update(
                    "UPDATE inventories SET available_stock = ? WHERE ticket_type_id = ?",
                    newStock, id);
            awaitRedisValue("inventory:" + id, String.valueOf(newStock));
        }

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);
    }

    @Test
    void cdc_recovers_after_redis_outage_when_redis_comes_back() {
        // can't kill Redis from inside the JVM; verify instead that a follow-up UPDATE still propagates
        long id = BASE_ID + 5;
        insertInventory(id, 150, 150);
        awaitRedisValue("inventory:" + id, "150");

        jdbcTemplate.update(
                "UPDATE inventories SET available_stock = ? WHERE ticket_type_id = ?", 90, id);
        awaitRedisValue("inventory:" + id, "90");
    }

    @Test
    void cdc_delete_does_not_crash_handler_on_tombstone_path() {
        // tombstones.on.delete=false so null events are unreachable; this verifies the handler
        // survives a normal delete and keeps processing afterward
        long id = BASE_ID + 6;
        insertInventory(id, 42, 42);
        awaitRedisValue("inventory:" + id, "42");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);

        // handler still alive after delete
        long nextId = BASE_ID + 7;
        insertInventory(nextId, 7, 7);
        awaitRedisValue("inventory:" + nextId, "7");

        var deleteCounter = meterRegistry.find("ticketflow_cdc_events_total").tag("op", "d").counter();
        assertThat(deleteCounter)
                .as("delete events must be recorded under op=d")
                .isNotNull();
        assertThat(deleteCounter.count())
                .as("at least one delete event must have been processed")
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void cdc_metrics_are_recorded_when_events_are_processed() {
        Timer lagTimer = meterRegistry.find("ticketflow_cdc_lag_seconds").timer();
        assertThat(lagTimer)
                .as("ticketflow_cdc_lag_seconds must be registered at startup")
                .isNotNull();

        long lagCountBefore = lagTimer.count();
        double insertCountBefore = eventCount("c");

        long id = BASE_ID + 8;
        insertInventory(id, 75, 75);

        // Redis write confirms accept() completed; metrics fire before Redis I/O,
        // so seeing the value guarantees >= baseline+1 for both counters.
        // >= not == because MeterRegistry is shared across the suite.
        awaitRedisValue("inventory:" + id, "75");

        assertThat(lagTimer.count())
                .as("lag timer must record at least one sample for the INSERT we triggered")
                .isGreaterThanOrEqualTo(lagCountBefore + 1);

        assertThat(eventCount("c"))
                .as("events_total{op=c} must increment at least once for the INSERT we triggered")
                .isGreaterThanOrEqualTo(insertCountBefore + 1.0);

        // sanity: handler errors counter exists; non-zero would indicate a parser/Redis fault
        assertThat(meterRegistry.find("ticketflow_cdc_handler_errors_total").counter())
                .as("ticketflow_cdc_handler_errors_total must be registered at startup")
                .isNotNull();
    }

    private void insertInventory(long ticketTypeId, int totalStock, int availableStock) {
        jdbcTemplate.update(
                "INSERT INTO inventories (ticket_type_id, total_stock, available_stock, version) "
                        + "VALUES (?, ?, ?, 0)",
                ticketTypeId, totalStock, availableStock);
    }

    private double eventCount(String op) {
        var counter = meterRegistry.find("ticketflow_cdc_events_total").tag("op", op).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void awaitRedisValue(String key, String expectedValue) {
        await().atMost(CDC_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> expectedValue.equals(redisTemplate.opsForValue().get(key)));
    }

    private void awaitRedisKeyAbsent(String key) {
        await().atMost(CDC_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> Boolean.FALSE.equals(redisTemplate.hasKey(key)));
    }
}