--[[
  배치 승인 Lua 스크립트 (REQ-QUEUE-005)

  대기열 Sorted Set에서 상위 N명을 원자적으로 추출하고 제거한다.

  KEYS[1] = queue:{scheduleId}
  ARGV[1] = batchSize (기본 10)

  Returns: 승인된 userId 목록
]]

local queueKey = KEYS[1]
local batchSize = tonumber(ARGV[1]) or 10

local members = redis.call('ZRANGE', queueKey, 0, batchSize - 1)

if #members > 0 then
    redis.call('ZREM', queueKey, unpack(members))
end

-- TODO(Issue #44): 배치 승인 완료 후 역방향 토큰 조회 키를 반드시 SET해야 함
-- queue-status.lua (KEYS[2])가 아래 키를 GET하여 ACTIVE 상태를 판단한다.
-- 미구현 시 배치 승인된 사용자가 NOT_IN_QUEUE로 표시됨 (ACTIVE 분기가 dead code)
-- 구현 예시 (scheduleId, token, tokenTtl을 ARGV로 추가 전달 필요):
--   for _, userId in ipairs(members) do
--     local token = generateToken(userId)  -- Token 생성 로직 별도 구현
--     redis.call('SET', 'queue:user-token:' .. userId .. ':' .. scheduleId, token, 'EX', tokenTtl)
--   end

return members
