--[[
  배치 승인 Lua 스크립트 (REQ-QUEUE-005)

  대기열 Sorted Set에서 상위 N명을 원자적으로 추출하고,
  Queue Token을 발급하여 ACTIVE 상태로 전환한다.

  KEYS[1] = queue:{scheduleId}          -- 대기열 Sorted Set
  KEYS[2] = queue:active-schedules      -- 활성 회차 추적 SET

  ARGV[1] = batchSize                   -- 한 번에 승인할 최대 인원
  ARGV[2] = scheduleId                  -- 회차 ID (토큰 데이터, SREM에 사용)
  ARGV[3] = tokenTtl                    -- queue:token / queue:user-token TTL (초)
  ARGV[4] = activeUserTtl              -- queue:active TTL 갱신 값 (초)
  ARGV[5] = issuedAt                    -- 토큰 발급 시각 (Unix ms, 문자열)
  ARGV[6..6+N-1] = 사전 생성된 token UUID 목록 (N = batchSize)

  Returns: 실제 승인된 사용자 수 (Long)
]]

local queueKey         = KEYS[1]
local activeSchedules  = KEYS[2]

local batchSize    = tonumber(ARGV[1]) or 10
local scheduleId   = ARGV[2]
local tokenTtl     = tonumber(ARGV[3])
local activeUserTtl = tonumber(ARGV[4])
local issuedAt     = ARGV[5]

-- Step 1: 대기열 상위 N명 조회
local members = redis.call('ZRANGE', queueKey, 0, batchSize - 1)

-- Step 2: 대기열이 비어 있으면 active-schedules에서 제거 후 즉시 반환
if #members == 0 then
    redis.call('SREM', activeSchedules, scheduleId)
    return 0
end

-- Step 3: Sorted Set에서 원자적 제거
redis.call('ZREM', queueKey, unpack(members))

-- Step 4: 각 사용자에 대해 Token 발급 및 ACTIVE 상태 전환
for i, userId in ipairs(members) do
    local token = ARGV[5 + i]  -- ARGV[6] = 첫 번째 토큰, ARGV[7] = 두 번째 토큰, ...

    -- queue:token:{token} — 토큰 메타데이터 (다른 서비스에서 검증용)
    local tokenData = '{"userId":"' .. userId .. '","scheduleId":"' .. scheduleId .. '","issuedAt":"' .. issuedAt .. '"}'
    redis.call('SET', 'queue:token:' .. token, tokenData, 'EX', tokenTtl)

    -- queue:user-token:{userId}:{scheduleId} — 역방향 토큰 조회 키 (queue-status.lua가 ACTIVE 판단에 사용)
    redis.call('SET', 'queue:user-token:' .. userId .. ':' .. scheduleId, token, 'EX', tokenTtl)

    -- queue:active:{userId} TTL 갱신 — 배치 승인 후에도 이탈 시 정리 가능하도록 유지
    redis.call('EXPIRE', 'queue:active:' .. userId, activeUserTtl)
end

-- Step 5: 대기열이 비었으면 active-schedules에서 제거
if redis.call('ZCARD', queueKey) == 0 then
    redis.call('SREM', activeSchedules, scheduleId)
end

return #members
