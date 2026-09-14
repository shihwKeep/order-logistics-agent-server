-- 取消是独立控制信号，不删除事件流；已产生的事件仍可用于状态确认和问题排查。
local state = redis.call('HGET', KEYS[1], 'state')
if not state then
    return {'NOT_FOUND'}
end
if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
        or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
    return {'IDENTITY_MISMATCH'}
end
if state ~= 'RUNNING' then
    -- 已完成任务无需重复取消，避免覆盖原有终态。
    return {'TERMINAL'}
end
-- 生产线程周期性读取该标志并主动停止下游模型；设置 TTL 防止控制 Key 泄漏。
redis.call('HSET', KEYS[2], 'cancelRequested', 'true')
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return {'REQUESTED'}
