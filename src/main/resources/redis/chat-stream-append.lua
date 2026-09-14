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

if eventBytes <= 0 or eventBytes > maxEventBytes then
    return {'LIMIT'}
end
if terminal then
    if eventCount >= maxEvents or streamBytes + eventBytes > maxStreamBytes then
        return {'LIMIT'}
    end
else
    if eventCount >= maxEvents - 1
            or streamBytes + eventBytes > maxStreamBytes - maxEventBytes then
        return {'LIMIT'}
    end
end

local nextSequence = tonumber(redis.call('HGET', KEYS[1], 'lastSequence') or '0') + 1
redis.call('XADD', KEYS[2], tostring(nextSequence) .. '-0',
        'type', ARGV[3], 'timestamp', ARGV[4], 'payload', ARGV[5])
redis.call('HSET', KEYS[1],
        'lastSequence', tostring(nextSequence),
        'eventCount', tostring(eventCount + 1),
        'streamBytes', tostring(streamBytes + eventBytes))
if terminal then
    redis.call('HSET', KEYS[1], 'state', terminalState)
    if ARGV[12] ~= '' then
        redis.call('HSET', KEYS[1], 'terminalCode', ARGV[12])
    end
    if ARGV[13] ~= '' then
        redis.call('HSET', KEYS[1], 'terminalMessageId', ARGV[13])
    end
end
redis.call('PEXPIRE', KEYS[1], ttlMillis)
redis.call('PEXPIRE', KEYS[2], ttlMillis)
redis.call('PEXPIRE', KEYS[3], ttlMillis)
return {'OK', tostring(nextSequence)}
