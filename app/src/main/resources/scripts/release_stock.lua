-- release_stock.lua
-- Atomically returns inventory to Redis when an order is cancelled.
--
-- KEYS[1] : inventory key, e.g. "inventory:ticketType:42"
-- ARGV[1] : quantity to return (must be a positive integer)
--
-- Return values:
--   1  : success — stock has been returned
--   0  : key not found — Redis was restarted and key is gone.
--        The caller should NOT fabricate a new key with just this quantity.
--        The reconciliation job will rebuild Redis from DB as the source of truth.

local stock = redis.call('GET', KEYS[1])

-- Key does not exist after a Redis restart.
-- Do NOT create the key here with just the returned quantity —
-- that would produce a stale, incorrect value (e.g., returning 1 unit
-- when the true available_stock in DB might be 47).
-- The reconciliation job (InventoryReconciliationJob) owns DB → Redis sync.
if stock == false then
    return 0
end

redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
return 1