package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

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
        // broad range catches any row inserted during the test; next test starts clean
        jdbcTemplate.update(
                "DELETE FROM inventories WHERE ticket_type_id >= 33333 AND ticket_type_id <= 99999");
    }

    @Test
    void cdc_insert_event_creates_redis_key_with_correct_value() {
        long ticketTypeId = 88888L;
        insertInventory(ticketTypeId, 200, 200);

        awaitRedisValue("inventory:" + ticketTypeId, "200");
    }

    @Test
    void cdc_update_event_overwrites_redis_value() {
        long ticketTypeId = 77777L;
        insertInventory(ticketTypeId, 500, 500);
        awaitRedisValue("inventory:" + ticketTypeId, "500");

        jdbcTemplate.update(
                "UPDATE inventories SET available_stock = ? WHERE ticket_type_id = ?",
                320, ticketTypeId);

        awaitRedisValue("inventory:" + ticketTypeId, "320");
    }

    @Test
    void cdc_delete_event_removes_correct_redis_key_not_id_zero() {
        long ticketTypeId = 66666L;
        insertInventory(ticketTypeId, 100, 100);
        awaitRedisValue("inventory:" + ticketTypeId, "100");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", ticketTypeId);

        // The real key must be removed.
        awaitRedisKeyAbsent("inventory:" + ticketTypeId);

        // inventory:0 appearing means REPLICA IDENTITY FULL is not in effect, the handler read the schema
        // the handler read the schema default instead of the real ticket_type_id
        assertThat(redisTemplate.hasKey("inventory:0"))
                .as("inventory:0 must not be touched by the DELETE handler. "
                        + "If this fails, REPLICA IDENTITY FULL is not in effect "
                        + "on public.inventories.")
                .isFalse();
    }

    @Test
    void cdc_delete_does_not_crash_handler_on_tombstone_path() {
        long ticketTypeId = 55555L;
        insertInventory(ticketTypeId, 150, 150);
        awaitRedisValue("inventory:" + ticketTypeId, "150");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", ticketTypeId);
        awaitRedisKeyAbsent("inventory:" + ticketTypeId);

        // if the handler crashed on a tombstone, the engine thread is dead and this second event will time out
        long nextTicketTypeId = 55554L;
        insertInventory(nextTicketTypeId, 42, 42);
        try {
            awaitRedisValue("inventory:" + nextTicketTypeId, "42");
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM inventories WHERE ticket_type_id = ?", nextTicketTypeId);
        }
    }

    @Test
    void replica_identity_full_is_in_effect_for_inventories() {
        // direct catalog query, not CDC-dependent. guards against accidental V2 migration revert.
        // relreplident: 'f'=FULL, 'd'=DEFAULT, 'n'=NOTHING, 'i'=INDEX
        String identity = jdbcTemplate.queryForObject(
                "SELECT relreplident::text FROM pg_class WHERE relname = 'inventories'",
                String.class);

        assertThat(identity)
                .as("inventories table must have REPLICA IDENTITY FULL. "
                        + "If this fails, V2 migration did not run or was reverted.")
                .isEqualTo("f");
    }

    @Test
    void cdc_events_processed_in_order_for_same_row() {
        // rapid-fire updates: Redis must land on the final value, not any intermediate
        long ticketTypeId = 44444L;
        insertInventory(ticketTypeId, 1000, 1000);
        awaitRedisValue("inventory:" + ticketTypeId, "1000");

        jdbcTemplate.update(
                "UPDATE inventories SET available_stock = 900 WHERE ticket_type_id = ?", ticketTypeId);
        jdbcTemplate.update(
                "UPDATE inventories SET available_stock = 800 WHERE ticket_type_id = ?", ticketTypeId);
        jdbcTemplate.update(
                "UPDATE inventories SET available_stock = 700 WHERE ticket_type_id = ?", ticketTypeId);

        awaitRedisValue("inventory:" + ticketTypeId, "700");
    }

    //helpers
    private void insertInventory(long ticketTypeId, int totalStock, int availableStock) {
        jdbcTemplate.update(
                "INSERT INTO inventories (ticket_type_id, total_stock, available_stock, version) "
                        + "VALUES (?, ?, ?, 0)",
                ticketTypeId, totalStock, availableStock);
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
