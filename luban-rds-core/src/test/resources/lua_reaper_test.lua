-- Lua Test Suite for Reaper Script
-- Usage: redis-cli --eval lua_reaper_test.lua , 

local function log(msg)
    -- redis.log(redis.LOG_NOTICE, msg)
end

local function fail(msg)
    error("TEST FAILED: " .. msg)
end

local function assert_eq(expected, actual, msg)
    -- Handle Lua 5.1 nil vs false discrepancy if needed, but for strict eq:
    if expected ~= actual then
        -- special handling for false/nil if needed, but strict is safer
        fail(string.format("%s: Expected %s, got %s", msg, tostring(expected), tostring(actual)))
    end
end

local function assert_true(condition, msg)
    if not condition then
        fail(string.format("%s: Expected true", msg))
    end
end

-- The Target Script Logic wrapped in a function
-- Note: 'table.getn' is deprecated in Lua 5.1+ but used in user script. 
-- Standard Redis uses Lua 5.1. Luban-RDS uses LuaJ (5.2 based) which might lack table.getn.
-- We might need a polyfill if running on Luban-RDS.
if not table.getn then
    table.getn = function(t) return #t end
end

local function run_reaper(KEYS, ARGV)
    -- Start of user script
    if redis.call('setnx', KEYS[6], ARGV[4]) == 0 then return -1;end;
    redis.call('expire', KEYS[6], ARGV[3]); 
    local expiredKeys1 = redis.call('zrangebyscore', KEYS[2], 0, ARGV[1], 'limit', 0, ARGV[2]); 
    for i, key in ipairs(expiredKeys1) do 
        local v = redis.call('hget', KEYS[1], key); 
        if v ~= false then 
            local t, val = struct.unpack('dLc0', v); 
            local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(val), val); 
            local listeners = redis.call(ARGV[5], KEYS[4], msg); 
            if (listeners == 0) then break;end; 
        end;
    end;
    for i=1, #expiredKeys1, 5000 do 
        redis.call('zrem', KEYS[5], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); 
        redis.call('zrem', KEYS[3], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); 
        redis.call('zrem', KEYS[2], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); 
        redis.call('hdel', KEYS[1], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); 
    end; 
    local expiredKeys2 = redis.call('zrangebyscore', KEYS[3], 0, ARGV[1], 'limit', 0, ARGV[2]); 
    for i, key in ipairs(expiredKeys2) do 
        local v = redis.call('hget', KEYS[1], key); 
        if v ~= false then 
            local t, val = struct.unpack('dLc0', v); 
            local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(val), val); 
            local listeners = redis.call(ARGV[5], KEYS[4], msg); 
            if (listeners == 0) then break;end; 
        end;
    end;
    for i=1, #expiredKeys2, 5000 do 
        redis.call('zrem', KEYS[5], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); 
        redis.call('zrem', KEYS[3], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); 
        redis.call('zrem', KEYS[2], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); 
        redis.call('hdel', KEYS[1], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); 
    end; 
    return #expiredKeys1 + #expiredKeys2; 
    -- End of user script
end

-- Test Setup
local function setup()
    redis.call('FLUSHDB')
end

-- Test Case 1: Basic Expiration Flow
local function test_basic_expiration()
    setup()
    local KEYS = {'hash', 'zset_exp', 'zset_act', 'channel', 'zset_aux', 'lock'}
    local ARGV = {1000, 10, 60, 'lock_val', 'PUBLISH'} 
    
    -- Setup Data
    local key1 = 'k1'
    local val1 = 'v1'
    local packed_val1 = struct.pack('dLc0', 123.456, string.len(val1), val1)
    
    redis.call('HSET', KEYS[1], key1, packed_val1)
    redis.call('ZADD', KEYS[2], 500, key1) -- expired (score < 1000)
    
    -- Note: PUBLISH returns 0 if no subscribers.
    -- The script logic: if listeners == 0 then break.
    -- This means if no subscribers, loop breaks, but keys are still collected in expiredKeys1 array.
    -- Then deletion happens for ALL keys in expiredKeys1.
    -- So even with 0 listeners, keys should be deleted.
    
    local res = run_reaper(KEYS, ARGV)
    
    -- Assertions
    assert_eq(1, res, "Should return 1 expired key")
    
    -- Verify deletion
    local exists = redis.call('HEXISTS', KEYS[1], key1)
    assert_eq(0, exists, "Key should be deleted from Hash")
    
    -- Verify ZSets deletion
    exists = redis.call('ZSCORE', KEYS[2], key1)
    assert_eq(false, exists, "Key should be deleted from ZSet Exp")
    
    -- Verify Lock
    local lock_ttl = redis.call('TTL', KEYS[6])
    assert_true(lock_ttl > 0, "Lock should have TTL")
    
    return "Basic Expiration Test Passed"
end

-- Test Case 2: Lock Contention
local function test_lock_contention()
    setup()
    local KEYS = {'hash', 'zset_exp', 'zset_act', 'channel', 'zset_aux', 'lock'}
    local ARGV = {1000, 10, 60, 'lock_val', 'PUBLISH'} 
    
    -- Set lock manually
    redis.call('SET', KEYS[6], 'existing_lock')
    
    local res = run_reaper(KEYS, ARGV)
    assert_eq(-1, res, "Should return -1 when locked")
    
    -- Verify data not changed (if we had data)
    
    return "Lock Contention Test Passed"
end

-- Test Case 3: Multiple Keys and Batch Limit
local function test_batch_limit()
    setup()
    local KEYS = {'hash', 'zset_exp', 'zset_act', 'channel', 'zset_aux', 'lock'}
    local ARGV = {1000, 6000, 60, 'lock_val', 'PUBLISH'} -- limit 6000
    
    -- Insert 5500 keys
    for i=1, 5500 do
        local k = 'k'..i
        local v = 'v'..i
        local pv = struct.pack('dLc0', i, string.len(v), v)
        redis.call('HSET', KEYS[1], k, pv)
        redis.call('ZADD', KEYS[2], 500, k)
    end
    
    local res = run_reaper(KEYS, ARGV)
    assert_eq(5500, res, "Should process all 5500 keys")
    
    -- Verify all deleted
    local count = redis.call('ZCARD', KEYS[2])
    assert_eq(0, count, "All keys should be removed from ZSet")
    
    return "Batch Limit Test Passed"
end

-- Test Case 4: Pub/Sub Message Format (Requires Subscribers, hard to test in pure script without external client)
-- But we can verify logic via side effects if possible, or trust basic flow test.
-- The script calls PUBLISH.
-- We can't easily capture PUBLISH output from within the script itself unless we subscribe.
-- But SUBSCRIBE blocks.
-- So we rely on manual verification or integration test for message content.

-- Main Test Runner
local report = {}
local tests = {test_basic_expiration, test_lock_contention, test_batch_limit}

for i, t in ipairs(tests) do
    local status, msg = pcall(t)
    if status then
        table.insert(report, msg)
    else
        table.insert(report, "FAIL: " .. msg)
    end
end

return report
