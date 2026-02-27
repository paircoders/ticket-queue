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

return members
