-- release_stock_idempotent.lua
-- Atomically: check consumer idempotency + release inventory in Redis.
--
-- Executed by OrderCancelledConsumer to restore inventory when an order
-- is cancelled. Combines the idempotency check and stock increment into a
-- single atomic Redis command, preventing double-restore under concurrent
-- Kafka redelivery (e.g. during consumer rebalance).
--
-- Key format mirrors deduct_stock.lua / release_stock.lua (String, not Hash).
-- inventory key value is a plain integer string, e.g. "99".
--
-- KEYS[1] : idempotency key  e.g. "kafka:consumed:{messageId}"
-- KEYS[2] : inventory key    e.g. "inventory:ticketType:{ticketTypeId}"
-- ARGV[1] : quantity to return (positive integer string)
-- ARGV[2] : idempotency key TTL in seconds (e.g. "86400" for 24 hours)
--
-- Return values (contract shared with RedisInventoryManager):
--   "OK"         : success — Redis inventory incremented, messageId recorded
--   "DUPLICATE"  : messageId already processed; Redis not changed
--   "CACHE_MISS" : inventory key absent in Redis (restart/eviction); skip Redis,
--                  caller must update DB directly and reconciler will rebuild cache

-- 1. Idempotency check: has this messageId been processed before?
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'DUPLICATE'
end

-- 2. Cache miss guard: inventory key must exist before we increment.
--    Do NOT create the key here with just the returned quantity.
--    That would produce a stale value (e.g. "1") when the real
--    available_stock in DB might be 47.
--    The reconciliation job owns DB → Redis sync.
local current_str = redis.call('GET', KEYS[2])
if not current_str then
    return 'CACHE_MISS'
end

-- 3. Increment inventory.
--    No upper-bound check against total_stock here — Redis does not store total.
--    Upper-bound protection is enforced by the DB guard write in InventoryRepository
--    (available_stock + qty <= total_stock) and the CHECK constraint on the column.
redis.call('INCRBY', KEYS[2], tonumber(ARGV[1]))

-- 4. Record messageId as processed with TTL.
--    SETEX is atomic with the INCRBY above because Lua executes as a single command.
--    If the JVM crashes after INCRBY but before SETEX, the next retry will not find
--    the idempotency key and will INCRBY again — double-restore in Redis.
--    The reconciliation job detects Redis > DB drift and corrects it.
redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), 'SUCCESS')

return 'OK'
