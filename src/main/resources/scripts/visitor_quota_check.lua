-- Atomic Visitor Fair-Share Quota Reservation & Concurrency Guard
-- KEYS[1]: Visitor Daily Quota Key (e.g., vq:{tenantId}:{visitorId}:{YYYY-MM-DD})
-- KEYS[2]: Visitor In-Flight Lock (e.g., inflight:{tenantId}:{visitorId})
-- ARGV[1]: Max Allowed Messages per Day (e.g., 15)
-- ARGV[2]: Max Allowed Tokens per Day (e.g., 3000)
-- ARGV[3]: Estimated Tokens for incoming query (e.g., 150)
-- ARGV[4]: Key TTL in Seconds (until midnight reset)

-- 1. Check for Active In-Flight Query (Prevent concurrent spam/race conditions)
local inFlight = redis.call('GET', KEYS[2])
if inFlight then
    return {-1, "CONCURRENT_REQUEST_BLOCKED", 0, 0}
end

-- 2. Fetch Current Daily Usage
local currentMsgs = tonumber(redis.call('HGET', KEYS[1], 'msgs') or "0")
local currentTokens = tonumber(redis.call('HGET', KEYS[1], 'tokens') or "0")

local maxMsgs = tonumber(ARGV[1])
local maxTokens = tonumber(ARGV[2])
local estimatedTokens = tonumber(ARGV[3])
local ttlSeconds = tonumber(ARGV[4])

-- 3. Check Message Limit
if currentMsgs >= maxMsgs then
    return {0, "MESSAGE_LIMIT_EXCEEDED", currentMsgs, currentTokens}
end

-- 4. Check Token Budget
if (currentTokens + estimatedTokens) > maxTokens then
    return {0, "TOKEN_LIMIT_EXCEEDED", currentMsgs, currentTokens}
end

-- 5. Atomic Reservation: Increment count & token budget
local newMsgs = redis.call('HINCRBY', KEYS[1], 'msgs', 1)
local newTokens = redis.call('HINCRBY', KEYS[1], 'tokens', estimatedTokens)

-- Set TTL on first entry of the day
if currentMsgs == 0 then
    redis.call('EXPIRE', KEYS[1], ttlSeconds)
end

-- Set In-Flight Concurrency Lock with 30s Safety Timeout
redis.call('SET', KEYS[2], "1", "EX", 30)

return {1, "RESERVED", newMsgs, newTokens}
