--[[
  대기열 이탈 Lua 스크립트 (REQ-QUEUE-003)

  WAITING 상태(Sorted Set 대기)와 ACTIVE 상태(토큰 발급)를 모두 원자적으로 처리한다.

  KEYS[1] = queue:{scheduleId}                     -- 대기열 Sorted Set
  KEYS[2] = queue:active:{userId}                  -- 중복 대기 방지 키
  KEYS[3] = queue:user-token:{userId}:{scheduleId} -- 역방향 토큰 조회 키

  ARGV[1] = userId
  ARGV[2] = scheduleId

  Returns:
    {0} : NOT_IN_QUEUE — 대기열에 없거나 다른 회차에 대기 중
    {1} : 이탈 성공
]]

local queueKey     = KEYS[1]
local activeKey    = KEYS[2]
local userTokenKey = KEYS[3]

local userId     = ARGV[1]
local scheduleId = ARGV[2]

-- Step 1: active 키 확인 (Single Source of Truth)
local existingScheduleId = redis.call('GET', activeKey)
if not existingScheduleId then
    -- active 키 없음 → 대기열에 없음
    return {0}
end
if existingScheduleId ~= scheduleId then
    -- 다른 회차에 대기 중 → 이 회차에서는 이탈 불가
    return {0}
end

-- Step 2: Sorted Set에서 제거 (WAITING 상태 처리)
-- ACTIVE 상태(배치 승인 후)에서는 이미 제거되어 있으므로 0 반환 — 정상
redis.call('ZREM', queueKey, userId)

-- Step 3: active 키 삭제
redis.call('DEL', activeKey)

-- Step 4: 토큰 정리 (ACTIVE 상태 처리)
local token = redis.call('GET', userTokenKey)
if token then
    redis.call('DEL', 'queue:token:' .. token)
    redis.call('DEL', userTokenKey)
end

return {1}
