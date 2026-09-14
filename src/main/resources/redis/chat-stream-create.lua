-- KEYS[1] 为任务元数据，KEYS[2] 为控制状态；两者共享 Hash Tag，保证脚本可在集群执行。
-- requestId 由客户端预生成并在服务端绑定身份，用它实现首次 POST 的幂等创建。
if redis.call('EXISTS', KEYS[1]) == 1 then
    -- 已存在的请求只有归属同一租户和用户时才能复用，避免跨用户探测或串流。
    if redis.call('HGET', KEYS[1], 'tenantId') ~= ARGV[1]
            or redis.call('HGET', KEYS[1], 'userId') ~= ARGV[2] then
        return {'IDENTITY_MISMATCH'}
    end
    return {'EXISTING'}
end

-- 初始状态只记录回放协议所需的最小元数据；conversationId 可在业务开始事务后补绑。
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
-- 元数据和控制 Key 使用相同 TTL，过期后不再承诺恢复，也不会永久占用 Redis。
redis.call('PEXPIRE', KEYS[1], ARGV[8])
redis.call('PEXPIRE', KEYS[2], ARGV[8])
return {'CREATED'}
