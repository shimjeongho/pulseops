# Performance Validation

## 1. Purpose

이 문서는 PulseOps의 관측 경로 전체가 실제 HTTP 부하 아래에서 이어져 동작하는지를 확인한 기록이다.

확인하려는 것은 다음 여덟 가지이다.

1. 일정한 부하에서 Demo Shop HTTP가 어떤 응답 시간과 오류 특성을 보이는가
2. 부하 중 Kafka Telemetry가 정상적으로 전달되는가
3. PulseOps Consumer가 그 Telemetry를 따라가는가
4. Consumer Lag이 늘어난다면 부하가 끝난 뒤 다시 줄어드는가
5. Redis Rolling Metric이 부하를 반영하는가
6. Incident 규칙이 의도한 장애 상황에서 동작하는가
7. Prometheus와 Grafana에서 그 현상을 관측할 수 있는가
8. 현재 구조에서 관측 가능한 병목 후보는 무엇인가

**이 문서가 목적으로 하지 않는 것**을 먼저 밝힌다. 아래 중 어느 것도 이 문서의 숫자로 주장할 수 없다.

- 최대 처리량(TPS) 인증
- 운영 환경의 용량 산정
- SLA 검증
- 서버 한계치 측정
- 하드웨어와 무관한 benchmark
- 다른 제품과의 비교

부하를 만드는 쪽의 속도를 k6 script가 미리 정해 두었으므로, 이것은 애초에 한계를 재는 시험이 아니다.
정해진 속도로 요청을 보냈을 때 관측 경로가 무엇을 보여 주는지를 확인하는 시험이다.

## 2. Environment

모든 숫자는 아래 환경에 종속된 local benchmark이다. 다른 하드웨어, 다른 Docker 자원 할당, 다른
동시 실행 부하에서는 다른 값이 나온다.

| 항목 | 값 |
| --- | --- |
| 측정 일자 | 2026-09-08 (UTC) |
| 측정 구간 | 00:50 ~ 01:06 UTC |
| OS | Microsoft Windows 11 Home 10.0.26200 (Build 26200) |
| CPU | Intel Core i7-8700K @ 3.70GHz |
| Core | 물리 6 / 논리 12 |
| Host Memory | 15.94 GB (시험 시작 시점 여유 3.86 GB) |
| Docker Engine | 29.7.2 (Client/Server 동일) |
| Docker Compose | v5.4.0 |
| Docker 자원 할당 | CPU 12, Memory 8,290,705,408 bytes (약 7.72 GiB) |
| Java | OpenJDK 21.0.12 (Microsoft-14653896) |
| Node.js | v24.19.0 / npm 11.17.0 |
| Kafka | apache/kafka:4.0.0 (KRaft, 단일 Broker) |
| MySQL | mysql:8.4.11 |
| Redis | redis:8.4.2-alpine |
| Prometheus | prom/prometheus:v3.6.0 |
| Grafana | grafana/grafana:12.2.0 |
| k6 | grafana/k6:1.4.0 (Container 실행) |
| Compose project | `pulseops-phase14` |

### k6 실행 방식

k6는 Host에 설치되어 있지 않았다. `load-test/k6/README.md`가 이미 문서화해 둔 Container 실행 경로를
그대로 사용했다.

```
docker run --rm --network pulseops-phase14_pulseops \
  -e DEMO_SHOP_BASE_URL=http://demo-shop-backend:8080 \
  -e ALLOW_LOAD_TEST=true \
  -v "<repo>/load-test/k6:/scripts:ro" \
  grafana/k6:1.4.0 run /scripts/baseline.js
```

`demo-shop-backend`는 로컬 주소가 아니므로 `ALLOW_LOAD_TEST=true`가 함께 필요하다. 이것은 script의
안전 장치가 의도대로 동작한 결과이며, script를 고쳐 우회하지 않았다.

Compose project를 `pulseops-phase14`로 분리해 실행했다. 기존 `pulseops-phase13_*` volume을 건드리지
않기 위해서이다. 두 project의 volume은 서로 다른 이름을 가지며, 이 시험은 빈 volume 위에서 시작했다.

### Docker Desktop 자원 배분

Docker가 보고한 값(CPU 12, Memory 약 7.72 GiB)은 위 표에 있다. Docker Desktop의 GUI 설정 화면에서
사용자가 지정한 값 자체는 확인하지 않았다.

**NOT VERIFIED FROM LOCAL REPOSITORY**

## 3. Test Scenarios

기존 `load-test/k6`의 script를 그대로 사용했다. 새 시나리오를 만들지 않았고, 부하의 속도와 지속
시간과 threshold를 바꾸지 않았다.

| 시나리오 | script | 부하 | 지속 | 기대 |
| --- | --- | --- | --- | --- |
| Baseline | `baseline.js` | 5→20 req/s ramping | 15s + 90s + 10s | Incident 없음 |
| Error Incident | `error-incident.js` | 20 req/s 고정 | 90s | ERROR_RATE CRITICAL |
| Recovery | `baseline.js` 재실행 | 5→20 req/s ramping | 15s + 90s + 10s | Incident RESOLVED |
| Latency Incident | `latency-incident.js` | 6 req/s 고정 | 90s | LATENCY CRITICAL |

Recovery는 별도 script가 아니다. 장애 주입 Header를 붙이지 않는 `baseline.js`를 그대로 다시 보내는
것이 곧 회복 트래픽이다. 회복은 "장애를 멈추는 것"이 아니라 "정상 요청이 다시 흐르는 것"으로만
확인할 수 있다.

네 시나리오 모두 시작점은 Demo Shop의 HTTP 요청이다. Kafka에 Event를 직접 넣지 않았다. 그래야
TelemetryFilter, Producer, Kafka, Consumer, Redis, 판정, MySQL, SSE가 전부 검증 범위에 들어온다.

### 이번 Phase에서 k6에 가한 변경

`lib/common.js`에 `SUMMARY_TREND_STATS`를 추가하고 세 script의 `options`에 `summaryTrendStats`로
연결했다. k6 기본 summary에는 p(99)가 없어 응답 시간의 꼬리를 기록할 수 없었기 때문이다.

이 값은 **출력에만 관여한다.** 부하의 속도, 지속 시간, threshold, check 어느 것도 바뀌지 않았다.

## 4. Baseline

정상 `GET /api/products` 부하이다. 장애 주입은 이 시점에 꺼져 있었다.

### k6 결과

| 항목 | 값 |
| --- | --- |
| total requests | 2,087 |
| request rate | 18.259616 /s |
| HTTP 실패 | 0.00% (0 / 2,087) |
| checks | 100.00% (2,087 / 2,087) |
| average | 3.96 ms |
| median | 3.09 ms |
| p90 | 6.19 ms |
| p95 | 9.33 ms |
| p99 | 11.41 ms |
| min | 2.28 ms |
| max | 18.97 ms |

### Threshold 결과

| Threshold | 기준 | 결과 |
| --- | --- | --- |
| `checks` | rate > 0.99 | 통과 (100.00%) |
| `http_req_duration` | p(95) < 1500ms | 통과 (9.33ms) |
| `http_req_failed` | rate < 0.01 | 통과 (0.00%) |

요청 속도 20 req/s를 목표로 했으나 실측은 18.26 /s이다. `ramping-arrival-rate`가 15초 동안 5에서
20으로 올라가는 구간을 포함하기 때문이며, 부하 생성 실패가 아니다.

### 파이프라인 관측

| 관측 지점 | 결과 |
| --- | --- |
| Redis rolling window 최대 요청 수 | 1,052 건 / 60초 창 |
| Redis window 오류율 | 0.00% 유지 |
| Redis window P95 | 8 ~ 23 ms |
| Consumer Lag | 대부분 0, 최대 1 |
| Incident | **열리지 않음** |

정상 트래픽만으로 Incident가 열리지 않는다는 것은 판정 기준이 정상 부하를 장애로 오인하지 않는다는
뜻이다. 이것이 baseline 시나리오의 핵심 확인 사항이다.

### JVM (PulseOps Backend, Prometheus 관측)

| 항목 | 최소 | 최대 |
| --- | --- | --- |
| Heap used | 53.7 MB | 144.5 MB |
| Live threads | 48 | 48 |
| Process CPU | 0.04% | 1.47% |
| System CPU (container 관점) | 1.71% | 21.06% |

Heap이 오르내리는 것은 GC가 도는 정상 형태이다. Thread 수는 시험 내내 48로 고정되었다.

## 5. Error Incident

모든 요청에 `X-Demo-Fault: ERROR_503`을 붙였다. 이 시나리오를 위해 Demo Shop Backend를
`DEMO_SHOP_FAULT_INJECTION_ENABLED=true`로 다시 올렸다.

Compose 설정 파일의 기본값은 바꾸지 않았다. `docker-compose.yml`의 기본값은 여전히 `false`이며,
이 시험은 실행 시점에만 환경 변수로 켰다.

**장애 주입 기본 disabled 계약을 시험 전에 확인했다.** 켜기 전에 같은 Header를 보냈을 때 응답은
200이었다. 꺼져 있으면 Interceptor 자체가 등록되지 않아 그 Header는 아무 의미도 없는 평범한 Header가
된다.

### k6 결과

| 항목 | 값 |
| --- | --- |
| total requests | 1,801 |
| request rate | 20.011337 /s |
| `expected_503` | 100.00% (1,801 / 1,801) |
| checks | 100.00% (1,801 / 1,801) |
| average | 1.92 ms |
| median | 1.55 ms |
| p90 | 3.21 ms |
| p95 | 4.05 ms |
| p99 | 6.43 ms |
| min | 889.72 µs |
| max | 9.61 ms |

`http_req_failed`는 100%이다. 이 시나리오가 의도한 결과이므로 script는 여기에 threshold를 걸지
않고 `expected_503`을 따로 센다.

503 응답이 정상 응답보다 빠른 것은 Fault Injection이 Business 처리 **전에** 요청을 끝내기 때문이다.
DB 조회가 일어나지 않는다.

### Incident 결과

| 항목 | 값 |
| --- | --- |
| Incident ID | 1 |
| type | ERROR_RATE |
| severity | **CRITICAL** |
| status | OPEN |
| service / method / endpoint | demo-shop / GET / /api/products |
| openedAt | 2026-09-08T00:57:10Z |
| trigger window | 00:56:10Z ~ 00:57:10Z |
| trigger requestCount | 316 |
| trigger errorCount | 314 |
| trigger errorRate | 99.37% |
| trigger P95 | 2 ms |

부하 시작 약 20초 뒤에 Incident가 열렸다. 이상인 창이 두 번 연속되어야 열리고 Bucket이 10초이므로,
20초는 규칙이 정한 최소 시간과 일치한다.

`triggerRequestCount` 316은 60초 창의 요청 수이고, `errorRate` 99.37%는 창의 앞부분에 정상 요청
두 건이 남아 있어 100%가 아니다. CRITICAL 기준선 25%를 크게 넘는다.

### 관측 경로

| 관측 지점 | 결과 |
| --- | --- |
| Redis window 오류율 | 98.65% → 100.0% |
| Redis window 요청 수 | 최대 1,090 건 / 60초 창 |
| Consumer Lag | 시험 내내 0 |
| `pulseops_incidents_active{type="ERROR_RATE",severity="CRITICAL"}` | 1 |
| `pulseops_incidents_opened_total{type="ERROR_RATE",severity="CRITICAL"}` | 1 |
| Telemetry `applied` 누적 | 4,176 → 5,980 (증가 1,804) |
| Telemetry `failed` / `rejected` / `duplicate` | 전부 0 |

증가분 1,804는 k6 요청 1,801건과 확인용 수동 요청 3건의 합과 정확히 같다.

### JVM

| 항목 | 최소 | 최대 |
| --- | --- | --- |
| Heap used | 57.5 MB | 138.1 MB |
| Live threads | 48 | 48 |
| Process CPU | 0.08% | 2.12% |

## 6. Recovery

`ERROR_503` Header 없이 `baseline.js`를 다시 실행했다. Demo Shop의 장애 주입 스위치는 켜진 채였으나
Header가 없으므로 모든 요청이 정상 처리되었다.

회복 확인 전에 Incident를 확인 처리(ACK)하여 `OPEN → ACKNOWLEDGED` 전이를 함께 검증했다.

### k6 결과

| 항목 | 값 |
| --- | --- |
| total requests | 2,087 |
| request rate | 18.259395 /s |
| HTTP 실패 | 0.00% (0 / 2,087) |
| checks | 100.00% |
| average | 5.33 ms |
| median | 4.56 ms |
| p90 | 7.82 ms |
| p95 | 11.48 ms |
| p99 | 15.83 ms |
| max | 28.96 ms |

Threshold 셋 모두 통과했다.

### Incident 전이

| 시각 (UTC) | 전이 | 근거 |
| --- | --- | --- |
| 00:57:10 | → OPEN | 이상 창 2회 연속 |
| 00:59:32 | OPEN → ACKNOWLEDGED | `POST /api/incidents/1/acknowledge` |
| 01:00:10 | ACKNOWLEDGED → **RESOLVED** | 정상 창 3회 연속 |

`RESOLVED`가 `ACKNOWLEDGED`에서 곧바로 일어났다. 회복은 서비스의 사실이고 확인 여부는 사람의 일이므로,
Detector는 사람이 확인했는지를 보지 않고 Incident를 끝낸다.

### SSE

Incident Stream을 `GET /api/incidents/stream`으로 열어 두고 관측했다.

```
00:59:27  event:connected          {"connectedAt":"2026-09-08T00:59:27.204677433Z"}
00:59:32  event:incident-changed   {"incidentId":1,"occurredAt":"2026-09-08T00:59:32.336841674Z"}
00:59:36  event:heartbeat
00:59:56  event:heartbeat
01:00:10  event:incident-changed   {"incidentId":1,"occurredAt":"2026-09-08T01:00:10Z"}
01:00:16  event:heartbeat
...
```

- 연결 즉시 `connected`가 온다.
- ACK와 RESOLVED에서 각각 `incident-changed`가 온다.
- `heartbeat`가 20초 간격으로 온다.
- `incident-changed`는 Incident의 상태를 싣지 않고 ID와 시각만 싣는다. 화면은 이 알림을 받고 REST로
  다시 읽는다.

`occurredAt`이 RESOLVED에서 `01:00:10Z`인 것은 알림 시각이 아니라 전이에 쓴 창의 끝이기 때문이다.
Incident의 `resolvedAt`과 같은 값이다.

### Prometheus

| Metric | 값 |
| --- | --- |
| `pulseops_incidents_active{type="ERROR_RATE"}` | 1 → 0 |
| `pulseops_incidents_resolved_total{type="ERROR_RATE"}` | 1 |

## 7. Latency Incident

모든 요청에 `X-Demo-Fault: LATENCY_3500`을 붙였다.

### k6 결과

| 항목 | 값 |
| --- | --- |
| total requests | 541 |
| request rate | 5.786029 /s |
| `expected_delayed_2xx` | 100.00% (541 / 541) |
| HTTP 실패 | **0.00%** (0 / 541) |
| checks | 100.00% |
| average | 3.50 s |
| median | 3.50 s |
| p90 | 3.50 s |
| p95 | 3.51 s |
| p99 | 3.51 s |
| min | 3.50 s |
| max | 3.53 s |
| VU (동시) | 최소 4, 최대 21 |

요청 하나가 3.5초 동안 Servlet Thread를 붙들기 때문에 초당 6건을 내보내는 데 21개의 VU가 동시에
필요했다. script가 예상한 값과 같다.

### Incident 결과

| 항목 | 값 |
| --- | --- |
| Incident ID | 2 |
| type | LATENCY |
| severity | **CRITICAL** |
| status | OPEN |
| openedAt | 2026-09-08T01:03:10Z |
| trigger window | 01:02:10Z ~ 01:03:10Z |
| trigger requestCount | 96 |
| trigger errorCount | **0** |
| trigger errorRate | **0.00%** |
| trigger P95 | **3,508 ms** |

### 오류율과 응답 시간의 분리

이 시나리오의 가장 중요한 확인 사항이다.

- HTTP 응답은 전부 200이었다.
- Redis window의 `errorCount`는 시험 내내 **0**이었다.
- 따라서 `ERROR_RATE` Incident는 **열리지 않았다.**
- 열린 것은 P95 3,508ms 하나 때문인 `LATENCY` Incident뿐이다.

느린 것과 실패한 것은 다른 일이다. PulseOps의 오류율은 5xx만 세므로, 지연 하나가 두 종류의 Incident를
동시에 만들지 않는다. 두 종류가 독립된 Incident라는 계약이 실측으로 확인되었다.

### 관측 경로

| 관측 지점 | 결과 |
| --- | --- |
| Redis window P95 | 3,506 ~ 3,508 ms (안정적) |
| Redis window errorRate | 0.0% 유지 |
| Consumer Lag | 시험 내내 0 |
| `pulseops_incidents_active{type="LATENCY",severity="CRITICAL"}` | 1 |
| `pulseops_incidents_opened_total{type="LATENCY",severity="CRITICAL"}` | 1 |
| SSE | `incident-changed {"incidentId":2,"occurredAt":"2026-09-08T01:03:10Z"}` |

### JVM

| 항목 | 최소 | 최대 |
| --- | --- | --- |
| Heap used | 65.6 MB | 159.0 MB |
| Live threads | 48 | 48 |
| Process CPU | 0.08% | 0.47% |

PulseOps의 CPU 사용률이 오히려 baseline보다 낮다. 부하 속도가 20 req/s가 아니라 6 req/s이므로
PulseOps가 처리할 Event 수 자체가 적기 때문이다. 지연은 Demo Shop 쪽에서 일어난다.

## 8. Consumer Lag

### 시나리오별 관측

| 구간 | total lag | 비고 |
| --- | --- | --- |
| Baseline 시작 전 | 0 | partition 0만 INITIALIZED |
| Baseline 중 | 0 (최대 1) | |
| Error Incident 중 | 0 | |
| Recovery 중 | 0 (최대 1) | |
| Latency Incident 중 | 0 | |
| 전체 종료 후 | 0 | |

**최대 관측 lag: 1건.**

### Partition 상태

```json
{
  "topic": "demo-shop.telemetry.api.v1",
  "consumerGroup": "pulseops-metric-consumer-v1",
  "partitionCount": 3,
  "uninitializedPartitionCount": 2,
  "totalLag": 0,
  "partitions": [
    { "partition": 0, "logEndOffset": 8608, "committedOffset": 8608, "lag": 0,    "state": "INITIALIZED" },
    { "partition": 1, "logEndOffset": 0,    "committedOffset": null, "lag": null, "state": "UNINITIALIZED" },
    { "partition": 2, "logEndOffset": 0,    "committedOffset": null, "lag": null, "state": "UNINITIALIZED" }
  ]
}
```

Topic은 partition 3개를 갖지만 **모든 Event가 partition 0으로만 갔다.** partition 1과 2는 record를
한 건도 받지 못해 `UNINITIALIZED`로 남았다.

원인은 Producer의 message key이다.

```java
private String keyOf(TelemetryEvent event) {
    return event.service() + ":" + event.endpoint();
}
```

네 시나리오 모두 `GET /api/products` 하나만 때렸으므로 key가 언제나
`demo-shop:/api/products`로 같았고, Kafka는 같은 key를 같은 partition으로 보낸다. 이것은 결함이 아니라
설계대로 동작한 결과이다. 같은 계열의 Event가 순서대로 처리되려면 한 partition에 모여야 한다.

### 중요: lag 0의 의미

**lag이 0이라는 것은 "처리 능력이 무한하다"는 뜻이 아니다.**

이 시험이 보낸 부하(최대 20 req/s)에서 Consumer가 Producer를 따라갔다는 뜻일 뿐이다. 부하를 올렸을 때
어디서부터 lag이 쌓이기 시작하는지는 이 시험이 재지 않았다.

또한 lag이 늘었다가 줄어드는 회복 곡선도 관측하지 못했다. 늘어난 적이 없기 때문이다. "부하 종료 후
lag이 감소하는가"라는 질문은 이 시험에서 **관측되지 않았다.**

### 전달 정합성

| Metric | 값 |
| --- | --- |
| partition 0 `logEndOffset` | 8,608 |
| partition 0 `committedOffset` | 8,608 |
| `pulseops_telemetry_events_processed_total{result="applied"}` | 8,608 |
| `pulseops_telemetry_events_processed_total{result="duplicate"}` | 0 |
| `pulseops_telemetry_events_processed_total{result="out_of_window"}` | 0 |
| `pulseops_telemetry_events_failed_total` | 0 |
| `pulseops_telemetry_events_rejected_total` | 0 |

Kafka에 기록된 record 수와 Redis에 반영된 Event 수가 정확히 같다. **이번 실행에서는** 중복도 유실도
없었다.

이것은 exactly-once의 증명이 아니다. 재시도도 Consumer 재시작도 Redis 장애도 일어나지 않은 구간의
관측이다. 그 조건에서 at-least-once 처리와 Redis dedup이 결과적으로 정확히 한 번을 만들어 냈다는
기록이다.

## 9. Prometheus / Grafana Observation

### Prometheus target

```
{application="pulseops", instance="pulseops-backend:8081", job="pulseops"}  health=up  lastError=
```

scrape 대상은 PulseOps Backend 하나뿐이다. **Demo Shop은 Prometheus가 긁지 않는다.** 따라서
Prometheus에 있는 `http_server_requests_*`는 PulseOps 자신의 REST API에 대한 값이고, Demo Shop의
응답 시간이 아니다. Demo Shop의 응답 시간은 k6 결과와 PulseOps의 Redis Metric에서 온다.

Dashboard의 해당 행이 `HTTP — PulseOps API`로 이름 붙어 있는 것도 같은 이유이다.

### 실제로 노출되는 metric 이름

`/actuator/prometheus`에서 직접 확인한 이름이다.

| 용도 | metric |
| --- | --- |
| Incident 진행 중 | `pulseops_incidents_active{type,severity}` |
| Incident 열림 누적 | `pulseops_incidents_opened_total{type,severity}` |
| Incident 종료 누적 | `pulseops_incidents_resolved_total{type}` |
| Telemetry 처리 결과 | `pulseops_telemetry_events_processed_total{result}` |
| Telemetry 실패 | `pulseops_telemetry_events_failed_total` |
| Telemetry 거절 | `pulseops_telemetry_events_rejected_total` |
| Consumer Lag 합계 | `pulseops_kafka_consumer_lag` |
| Consumer Lag partition별 | `pulseops_kafka_consumer_partition_lag{partition}` |
| 미초기화 partition 수 | `pulseops_kafka_consumer_partitions_uninitialized` |
| Lag 표본 나이 | `pulseops_kafka_consumer_lag_snapshot_age_seconds` |
| HTTP (PulseOps API) | `http_server_requests_seconds_{count,sum,bucket,max}` |
| JVM Heap | `jvm_memory_used_bytes{area="heap"}` |
| JVM Thread | `jvm_threads_live_threads` |
| CPU | `process_cpu_usage`, `system_cpu_usage` |

`processed_total`의 `result` 값은 `applied`, `duplicate`, `out_of_window` 셋이다.

### Grafana

| 확인 항목 | 결과 |
| --- | --- |
| Datasource provisioning | `Prometheus` (uid `pulseops-prometheus`) |
| Datasource health | `"Successfully queried the Prometheus API." status=OK` |
| Dashboard provisioning | 폴더 `PulseOps` / Dashboard `PulseOps Overview` (uid `pulseops-overview`) |
| Panel 수 | 20 (row 5개 포함) |

Dashboard의 모든 PromQL을 Prometheus에 직접 질의해 데이터가 돌아오는지 확인했다. 결과 예시는 아래와
같다.

| PromQL | 반환값 |
| --- | --- |
| `sum by (type, severity) (pulseops_incidents_active{job="pulseops"})` | LATENCY/CRITICAL = 1, 나머지 0 |
| `sum by (type, severity) (increase(pulseops_incidents_opened_total[10m]))` | ERROR_RATE/CRITICAL ≈ 1, LATENCY/CRITICAL ≈ 1 |
| `sum by (type) (increase(pulseops_incidents_resolved_total[10m]))` | ERROR_RATE ≈ 1 |
| `sum by (result) (rate(pulseops_telemetry_events_processed_total[5m]))` | applied ≈ 5.09 /s |
| `pulseops_kafka_consumer_lag` | 0 |
| `pulseops_kafka_consumer_partitions_uninitialized` | 2 |
| `pulseops_kafka_consumer_lag_snapshot_age_seconds` | 4.995 |
| `sum(jvm_memory_used_bytes{area="heap"})` | 149,890,320 |
| `jvm_threads_live_threads` | 48 |

**빈 결과를 반환하거나 오류가 나는 panel은 없었다.** Dashboard를 수정하지 않았다.

브라우저에서 panel을 눈으로 확인하지는 않았다. 이를 위해 browser automation을 새로 설치하지 않았기
때문이다. Datasource health와 PromQL 응답으로 대신했다.

**Grafana panel 렌더링 결과: NOT VERIFIED**

## 10. Bottleneck Analysis

이 절은 병목 "후보"를 정리한 것이다. 아래 어느 것도 증명된 병목이 아니다. 병목을 증명하려면 부하를
올려 가며 어느 자원이 먼저 포화되는지를 봐야 하는데, 이 시험은 부하를 미리 정해 두고 보냈다.

### 10.1 Demo Shop Servlet Thread

**Observation** — LATENCY 시나리오에서 초당 6건을 내보내는 데 동시 VU가 최대 21개까지 올라갔다.

**Evidence** — k6 `vus: min=4 max=21`. 요청 하나가 3.5초를 점유한다. 6 req/s × 3.5s ≈ 21.

**Interpretation** — 응답 시간이 길어지면 필요한 동시 처리 수가 선형으로 늘어난다. Tomcat의 기본
Thread pool은 200이므로, 같은 3.5초 지연에서 초당 약 57건을 넘기면 Thread pool이 먼저 마른다.

**Limitation** — 그 지점까지 부하를 올려 보지 않았다. 계산에서 나온 숫자이지 관측된 한계가 아니다.

### 10.2 Kafka Partition 배분

**Observation** — Topic의 partition 3개 중 1개만 사용되었고 2개는 `UNINITIALIZED`로 남았다.

**Evidence** — partition 0의 `logEndOffset` 8,608, partition 1과 2는 0. Producer key는
`service:endpoint`이며 네 시나리오가 모두 같은 endpoint 하나를 때렸다.

**Interpretation** — 현재 부하 형태에서는 Consumer의 병렬 처리가 전혀 쓰이지 않았다. Consumer가
따라갔다는 사실은 partition 하나만으로 따라갔다는 뜻이다. 반대로 말하면, endpoint가 여러 개인
트래픽에서는 지금보다 나은 분산을 기대할 수 있다.

**Limitation** — endpoint를 여러 개 쓰는 부하를 실행하지 않았다. 여러 partition이 실제로 병렬 처리되는지는
이 시험이 확인하지 않았다.

### 10.3 PulseOps Consumer 처리 여력

**Observation** — 모든 시나리오에서 Consumer Lag이 0이었고 PulseOps의 process CPU는 최대 2.12%였다.

**Evidence** — Prometheus `process_cpu_usage` 최대 0.0212, `jvm_threads_live_threads` 48 고정,
Heap 최대 159 MB.

**Interpretation** — 20 req/s 수준에서 PulseOps Consumer는 포화와 거리가 멀다. 이 부하에서 병목이
PulseOps 안에 있다고 볼 근거가 없다.

**Limitation** — 포화 지점이 어디인지 모른다. 자원 여유가 많다는 것과 어디까지 견딘다는 것은 다른
말이다.

### 10.4 Redis Lua Script

**Observation** — Event 8,608건이 전부 `applied`로 반영되었고 실패가 0이었다.

**Evidence** — `pulseops_telemetry_events_processed_total{result="applied"} = 8608`, `failed = 0`.

**Interpretation** — Event 하나당 Lua script 한 번 실행이고 그 안에서 SET/HINCRBY×2/EXPIRE/RPUSH/
LTRIM/EXPIRE/ZADD/EXPIRE가 일어난다. 이 부하에서는 문제가 되지 않았다.

**Limitation** — Redis 자체의 지표(CPU, 명령 처리량, latency)를 수집하지 않았다. Redis는 Prometheus의
scrape 대상이 아니다. Redis가 여유로웠는지는 **관측하지 않았고** PulseOps 쪽에서 실패가 없었다는
간접 근거만 있다.

### 10.5 MySQL / Incident 판정

**Observation** — Incident 판정은 Event마다 일어나며 DetectionState와 Incident에 행 잠금을 건다.

**Evidence** — 이번 시험에서 Incident는 2건만 열렸고 판정으로 인한 실패나 지연은 관측되지 않았다.

**Interpretation** — 같은 계열의 Event는 같은 partition에서 순서대로 처리되므로 잠금 경합이 생기지
않는다. 계열이 하나뿐인 이 부하에서는 경합 자체가 성립하지 않는다.

**Limitation** — 여러 계열이 동시에 판정되는 상황을 부하로 재현하지 않았다. MySQL의 지표도 수집하지
않았다.

### 10.6 관측하지 않은 것

정직하게 남긴다. 아래는 이 시험이 **재지 않았다.**

- Demo Shop Backend의 JVM/CPU/Heap (Prometheus scrape 대상이 아니다)
- MySQL, Redis, Kafka container의 자원 사용량
- GC pause 분포
- DB connection pool 점유율
- 부하를 올렸을 때의 포화 지점
- 여러 endpoint를 동시에 때리는 부하
- Consumer 재시작 중의 중복 처리
- Redis 장애 구간의 Event 유실

## 11. Limitations

1. **단일 실행이다.** 각 시나리오를 한 번씩만 돌렸다. 반복 실행의 분산을 모른다.
2. **로컬 단일 머신이다.** 부하 생성기(k6), 관측 대상(Demo Shop), 관측자(PulseOps), 저장소가 전부
   같은 Docker Host에서 CPU와 memory를 나눠 쓴다. 네트워크 지연은 사실상 0이다.
3. **모든 구성 요소가 Instance 하나이다.** Kafka Broker 1개, replication factor 1, Application
   Instance 1개씩이다.
4. **부하 속도를 우리가 정했다.** 한계를 재는 시험이 아니다.
5. **계열이 하나뿐이다.** `GET /api/products` 하나만 때렸다.
6. **Consumer Lag이 늘어난 적이 없다.** 따라서 lag 회복 곡선을 관측하지 못했다.
7. **Demo Shop의 내부 지표가 없다.** Prometheus가 Demo Shop을 긁지 않는다.
8. **Grafana를 브라우저로 확인하지 않았다.** API 응답으로만 확인했다.
9. **Docker Desktop의 사용자 지정 자원 값을 확인하지 못했다.**
10. **AI 분석과 GitHub 코드 수집은 이 시험에 포함되지 않았다.** 둘 다 꺼진 상태였고, 부하 경로에
    관여하지 않는다.

## 12. Claims We Can Make

이번 측정으로 말할 수 있는 것이다.

- 위 §2 환경의 Docker Compose stack에서 `GET /api/products`에 대해 약 18 req/s를 115초 동안 보냈을 때,
  Demo Shop의 응답은 p95 9.33ms / p99 11.41ms였고 실패는 0건이었다.
- 같은 부하에서 Demo Shop HTTP → TelemetryFilter → Kafka → PulseOps Consumer → Redis Metric으로
  이어지는 경로가 끊기지 않고 동작했다.
- 이 실행에서 Kafka에 기록된 record 8,608건과 Redis에 반영된 Event 8,608건이 일치했고, 중복·실패·
  거절·창 이탈이 모두 0이었다.
- 정상 트래픽만으로는 Incident가 열리지 않았다.
- 503을 100% 주입했을 때 `ERROR_RATE` Incident가 `CRITICAL`로 열렸고, 열리기까지 약 20초가 걸렸다.
  이는 "이상 창 2회 연속" 규칙과 10초 Bucket에서 나오는 최소 시간과 일치한다.
- 정상 트래픽을 다시 보냈을 때 그 Incident가 `RESOLVED`로 종료되었고, `OPEN → ACKNOWLEDGED →
  RESOLVED` 전이가 모두 동작했다.
- 3.5초 지연을 주입했을 때 HTTP 응답은 200을 유지했고 `errorCount`는 0이었으며, P95 3,508ms만으로
  `LATENCY` Incident가 `CRITICAL`로 열렸다. `ERROR_RATE` Incident는 열리지 않았다.
- SSE가 연결 시 `connected`, 상태 변경 시 `incident-changed`, 20초 간격 `heartbeat`를 보냈다.
- Prometheus target이 UP이었고, Grafana Dashboard의 모든 PromQL이 데이터를 반환했다.
- 이 부하 구간에서 PulseOps Backend의 process CPU는 최대 2.12%, Heap은 최대 159MB, Thread는 48개
  고정이었다.
- 이 부하 구간에서 관측된 최대 Consumer Lag은 1건이었다.

## 13. Claims We Cannot Make

이번 측정으로 **말할 수 없는 것**이다. 문서 어디에서도 아래를 주장하지 않는다.

- **최대 처리량.** "PulseOps는 N TPS를 지원한다"고 말할 수 없다. 부하를 올려 포화 지점을 찾지 않았다.
- **운영 환경 성능.** 이 숫자는 노트북 한 대의 Docker Compose 안에서 나온 값이다.
- **SLA.** 어떤 가용성도 응답 시간 보장도 측정하지 않았다.
- **production ready.** 인증, 이중화, 자원 한도, backup, 배포 전략 어느 것도 없다.
- **exactly-once 전달.** 이번 실행에서 중복이 0이었을 뿐이다. Producer는 실패 시 요청을 살리는
  fail-open이고, Consumer는 at-least-once이며, Redis 장애 구간의 Event는 집계에서 빠질 수 있다.
- **무한한 Consumer 처리 능력.** lag 0은 이 부하를 따라갔다는 뜻이다.
- **lag 회복 능력.** lag이 늘어난 적이 없어 줄어드는 것도 관측하지 못했다.
- **Kafka partition 병렬 처리 검증.** partition 하나만 사용되었다.
- **병목의 위치.** §10은 전부 후보이며 증명이 아니다.
- **AI가 장애를 탐지한다.** AI는 이 경로 어디에도 없다. Incident가 만들어진 뒤 사람이 요청했을 때만
  동작한다.
- **GitHub 코드 맥락이 원인을 확정한다.** 그것은 가설을 보강하는 부분적 근거이다.
