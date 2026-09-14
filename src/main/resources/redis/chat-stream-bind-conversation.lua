if redis.call('EXISTS', KEYS[1]) == 0 then
    return {'NOT_FOUND'}
end

if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
    return {'IDENTITY_MISMATCH'}
end

local current = redis.call('HGET', KEYS[1], 'conversationId') or ''
if current == ARGV[3] then
    return {'ALREADY_BOUND'}
end
if current ~= '' then
    return {'CONVERSATION_CONFLICT'}
end

redis.call('HSET', KEYS[1], 'conversationId', ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return {'BOUND'}
