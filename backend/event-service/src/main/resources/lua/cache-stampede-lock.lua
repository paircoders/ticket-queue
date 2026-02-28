-- Cache Stampede 방지 Lua 스크립트 (REQ-EVT-021)
--
-- SET NX EX 를 원자적으로 실행하여 락을 획득한다.
-- 동시 다발적 캐시 Miss 시 단 하나의 요청만 DB 조회 + 캐시 저장을 수행하게 한다.
--
-- KEYS[1] = 락 키 (예: cache:lock:event:{eventId})
-- ARGV[1] = 락 TTL (초)
--
-- Returns:
--   1 : 락 획득 성공 (이 요청이 DB 조회 및 캐시 저장을 담당)
--   0 : 락 이미 존재 (다른 요청이 이미 처리 중)

local result = redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1])
if result then
    return 1
else
    return 0
end
