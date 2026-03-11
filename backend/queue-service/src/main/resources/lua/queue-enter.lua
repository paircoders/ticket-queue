--[[
  대기열 진입 Lua 스크립트 (REQ-QUEUE-001, REQ-QUEUE-006, REQ-QUEUE-011)

  ZCARD, ZADD, SET 간의 Race Condition을 단일 원자적 실행으로 해결한다.

  KEYS[1] = queue:{scheduleId}          -- 대기열 Sorted Set
  KEYS[2] = queue:active:{userId}       -- 중복 대기 방지 키
  KEYS[3] = queue:active-schedules      -- 활성 회차 추적 SET (배치 승인 대상 탐색용)

  ARGV[1] = userId                  -- 대기열에 추가할 사용자 ID
  ARGV[2] = timestamp               -- score (진입 시각, Unix ms)
  ARGV[3] = maxCapacity             -- 대기열 최대 수용 인원 (기본 50000)
  ARGV[4] = activeUserTtl           -- queue:active 키 TTL (초, 기본 600)
  ARGV[5] = scheduleId              -- 진입 요청한 회차 ID

  Returns:
    {0, rank} : 신규 진입 성공 (rank = ZRANK, 0-based)
    {1, rank} : 동일 회차 중복 진입 — 멱등성 처리, 기존 rank 반환
    {2, existingScheduleId} : 다른 회차 대기 중 (ALREADY_IN_QUEUE)
    {3, -1}   : 대기열 가득 참 (QUEUE_FULL)
    {4, -1}   : 이미 배치 승인 완료 — Sorted Set에 존재하지 않음 (ALREADY_APPROVED)
]]

local queueKey        = KEYS[1]
local activeKey       = KEYS[2]
local activeSchedules = KEYS[3]

local userId       = ARGV[1]
local timestamp    = ARGV[2]
local maxCapacity  = tonumber(ARGV[3])
local activeUserTtl = tonumber(ARGV[4])
local scheduleId   = ARGV[5]

-- Step 1: 중복 대기 확인 (REQ-QUEUE-011)
local existingScheduleId = redis.call('GET', activeKey)
if existingScheduleId then
    if existingScheduleId == scheduleId then
        -- 같은 회차에 이미 대기 중 → 멱등성: 기존 순위 반환
        local rank = redis.call('ZRANK', queueKey, userId)
        if rank == false then
            -- ZRANK가 nil: 배치 승인으로 Sorted Set에서 이미 제거된 상태
            return {4, -1}
        end
        -- 멱등성 경로에서 TTL 갱신: 반복 요청 시 active 키 만료 방지
        redis.call('EXPIRE', activeKey, activeUserTtl)
        return {1, rank}
    else
        -- 다른 회차에 대기 중 → 거부
        return {2, existingScheduleId}
    end
end

-- Step 2: 대기열 용량 확인 (REQ-QUEUE-006)
local currentSize = redis.call('ZCARD', queueKey)
if currentSize >= maxCapacity then
    return {3, -1}
end

-- Step 3: 대기열 진입 (REQ-QUEUE-001)
-- NX 옵션: 이미 존재하는 멤버는 score 변경 없이 무시
redis.call('ZADD', queueKey, 'NX', timestamp, userId)

-- Step 3-1: 활성 회차 목록에 등록 (배치 승인 스케줄러가 SMEMBERS로 조회)
-- SADD는 멱등적이므로 동일 scheduleId 중복 등록 무해
redis.call('SADD', activeSchedules, scheduleId)

-- Step 4: 중복 대기 방지 키 등록
redis.call('SET', activeKey, scheduleId, 'EX', activeUserTtl)

-- Step 5: 진입 순위 조회 (0-based, Service에서 1-based로 변환)
local rank = redis.call('ZRANK', queueKey, userId)
return {0, rank}
