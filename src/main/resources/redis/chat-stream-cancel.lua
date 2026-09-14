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
redis.call('HSET', KEYS[2], 'cancelRequested', 'true')
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return {'REQUESTED'}
