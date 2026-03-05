-- 定义通用错误码（与Java端保持一致）
local RETURN_CODE_EXPIRED = "-1"
local RETURN_CODE_STOPPED = "-2"
local RETURN_CODE_INVALID = "-3"

-- 通用错误回复函数（模拟Java端makeError逻辑）
local function makeError(errMsg)
    return redis.error_reply(errMsg)
end

-- 1. 会话触发生命周期续期（对应TOUCH_SCRIPT）
function touchSession(key1, key2, lastAccessTime)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    local timeoutEncoded = redis.call('HGET', key1, '"timeout"')
    if timeoutEncoded == nil then
        return makeError(RETURN_CODE_INVALID)
    end

    local timeout = cjson.decode(timeoutEncoded)[2]

    redis.call('HSET', key1, '"lastAccessTime"', lastAccessTime)
    redis.call('PEXPIRE', key1, timeout)
    redis.call('PEXPIRE', key2, timeout)
end

-- 2. 会话初始化（对应INIT_SCRIPT）
function initSession(key1, sessionId, timeoutJson, startTimestamp, host)
    redis.call('HMSET', key1, 
        '"id"', sessionId, 
        '"timeout"', timeoutJson,
        '"startTimestamp"', startTimestamp, 
        '"lastAccessTime"', startTimestamp,
        '"host"', host)
    local timeout = cjson.decode(timeoutJson)[2]
    redis.call('PEXPIRE', key1, timeout)
end

-- 3. 获取会话启动时间（对应GET_START_SCRIPT）
function getSessionStartTime(key1)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    local startTime = redis.call('HGET', key1, '"startTimestamp"')
    if startTime == nil then
        return makeError(RETURN_CODE_INVALID)
    end

    return startTime
end

-- 4. 获取会话最后访问时间（对应GET_LAST_SCRIPT）
function getSessionLastAccessTime(key1)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    local lastTime = redis.call('HGET', key1, '"lastAccessTime"')
    if lastTime == nil then
        return makeError(RETURN_CODE_INVALID)
    end

    return lastTime
end

-- 5. 获取会话超时时间（对应GET_TIMEOUT_SCRIPT）
function getSessionTimeout(key1)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    local timeout = redis.call('HGET', key1, '"timeout"')
    if timeout == nil then
        return makeError(RETURN_CODE_INVALID)
    end

    return timeout
end

-- 6. 获取会话所属主机（对应GET_HOST_SCRIPT）
function getSessionHost(key1)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    local host = redis.call('HGET', key1, '"host"')
    if host == nil then
        return makeError(RETURN_CODE_INVALID)
    end

    return host
end

-- 7. 修改会话超时时间（对应SET_TIMEOUT_SCRIPT）
function setSessionTimeout(key1, key2, newTimeoutJson)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    local timeout = redis.call('HGET', key1, '"timeout"')
    if timeout == nil then
        return makeError(RETURN_CODE_INVALID)
    end

    redis.call('HSET', key1, '"timeout"', newTimeoutJson)
    local newTimeout = cjson.decode(newTimeoutJson)[2]
    redis.call('PEXPIRE', key1, newTimeout)
    redis.call('PEXPIRE', key2, newTimeout)
end

-- 8. 停止会话（对应STOP_SCRIPT）
function stopSession(key1, stopFlag)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    redis.call('HSET', key1, '"stop"', stopFlag)
end

-- 9. 获取会话属性键列表（对应GET_ATTRKEYS_SCRIPT）
function getSessionAttrKeys(key1, key2)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    return redis.call('HKEYS', key2)
end

-- 10. 获取指定会话属性（对应GET_ATTR_SCRIPT）
function getSessionAttr(key1, key2, attrKey)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    return redis.call('HGET', key2, attrKey)
end

-- 11. 移除指定会话属性（对应REMOVE_ATTR_SCRIPT）
function removeSessionAttr(key1, key2, attrKey)
    if redis.call('PTTL', key1) <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    local attr = redis.call('HGET', key2, attrKey)
    if attr ~= nil then
        redis.call('HDEL', key2, attrKey)
    end

    return attr
end

-- 12. 设置会话属性（对应SET_ATTR_SCRIPT）
function setSessionAttr(key1, key2, attrKey, attrValue)
    local pttl = redis.call('PTTL', key1)
    if pttl <= 0 then
        return makeError(RETURN_CODE_EXPIRED)
    end

    if redis.call('HEXISTS', key1, '"stop"') == 1 then
        return makeError(RETURN_CODE_STOPPED)
    end

    redis.call('HSET', key2, attrKey, attrValue)
    -- redis auto delete key of hash when it is empty.
    -- then, expire time of the hash will be lost.
    if redis.call('PTTL', key2) <= 0 then
        redis.call('PEXPIRE', key2, pttl)
    end
end

-- 13. 删除会话（对应DELETE_SCRIPT）
function deleteSession(key1, key2)
    redis.call('UNLINK', key1, key2)
end

-- 14. 读取会话剩余存活时间（对应READ_SCRIPT）
function readSessionTTL(key1)
    return redis.call('PTTL', key1)
end