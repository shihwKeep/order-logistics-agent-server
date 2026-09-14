if redis.call('EXISTS', KEYS[1]) == 0 then
    return {'NOT_FOUND'}
end

if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
    return {'IDENTITY_MISMATCH'}
end

local state = redis.call('HGET', KEYS[1], 'state')
if state ~= 'RUNNING' and state ~= 'DONE' and state ~= 'ERROR'
        and state ~= 'TIMEOUT' and state ~= 'CANCELLED' then
    return {'NOT_FOUND'}
end

redis.call('HSET', KEYS[1], 'activeConnectionId', ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return {'ACTIVATED'}
