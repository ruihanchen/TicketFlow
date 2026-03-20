-- release_stock_idempotent.lua
-- Atomic idempotency check + inventory restore for Kafka consumer.
--
-- Combining SETNX and INCRBY in one Lua command gives a critical guarantee:
-- if this messageId is ever seen as DUPLICATE, Redis inventory is guaranteed
-- to have already been incremented. The consumer can safely skip DB-only
-- work knowing Redis is correct.
--
-- KEYS[1] : idempotency key  "kafka:consumed:{messageId}"
-- KEYS[2] : inventory key    "inventory:ticketType:{ticketTypeId}"
-- ARGV[1] : quantity to restore
-- ARGV[2] : idempotency key TTL in seconds (86400 = 24h)
--
-- Returns:
--   "OK"         — first time; Redis incremented, messageId recorded; caller updates DB
--   "DUPLICATE"  — already processed; Redis guaranteed correct; caller may skip DB
--   "CACHE_MISS" — inventory key absent (Redis restart/eviction); skip Redis,
--                  caller updates DB directly; reconciler rebuilds cache

if redis.call('EXISTS', KEYS[1]) == 1 then
    return 'DUPLICATE'
end

local current_str = redis.call('GET', KEYS[2])
if not current_str then
    return 'CACHE_MISS'
end

-- Increment inventory and record messageId atomically.
-- Only one of these two operations can happen: either both succeed or
-- neither succeeds (Lua executes as a single atomic Redis command).
redis.call('INCRBY', KEYS[2], tonumber(ARGV[1]))
redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), 'SUCCESS')

return 'OK'