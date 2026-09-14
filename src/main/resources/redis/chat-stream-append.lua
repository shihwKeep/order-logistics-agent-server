-- 先校验任务存在、身份一致且仍处于 RUNNING；终态之后到达的迟到事件一律拒绝。
local state = redis.call('HGET', KEYS[1], 'state')
if not state then
    return {'NOT_FOUND'}
end
if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
    return {'IDENTITY_MISMATCH'}
end
if state ~= 'RUNNING' then
    return {'TERMINAL'}
end

local eventBytes = tonumber(ARGV[6])
local ttlMillis = tonumber(ARGV[7])
local maxEvents = tonumber(ARGV[8])
local maxEventBytes = tonumber(ARGV[9])
local maxStreamBytes = tonumber(ARGV[10])
local terminalState = ARGV[11]
local terminal = terminalState ~= ''
local eventCount = tonumber(redis.call('HGET', KEYS[1], 'eventCount') or '0')
local streamBytes = tonumber(redis.call('HGET', KEYS[1], 'streamBytes') or '0')

-- 单事件和整条流都设置硬上限，防止异常模型输出无限占用 Redis 内存。
if eventBytes <= 0 or eventBytes > maxEventBytes then
    return {'LIMIT'}
end
if terminal then
    if eventCount >= maxEvents or streamBytes + eventBytes > maxStreamBytes then
        return {'LIMIT'}
    end
else
    -- 普通事件必须预留一个事件槽和 maxEventBytes 空间，保证容量临界时仍能写入
    -- done/error，让客户端获得明确终态，而不是只能看到裸断连。
    if eventCount >= maxEvents - 1
            or streamBytes + eventBytes > maxStreamBytes - maxEventBytes then
        return {'LIMIT'}
    end
end

-- lastSequence 的读取、递增和 XADD 位于同一个 Lua 脚本中，并发发布也不会生成重复序号。
-- Stream ID 直接使用 sequence-0，恢复端即可用 afterSequence 精确续读。
local nextSequence = tonumber(redis.call('HGET', KEYS[1], 'lastSequence') or '0') + 1
redis.call('XADD', KEYS[2], tostring(nextSequence) .. '-0',
        'type', ARGV[3], 'timestamp', ARGV[4], 'payload', ARGV[5])
redis.call('HSET', KEYS[1],
        'lastSequence', tostring(nextSequence),
        'eventCount', tostring(eventCount + 1),
        'streamBytes', tostring(streamBytes + eventBytes))
if terminal then
    -- 终态事件和任务状态原子提交；写入终态后，后续 append 会在上方返回 TERMINAL。
    redis.call('HSET', KEYS[1], 'state', terminalState)
    if ARGV[12] ~= '' then
        redis.call('HSET', KEYS[1], 'terminalCode', ARGV[12])
    end
    if ARGV[13] ~= '' then
        redis.call('HSET', KEYS[1], 'terminalMessageId', ARGV[13])
    end
end
-- 每次成功追加都刷新整组 Key 的 TTL，使正在运行或刚结束的请求保留完整回放窗口。
redis.call('PEXPIRE', KEYS[1], ttlMillis)
redis.call('PEXPIRE', KEYS[2], ttlMillis)
redis.call('PEXPIRE', KEYS[3], ttlMillis)
return {'OK', tostring(nextSequence)}
