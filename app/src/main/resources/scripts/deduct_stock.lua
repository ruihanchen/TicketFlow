-- deduct_stock.lua
-- Atomically checks and deducts inventory from Redis.
--
-- Redis executes Lua scripts as a single atomic command.
-- No other client can read or write the key between the GET and DECRBY.
-- This eliminates the race condition that exists with separate GET + DECRBY calls.
--
-- KEYS[1] : inventory key, e.g. "inventory:ticketType:42"
-- ARGV[1] : quantity to deduct (must be a positive integer)
--
-- Return values (contract shared with RedisInventoryAdapter):
--   1  : success — stock was sufficient and has been deducted
--   0  : insufficient stock — current stock < requested quantity
--  -1  : cache miss — key does not exist in Redis
--        Caller must load from DB, populate the key, then retry.

local stock = redis.call('GET', KEYS[1])

-- Key does not exist: Redis was restarted or key was never populated.
-- Signal cache miss so the Java layer can reload from DB.
-- We do NOT attempt a DB call here: Lua scripts run inside the Redis
-- event loop and must not perform I/O or blocking operations.
if stock == false then
    return -1
end

local current  = tonumber(stock)
local quantity = tonumber(ARGV[1])

if current < quantity then
    return 0
end

redis.call('DECRBY', KEYS[1], quantity)
return 1