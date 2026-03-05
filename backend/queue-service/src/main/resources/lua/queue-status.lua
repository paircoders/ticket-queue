--[[
  대기열 상태 조회 Lua 스크립트 (REQ-QUEUE-002)

  단일 round-trip으로 토큰 발급 여부와 대기 순위를 원자적으로 확인한다.
  Redis KEYS 명령 금지 원칙에 따라 역방향 토큰 키를 직접 조회한다.

  KEYS[1] = queue:{scheduleId}                     -- 대기열 Sorted Set
  KEYS[2] = queue:user-token:{userId}:{scheduleId} -- 역방향 토큰 조회 키 (배치 승인 시 작성)

  ARGV[1] = userId

  Returns:
    {0, rank} : WAITING — 대기열 대기 중 (rank = ZRANK, 0-based)
    {1, token} : ACTIVE  — 배치 승인 완료, 토큰 발급됨
    {2, -1}   : NOT_IN_QUEUE — 대기열에 없음
]]

local queueKey     = KEYS[1]
local userTokenKey = KEYS[2]
local userId       = ARGV[1]

-- Step 1: 토큰 발급 여부 확인 (배치 승인 완료된 경우)
local token = redis.call('GET', userTokenKey)
if token then
    return {1, token}
end

-- Step 2: 대기열 순위 확인
local rank = redis.call('ZRANK', queueKey, userId)
if rank == false then
    return {2, -1}
end

return {0, rank}
