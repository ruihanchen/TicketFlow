package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import io.micrometer.core.instrument.Counter;
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

// Tests the CDC pipeline: Postgres WAL -> InventoryChangeHandler -> Redis.
// Direct Postgres + Redis access keeps failures attributable to the pipeline, not service code.
// ID namespace: BASE_ID=40000L through BASE_ID+99. AfterEach cleans only this range.
class InventoryCdcSyncTest extends IntegrationTestBase {

    private static final Duration CDC_TIMEOUT   = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final long     BASE_ID       = 40000L;

    @Autowired private JdbcTemplate        jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private MeterRegistry       meterRegistry;

    @BeforeEach
    void cleanRedisNamespace() {
        for (long id = BASE_ID; id < BASE_ID + 100; id++) {
            redisTemplate.delete("inventory:" + id);
        }
    }

    @AfterEach
    void cleanDatabaseNamespace() {
        jdbcTemplate.update(
                "DELETE FROM inventories WHERE ticket_type_id >= ? AND ticket_type_id <= ?",
                BASE_ID, BASE_ID + 99);
    }

    @Test
    void cdc_insert_writes_available_stock_to_redis() {
        long id = BASE_ID + 1;
        insertInventory(id, 200, 200);
        awaitRedisValue("inventory:" + id, "200");
    }

    @Test
    void cdc_insert_uses_available_stock_not_total_stock() {
        // Handler reads available_stock from payload.after, not total_stock.
        long id = BASE_ID + 2;
        insertInventory(id, 500, 320);
        awaitRedisValue("inventory:" + id, "320");
    }

    @Test
    void cdc_update_overwrites_redis_value_with_new_available_stock() {
        long id = BASE_ID + 3;
        insertInventory(id, 500, 500);
        awaitRedisValue("inventory:" + id, "500");

        jdbcTemplate.update("UPDATE inventories SET available_stock = ? WHERE ticket_type_id = ?", 320, id);
        awaitRedisValue("inventory:" + id, "320");
    }

    @Test
    void multiple_sequential_updates_converge_to_final_value() {
        long id = BASE_ID + 4;
        insertInventory(id, 1000, 1000);
        awaitRedisValue("inventory:" + id, "1000");

        for (int newStock : new int[]{900, 800, 700}) {
            jdbcTemplate.update("UPDATE inventories SET available_stock = ? WHERE ticket_type_id = ?", newStock, id);
            awaitRedisValue("inventory:" + id, String.valueOf(newStock));
        }
    }

    @Test
    void cdc_delete_removes_the_correct_redis_key() {
        long id = BASE_ID + 5;
        insertInventory(id, 100, 100);
        awaitRedisValue("inventory:" + id, "100");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);
    }

    @Test
    void cdc_delete_does_not_corrupt_inventory_zero() {
        // Regression guard: before V2 REPLICA IDENTITY FULL, DELETE events carried only the PK,
        // causing applyDelete() to read ticket_type_id=0 and delete "inventory:0".
        long id = BASE_ID + 6;
        insertInventory(id, 100, 100);
        awaitRedisValue("inventory:" + id, "100");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);

        assertThat(redisTemplate.hasKey("inventory:0")).isFalse();
    }

    @Test
    void cdc_handler_remains_alive_after_a_delete() {
        // An uncaught exception in accept() permanently stops the engine thread.
        // Verify the engine is still alive by processing a subsequent INSERT.
        long id = BASE_ID + 7;
        insertInventory(id, 42, 42);
        awaitRedisValue("inventory:" + id, "42");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);

        long nextId = BASE_ID + 8;
        insertInventory(nextId, 7, 7);
        awaitRedisValue("inventory:" + nextId, "7");
    }

    @Test
    void full_inventory_lifecycle_insert_update_delete_propagates_correctly() {
        long id = BASE_ID + 9;
        insertInventory(id, 1000, 1000);
        awaitRedisValue("inventory:" + id, "1000");

        jdbcTemplate.update("UPDATE inventories SET available_stock = 750 WHERE ticket_type_id = ?", id);
        awaitRedisValue("inventory:" + id, "750");

        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);
    }

    @Test
    void cdc_lag_timer_and_insert_counter_are_recorded_per_event() {
        Timer lagTimer = meterRegistry.find("ticketflow_cdc_lag_seconds").timer();
        assertThat(lagTimer).isNotNull();

        long lagBefore    = lagTimer.count();
        double insertBefore = counterValue("c");

        long id = BASE_ID + 10;
        insertInventory(id, 75, 75);
        awaitRedisValue("inventory:" + id, "75");

        assertThat(lagTimer.count()).isGreaterThanOrEqualTo(lagBefore + 1);
        assertThat(counterValue("c")).isGreaterThanOrEqualTo(insertBefore + 1.0);
    }

    @Test
    void delete_counter_increments_per_delete_event() {
        long id = BASE_ID + 11;
        insertInventory(id, 42, 42);
        awaitRedisValue("inventory:" + id, "42");

        double deleteBefore = counterValue("d");
        jdbcTemplate.update("DELETE FROM inventories WHERE ticket_type_id = ?", id);
        awaitRedisKeyAbsent("inventory:" + id);

        assertThat(counterValue("d")).isGreaterThanOrEqualTo(deleteBefore + 1.0);
    }

    @Test
    void cdc_error_counter_is_registered_and_zero_under_normal_operation() {
        Counter errorsCounter = meterRegistry.find("ticketflow_cdc_handler_errors_total").counter();
        assertThat(errorsCounter).isNotNull();
        assertThat(errorsCounter.count()).isEqualTo(0.0);
    }

    private void insertInventory(long ticketTypeId, int totalStock, int availableStock) {
        jdbcTemplate.update(
                "INSERT INTO inventories (ticket_type_id, total_stock, available_stock, version) VALUES (?, ?, ?, 0)",
                ticketTypeId, totalStock, availableStock);
    }

    private double counterValue(String op) {
        Counter c = meterRegistry.find("ticketflow_cdc_events_total").tag("op", op).counter();
        return c == null ? 0.0 : c.count();
    }

    private void awaitRedisValue(String key, String expectedValue) {
        await().alias("key '" + key + "' should equal '" + expectedValue + "'")
                .atMost(CDC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> expectedValue.equals(redisTemplate.opsForValue().get(key)));
    }

    private void awaitRedisKeyAbsent(String key) {
        await().alias("key '" + key + "' should be absent")
                .atMost(CDC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> Boolean.FALSE.equals(redisTemplate.hasKey(key)));
    }
}