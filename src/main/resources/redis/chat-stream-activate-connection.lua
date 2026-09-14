-- 恢复连接必须先验证任务存在及归属；不能仅凭 requestId 接管其他用户的事件流。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {'NOT_FOUND'}
end

if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
    return {'IDENTITY_MISMATCH'}
end

local state = redis.call('HGET', KEYS[1], 'state')
-- 终态任务仍允许连接，用于补发客户端断线前尚未收到的 done/error 及其前序事件。
if state ~= 'RUNNING' and state ~= 'DONE' and state ~= 'ERROR'
        and state ~= 'TIMEOUT' and state ~= 'CANCELLED' then
    return {'NOT_FOUND'}
end

-- 后建立的连接覆盖旧 connectionId；旧中继发现自己已失效后会停止发送，避免双流并发。
redis.call('HSET', KEYS[1], 'activeConnectionId', ARGV[3])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return {'ACTIVATED'}
