package com.chendev.ticketflow.infrastructure.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

// Applies Debezium CDC events from public.inventories to the Redis read cache.
// Postgres is the source of truth; Redis is a derived copy keyed by ticket_type_id.
// All four operations (c/u/r/d) are idempotent, Redis writes are absolute SET/DELETE, not deltas.
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryChangeHandler implements Consumer<ChangeEvent<String, String>> {

    private static final String KEY_PREFIX = "inventory:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void accept(ChangeEvent<String, String> event) {
        //tombstones.on.delete=false makes this unreachable, but guards against a future config change
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

            switch (op) {
                case "c": // INSERT
                case "u": // UPDATE
                case "r": // READ (snapshot phase under snapshot.mode=initial)
                    applyUpsert(payload);
                    break;
                case "d": // DELETE
                    applyDelete(payload);
                    break;
                default:
                    log.debug("[CDC] Unhandled operation '{}', skipping", op);
            }
        } catch (Exception e) {
            //never propagate, an uncaught exception stops the engine's worker thread permanently
            log.error("[CDC] Failed to handle change event: value={}",
                    event.value(), e);
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

        String key = KEY_PREFIX + ticketTypeId;
        redisTemplate.opsForValue().set(key, String.valueOf(availableStock));

        log.debug("[CDC] Upsert: {} = {}", key, availableStock);
    }

    private void applyDelete(JsonNode payload) {
        // REPLICA IDENTITY FULL (V2 migration) required: default identity only has the primary key,
        // not ticket_type_id, so 'before' would be useless without it
        JsonNode before = payload.path("before");
        if (before.isMissingNode() || before.isNull()) {
            log.warn("[CDC] Delete event has no 'before' field. "
                    + "Is REPLICA IDENTITY FULL enabled on inventories?");
            return;
        }

        long ticketTypeId = before.path("ticket_type_id").asLong();
        if (ticketTypeId == 0L) {
            log.warn("[CDC] Delete event has ticket_type_id=0. "
                    + "Likely REPLICA IDENTITY is not FULL. Skipping to avoid "
                    + "corrupting inventory:0 key.");
            return;
        }

        String key = KEY_PREFIX + ticketTypeId;
        redisTemplate.delete(key);

        log.debug("[CDC] Delete: {}", key);
    }
}