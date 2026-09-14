if redis.call('EXISTS', KEYS[1]) == 1 then
    if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
            or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
        return {'IDENTITY_MISMATCH'}
    end
    return {'EXISTING'}
end

redis.call('HSET', KEYS[1],
        'tenantId', ARGV[1],
        'userId', ARGV[2],
        'orgId', ARGV[3],
        'conversationId', ARGV[4],
        'requestId', ARGV[5],
        'state', 'RUNNING',
        'createdAt', ARGV[6],
        'expiresAt', ARGV[7],
        'lastSequence', '0',
        'eventCount', '0',
        'streamBytes', '0')
redis.call('HSET', KEYS[2], 'cancelRequested', 'false')
redis.call('PEXPIRE', KEYS[1], ARGV[8])
redis.call('PEXPIRE', KEYS[2], ARGV[8])
return {'CREATED'}
