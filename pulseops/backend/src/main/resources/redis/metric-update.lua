-- Telemetry Event 하나를 Metric Bucket에 정확히 한 번만 반영한다.
--
-- KEYS[1] dedupKey          이미 반영한 eventId인지 표시하는 Key
-- KEYS[2] countsKey         requestCount / errorCount를 담는 HASH
-- KEYS[3] latencyKey        latency 표본을 담는 LIST
-- KEYS[4] seriesRegistryKey 관측된 계열을 모아 두는 ZSET
-- ARGV[1] errorDelta        0 또는 1
-- ARGV[2] latencyMs
-- ARGV[3] sampleCap         LIST에 남길 최근 표본 수
-- ARGV[4] metricTtlSeconds
-- ARGV[5] dedupTtlSeconds
-- ARGV[6] seriesMember      registry에 넣을 계열 식별자
-- ARGV[7] bucketStartEpochSecond  registry score로 쓸 Bucket 시작 시각
--
-- 반환 1이면 이번 호출이 Metric을 바꿨고, 0이면 이미 반영된 Event라 아무것도 바꾸지 않았다.
--
-- Kafka는 같은 Event를 다시 전달할 수 있다. Redis는 script를 실행하는 동안 다른 Client의 명령이
-- 끼어들지 않게 하므로, dedup 표시와 counter 증가 사이에 같은 Event의 다른 사본이 들어올 수 없다.
-- 다만 script 도중에 난 오류가 앞서 실행된 write를 되돌려 주지는 않는다. 그래서 실패할 수 있는
-- 값 검증을 첫 write보다 먼저 끝낸다.

local errorDelta = tonumber(ARGV[1])
local latencyMs = ARGV[2]
local sampleCap = tonumber(ARGV[3])
local metricTtlSeconds = tonumber(ARGV[4])
local dedupTtlSeconds = tonumber(ARGV[5])
local seriesMember = ARGV[6]
local bucketStartEpochSecond = tonumber(ARGV[7])

if not errorDelta or not tonumber(latencyMs) or not sampleCap or not metricTtlSeconds or not dedupTtlSeconds
		or not bucketStartEpochSecond or not seriesMember or seriesMember == ''
		or sampleCap < 1 or metricTtlSeconds < 1 or dedupTtlSeconds < 1 then
	return redis.error_reply('metric-update: invalid argument')
end

-- SET NX는 Key가 없을 때만 성공한다. 이 한 번의 성공이 곧 이 Event를 반영할 권리이고, 실패했다면
-- 다른 호출이 이미 반영한 것이므로 counter를 건드리지 않고 끝낸다. 만료는 이 자리에서만 정해지므로
-- 중복 호출이 dedup 표시의 수명을 늘리지 못한다.
if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', dedupTtlSeconds) then
	return 0
end

-- 오류가 아니어도 errorCount를 0만큼 올린다. 두 field가 항상 함께 존재해야 읽는 쪽이 field 없음과
-- 0을 구분하지 않아도 된다.
redis.call('HINCRBY', KEYS[2], 'requestCount', 1)
redis.call('HINCRBY', KEYS[2], 'errorCount', errorDelta)
redis.call('EXPIRE', KEYS[2], metricTtlSeconds)

-- 뒤에 넣고 뒤에서부터 남긴다. 앞에서부터 남기면 Bucket이 처음 채워진 순간의 표본이 그대로 굳어
-- 이후 요청이 P95에 전혀 반영되지 않는다.
redis.call('RPUSH', KEYS[3], latencyMs)
redis.call('LTRIM', KEYS[3], -sampleCap, -1)
redis.call('EXPIRE', KEYS[3], metricTtlSeconds)

-- 어떤 계열이 관측되고 있는지를 남긴다. Dashboard는 Key를 훑지 않고 이 ZSET만 읽어 계열을 찾는다.
-- Metric의 의미에는 관여하지 않는 discovery용 부산물이라, 이 write가 없어도 집계와 Incident 판정은
-- 그대로 성립한다.
--
-- score는 이 Event가 속한 Bucket의 시작이다. 벽시계를 쓰면 늦게 도착한 Event가 계열을 실제보다
-- 최근인 것으로 만들어, 이미 조용해진 계열이 계속 살아 있는 것처럼 보인다.
--
-- member마다 만료를 걸 수 없어 Key 전체에 만료를 건다. 오래된 member는 읽는 쪽이 score로 걸러 내고,
-- 트래픽이 완전히 끊기면 이 Key 자체가 사라진다.
redis.call('ZADD', KEYS[4], bucketStartEpochSecond, seriesMember)
redis.call('EXPIRE', KEYS[4], metricTtlSeconds)

return 1
