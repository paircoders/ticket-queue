--[[
  Rate Limit 검사 Lua 스크립트 (REQ-QUEUE-008)

  고정 윈도우 방식으로 사용자별 요청 횟수를 원자적으로 카운트한다.
  INCR + EXPIRE를 단일 스크립트로 묶어 Race Condition을 방지한다.

  KEYS[1] = rate:queue-status:{userId}

  ARGV[1] = maxRequests   (기본 15)
  ARGV[2] = windowSeconds (기본 60)

  Returns:
    0 : 요청 허용
    1 : 한도 초과 (RATE_LIMIT_EXCEEDED)
]]

local key           = KEYS[1]
local maxRequests   = tonumber(ARGV[1])
local windowSeconds = tonumber(ARGV[2])

local current = redis.call('INCR', key)

-- 최초 요청 시 TTL 설정 (이후 요청은 기존 TTL 유지)
if current == 1 then
    redis.call('EXPIRE', key, windowSeconds)
end

if current > maxRequests then
    return 1
end

return 0
