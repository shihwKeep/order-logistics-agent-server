-- conversationId 只能在身份校验后绑定，并且一旦绑定就不能改绑到另一会话。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {'NOT_FOUND'}
end

if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
    return {'IDENTITY_MISMATCH'}
end

local current = redis.call('HGET', KEYS[1], 'conversationId') or ''
-- 同值重试保持幂等；不同值表示请求与业务会话发生冲突，必须拒绝。
if current == ARGV[3] then
    return {'ALREADY_BOUND'}
end
if current ~= '' then
    return {'CONVERSATION_CONFLICT'}
end

-- 业务开始事务成功后才补写可信 conversationId，避免使用未经数据库校验的前端值。
redis.call('HSET', KEYS[1], 'conversationId', ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return {'BOUND'}
