package com.chendev.ticketflow.infrastructure.cdc;

import com.chendev.ticketflow.inventory.redis.InventoryRedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

//applies Debezium CDC events from public, inventories to the Redis read cache,
//Postgres is source of truth; Redis is a derived copy. All ops (c/u/r/d) are idempotent.
@Slf4j
@Component
public class InventoryChangeHandler implements Consumer<ChangeEvent<String, String>> {

    private static final String EVENTS_METRIC = "ticketflow_cdc_events_total";
    private static final String EVENTS_DESCRIPTION =
            "CDC events processed by InventoryChangeHandler, partitioned by op type";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Timer cdcLag;
    private final Counter cdcErrors;

    //pre-registered to avoid per-event builder allocation. Debezium has 6 op codes (c/u/d/r/t/m);
    //"other" catches the unexpected ones and keeps lag.count() == sum(events_total{op=*}).
    private final Counter cdcEventsCreate;
    private final Counter cdcEventsUpdate;
    private final Counter cdcEventsDelete;
    private final Counter cdcEventsRead;
    private final Counter cdcEventsOther;

    public InventoryChangeHandler(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;

        //handler entry, before Redis I/O, transport lag only, not Redis performance
        this.cdcLag = Timer.builder("ticketflow_cdc_lag_seconds")
                .description("source-to-handler lag: now() - payload.source.ts_ms")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(meterRegistry);

        //exceptions are swallowed to protect the engine thread; this is the only signal they fired
        this.cdcErrors = Counter.builder("ticketflow_cdc_handler_errors_total")
                .description("accept() exceptions caught by the safety-net; non-zero = parser or Redis fault")
                .register(meterRegistry);

        this.cdcEventsCreate = eventCounter(meterRegistry, "c");
        this.cdcEventsUpdate = eventCounter(meterRegistry, "u");
        this.cdcEventsDelete = eventCounter(meterRegistry, "d");
        this.cdcEventsRead   = eventCounter(meterRegistry, "r");
        this.cdcEventsOther  = eventCounter(meterRegistry, "other");
    }

    private static Counter eventCounter(MeterRegistry registry, String op) {
        return Counter.builder(EVENTS_METRIC)
                .description(EVENTS_DESCRIPTION)
                .tag("op", op)
                .register(registry);
    }

    @Override
    public void accept(ChangeEvent<String, String> event) {
        //tombstones.on.delete=false makes this unreachable; guard against a future config change
        if (event.value() == null) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(event.value());
            JsonNode payload = root.path("payload");
            if (payload.isMissingNode() || payload.isNull()) {
                log.warn("[CDC] Event has no payload, ignoring: key={}", event.key());
                return;
            }

            String op = payload.path("op").asText("");
            recordLag(payload);
            recordEvent(op);  //must come before the switch so lag.count() == sum(events_total)

            switch (op) {
                case "c": // INSERT
                case "u": // UPDATE
                case "r": // READ (snapshot under snapshot.mode=when_needed)
                    applyUpsert(payload);
                    break;
                case "d": // DELETE
                    applyDelete(payload);
                    break;
                default:
                    log.debug("[CDC] Unhandled op '{}', skipping", op);
            }
        } catch (Exception e) {
            // never propagate, an uncaught exception stops the engine's worker thread permanently
            cdcErrors.increment();
            log.error("[CDC] Failed to handle change event: value={}",
                    event.value(), e);
        }
    }

    private void recordLag(JsonNode payload) {
        // lag = now(): source.ts_ms (Postgres commit time). NTP required in multi-host production.
        long sourceTsMs = payload.path("source").path("ts_ms").asLong(0L);
        if (sourceTsMs <= 0L) {
            return; // missing timestamp, skip rather than record garbag
        }
        long lagMs = System.currentTimeMillis() - sourceTsMs;
        if (lagMs < 0L) {
            return; // clock skew; Timer rejects negatives
        }
        cdcLag.record(lagMs, TimeUnit.MILLISECONDS);
    }

    private void recordEvent(String op) {
        switch (op) {
            case "c": cdcEventsCreate.increment(); return;
            case "u": cdcEventsUpdate.increment(); return;
            case "d": cdcEventsDelete.increment(); return;
            case "r": cdcEventsRead.increment();   return;
            default:
                cdcEventsOther.increment();
                log.warn("[CDC] Unexpected op '{}' recorded under op=other; likely TRUNCATE or future protocol type", op);
        }
    }

    private void applyUpsert(JsonNode payload) {
        JsonNode after = payload.path("after");
        if (after.isMissingNode() || after.isNull()) {
            log.warn("[CDC] Upsert event has no 'after' field, skipping");
            return;
        }
        long ticketTypeId = after.path("ticket_type_id").asLong();
        int availableStock = after.path("available_stock").asInt();
        String key = InventoryRedisKeys.stockKey(ticketTypeId);
        redisTemplate.opsForValue().set(key, String.valueOf(availableStock));
        log.debug("[CDC] Upsert: {} = {}", key, availableStock);
    }

    private void applyDelete(JsonNode payload) {
        // REPLICA IDENTITY FULL (V2 migration) required: without it, 'before' has only the PK,
        // not ticket_type_id, so the handler would delete inventory:0 instead of the real key
        JsonNode before = payload.path("before");
        if (before.isMissingNode() || before.isNull()) {
            log.warn("[CDC] Delete event missing 'before', is REPLICA IDENTITY FULL set on inventories?");
            return;
        }
        long ticketTypeId = before.path("ticket_type_id").asLong();
        if (ticketTypeId == 0L) {
            log.warn("[CDC] Delete event has ticket_type_id=0; REPLICA IDENTITY likely not FULL. Skipping.");
            return;
        }
        String key = InventoryRedisKeys.stockKey(ticketTypeId);
        redisTemplate.delete(key);
        log.debug("[CDC] Delete: {}", key);
    }
}