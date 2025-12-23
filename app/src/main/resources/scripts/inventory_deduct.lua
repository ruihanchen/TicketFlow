-- Atomic check-and-decrement. Executes as a single Redis command.
-- Returns:  1 = SUCCESS, 0 = INSUFFICIENT, -1 = CACHE_MISS

local key = KEYS[1]
local qty = tonumber(ARGV[1])

local stock = redis.call('GET', key)
if stock == false then
    -- Redis returns Lua false (not nil) for missing keys
    return -1
end

stock = tonumber(stock)
if stock < qty then
    return 0
end

redis.call('DECRBY', key, qty)
return 1
