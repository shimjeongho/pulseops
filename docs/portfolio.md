# PulseOps Portfolio Summary

기술 면접과 포트폴리오 설명을 위한 문서이다. 무엇을 만들었는지가 아니라 **왜 그렇게 만들었는지**를
중심으로 정리한다.

## 1. 문제 정의 — 왜 만들었는가

서비스가 아프다는 것을 어떻게 알 것인가.

이 질문에 답하려면 최소한 세 가지를 정해야 한다.

1. **무엇을 관측할 것인가** — 어떤 사실을 남겨야 나중에 판단할 수 있는가
2. **관측이 서비스를 방해하지 않게 하려면** — 관측 때문에 사용자의 주문이 실패하면 안 된다
3. **무엇을 장애로 볼 것인가** — 오류율 몇 %부터가 장애인가, 몇 번 연속이어야 하는가

PulseOps는 이 세 질문에 대한 답을 코드로 만든 것이다.

**만들지 않은 것**도 분명하다. 이것은 CRUD 연습 프로젝트가 아니고, AI chatbot도 아니다. Demo Shop은
관측할 대상이 필요해서 만든 것이지 그 자체가 목적이 아니다.

## 2. 핵심 Architecture — 왜 Kafka / Redis / MySQL을 나눴는가

세 저장소를 쓰는 이유는 **세 종류의 데이터가 수명과 목적이 전혀 다르기 때문**이다.

### Kafka — 두 Application 사이의 유일한 경계

Demo Shop과 PulseOps는 독립된 Spring Boot Application이고, 둘 사이에는 Topic 하나만 있다. PulseOps는
Demo Shop의 Business Database에 접근하지 않는다.

**동기 HTTP 호출로 하지 않은 이유**: 관측자가 느리거나 죽으면 관측 대상의 요청이 함께 느려지거나
실패한다. 관측이 서비스의 가용성을 떨어뜨리는 구조는 애초에 관측을 켤 수 없게 만든다.

**Producer는 fail-open이다.** Event 발행은 HTTP 응답이 Client에게 나간 **뒤**에 일어나고, 실패하면
WARN log만 남긴다. `max.block.ms`를 500ms로, `delivery.timeout.ms`를 10초로 줄인 것도 Broker 장애가
Demo Shop의 응답 시간으로 번지지 않게 하기 위해서이다.

대가는 분명하다. **Kafka 장애 구간의 Telemetry는 유실된다.** 관측 Event 하나를 지키려고 사용자의
주문을 실패시키지 않겠다는 선택이다.

### Redis — 짧은 수명의 실시간 상태

Rolling Metric은 60초 창의 값이다. 이 값은 60초 뒤에 의미가 없어진다.

| 용도 | TTL |
| --- | --- |
| Metric Bucket (counts, latency 표본) | 300초 |
| 계열 registry | 300초 |
| 중복 방지 Key | 900초 |

**"기술 스택에 Redis를 넣으려고" 쓴 캐시가 아니다.** 여기에는 두 가지 일이 있다.

1. **Rolling window 집계** — 10초 Bucket 6개를 더해 60초 창을 만든다. 매 Event마다 `HINCRBY`로 누적하고
   읽을 때 6개를 합친다.
2. **중복 반영 방지** — `SET NX EX`의 성공이 곧 "이 Event를 반영할 권리"이다.

두 일이 **하나의 Lua script 안에서 원자적으로** 일어난다. Redis는 script 실행 중 다른 Client의 명령을
끼워 넣지 않으므로, dedup 표시와 counter 증가 사이에 같은 Event의 다른 사본이 들어올 수 없다.

Redis는 **어떤 데이터의 유일한 저장소도 아니다.** 여기가 통째로 비어도 Incident 이력은 MySQL에 남는다.

### MySQL — 영구 보존이 목적인 데이터

Incident, AI 분석, GitHub 코드 맥락, Daily Report가 들어간다.

**Telemetry Event 원문은 저장하지 않는다.** 초당 수십 건의 관측 Event를 전부 RDB에 넣으면 그 자체가
부하가 되고, 대부분은 아무도 다시 보지 않는다. Incident를 연 창의 관측값(`trigger_*` 열)만 남긴다.

Demo Shop과 PulseOps는 **다른 database**를 쓴다.

## 3. 가장 중요한 설계 결정

### 3.1 endpoint는 Route Template이다

`/api/orders/123`이 아니라 `/api/orders/{orderId}`를 남긴다.

두 가지 이유가 있다.

- **보안** — 실제 resource ID가 관측 Event에 남지 않는다. Request Body, Authorization Header,
  Cookie도 마찬가지로 넣지 않는다.
- **집계 가능성** — ID를 그대로 쓰면 주문 하나마다 계열이 생겨, 어느 창에도 판정에 필요한 요청 수가
  모이지 않는다. 관측은 되지만 판단은 불가능해진다.

### 3.2 오류는 5xx뿐이다

4xx는 `requestCount`에만 들어가고 `errorCount`에는 들어가지 않는다.

**잘못된 요청이 많은 것은 서비스의 이상이 아니다.** 404가 쏟아지는 것은 대개 Client의 문제이고, 그것으로
서버 장애 Incident를 열면 알림이 신뢰를 잃는다.

이 결정의 결과가 LATENCY 시나리오에서 드러난다. 3.5초 지연을 주입해도 응답은 200이므로 `errorCount`는
0이고, `ERROR_RATE` Incident는 열리지 않는다. 열리는 것은 P95 때문인 `LATENCY` Incident 하나뿐이다.
**느린 것과 실패한 것은 다른 일이다.**

### 3.3 판정 대상은 "마지막으로 닫힌 창"이다

지금 채워지는 중인 Bucket으로 판정하지 않는다.

값이 아직 들어오는 중이면 같은 구간을 볼 때마다 다른 오류율이 나온다. 그 값으로 연속을 세면 **한
구간이 이상과 정상 사이를 오가며 여러 번 세어진다.**

### 3.4 연속 판정과 완충 구간

| | 열림 | 종료 |
| --- | --- | --- |
| 필요한 연속 | 2회 | **3회** |
| 기준선 (ERROR_RATE) | 10% / 25% | **3%** |
| 기준선 (LATENCY) | 1500ms / 3000ms | **500ms** |

**여는 기준과 닫는 기준을 일부러 다르게 두었다.**

- 두 기준이 같으면 값이 기준선에서 흔들릴 때마다 판정이 뒤집힌다(flapping). 그 사이를 `NEUTRAL`
  구간으로 남겨 두었다.
- 회복 확인을 더 길게(3회) 잡은 것은 **잘못 여는 것보다 잘못 닫는 것이 위험하기 때문**이다. 닫힌
  뒤에는 아무도 그 장애를 보지 않는다.

### 3.5 판정 상태를 DB에 둔다 (durable DetectionState)

"이상이 몇 번 연속됐는가"와 "어느 창까지 봤는가"를 JVM 메모리가 아니라 MySQL에 둔다.

**JVM 안의 기억은 재시작이나 Instance 추가로 사라진다.** DB의 상태는 남는다. 두 기준이 어긋나면 어느
쪽이 맞는지 말할 수 없게 된다.

이 cursor 하나가 **판정의 idempotency를 만든다.**

```java
if (!snapshot.windowEnd().isAfter(state.getLastEvaluatedWindowEnd())) {
    return;   // 이미 본 창
}
```

같은 Event가 at-least-once로 다시 와도, 이미 진행된 창은 다시 세어지지 않는다.

### 3.6 at-least-once + idempotency

Kafka Consumer는 exactly-once를 쓰지 않는다. **at-least-once를 전제하고 중복을 견디게 만들었다.**

| 계층 | 중복을 막는 방법 |
| --- | --- |
| Metric 집계 | Redis `SET NX` dedup (TTL 900초) |
| Incident 판정 | `DetectionState`의 cursor |

exactly-once semantics를 Kafka Transaction으로 구현하는 대신 이 길을 택한 이유는, **어차피 Producer가
fail-open이라 end-to-end exactly-once가 성립할 수 없기 때문**이다. 성립하지 않는 보장을 위해 복잡도를
치르지 않는다.

### 3.7 Retry와 DLT

| 항목 | 값 |
| --- | --- |
| 재시도 간격 | 500ms |
| 총 처리 시도 | 3회 (최초 1 + 재시도 2) |
| 실패 격리 | `demo-shop.telemetry.api.v1.dlt` |

**계약 위반은 재시도하지 않는다.** `TelemetryEventContractException`은
`addNotRetryableExceptions`에 등록되어 한 번에 DLT로 간다. 다시 읽어도 같은 결과인 record를 재시도하는
것은 시간만 쓴다.

**DLT를 자동 재처리하는 Consumer는 두지 않았다.** 그것을 두면 계약을 어긴 record가 무한히 도는 경로가
생긴다. DLT는 사람이 확인할 때까지 붙잡아 두는 자리이다.

또 하나 — Consumer는 `success`가 `httpStatus`와 어긋나는 Event를 **고치지 않고 거절한다.** 계약을 깬
것은 Producer이고, Consumer가 조용히 고치면 계약이 깨진 사실이 아무 데도 남지 않는다.

### 3.8 SSE — 알림은 "다시 읽어라"만 말한다

```
event:incident-changed
data:{"incidentId":1,"occurredAt":"..."}
```

**알림에 Incident의 상태를 싣지 않는다.** ID와 시각만 보내고 화면은 REST로 다시 읽는다.

상태를 실으면 화면이 REST 응답과 알림 중 **어느 쪽을 믿을지 정해야 한다.** 그 순간 두 개의 진실이
생긴다. Backend Response가 언제나 최종 상태의 기준이다.

알림은 Transaction commit **이후에** 나간다. 되돌아간 Transaction의 변경이 화면에 먼저 보이지 않는다.
`IncidentDetector`는 "변경이 있었다"는 사실만 Transaction 안에서 발행하고, 그것을 언제 어디로 내보낼지는
`IncidentChangedEventListener`가 정한다. 판정이 전달 수단을 알면, Client가 하나도 붙어 있지 않은
새벽의 판정까지 그 수단의 사정에 묶인다.

### 3.9 AI는 탐지 경로에 없다

**Incident를 여는 것도 끝내는 것도 규칙과 숫자뿐이다.**

Claude를 부르는 자리는 사람이 요청하는 REST endpoint 둘뿐이다. Incident 발생, Scheduler, SSE, Consumer
어디에서도 AI를 부르지 않는다.

결과도 자유 서술이 아니라 구조를 갖는다. `primaryHypothesis`(원인이 아니라 **가설**), `confidence`,
`evidence`, 그리고 **`limitations`**. 분석이 스스로 무엇을 모르는지 말하게 한다.

`model`과 `promptVersion`과 `inputSnapshot`을 함께 저장하는 이유는, 나중에 그 결과를 다시 봤을 때
무엇을 주고 무엇으로 받았는지 재구성할 수 있어야 하기 때문이다.

기본값은 꺼짐이다. 이 Application이 **요금이 발생하는 요청**을 보내는 유일한 자리이기 때문이다.

### 3.10 Actuator를 둘로 좁혔다

```properties
management.endpoints.web.exposure.include=health,prometheus
```

이 Application에는 인증 계층이 없다. **여는 것이 곧 공개하는 것이다.** 편하다는 이유로 `*`를 적으면
`env`와 `configprops`가 함께 열려 Claude API key와 GitHub token이 인증 없는 HTTP 응답으로 나간다.

### 3.11 Consumer Lag은 읽기 전용이다

Lag은 **Incident 판정의 입력이 아니다.** 커져도 Incident가 열리지 않는다.

그리고 Prometheus Gauge는 Kafka를 직접 부르지 않는다. 마지막으로 읽어 둔 표본만 내보내고, 표본이 오래되면
`pulseops_kafka_consumer_lag_snapshot_age_seconds`로 그 사실이 드러난다. **scrape가 외부 시스템을
기다리게 하지 않기 위해서이다.**

## 4. 어려웠던 문제와 해결

### 4.1 SSE 연결이 조용히 죽는 문제

**문제** — 브라우저와 Backend 사이에 nginx가 있으면 SSE 응답이 버퍼링되어 알림이 늦게 도착하고, nginx의
읽기 대기 시간이 짧으면 멀쩡한 연결을 nginx가 먼저 끊는다.

**해결** — `pulseops/frontend/nginx.conf`에 SSE 경로만 따로 두어 buffering을 끄고 대기 시간을 Backend가
정한 수명보다 길게 잡았다. 그리고 Backend가 20초마다 `heartbeat`를 보내 연결이 살아 있는지 양쪽이 알 수
있게 했다.

*근거: commit `f0539f9` "fix: SSE 연결 생존 감지와 자동 복구 구현"*

### 4.2 같은 계열에 Incident가 둘 생길 수 있는 문제

**문제** — 같은 계열을 처음 보는 두 처리가 나란히 "처리 중인 Incident 없음"을 확인하고 각자 만들 수 있다.

**해결** — Application의 확인에 기대지 않고 **DB 제약**으로 막았다. MySQL에는 조건부 UNIQUE Index가
없으므로, 처리 중일 때만 값이 생기고 `RESOLVED`면 `NULL`이 되는 generated column을 만들어 UNIQUE를
걸었다.

```sql
active_key VARCHAR(350) GENERATED ALWAYS AS (
    CASE WHEN status IN ('OPEN','ACKNOWLEDGED')
         THEN CONCAT(service,'|',method,'|',endpoint,'|',type) END
) STORED,
CONSTRAINT uk_incident_active UNIQUE (active_key)
```

UNIQUE Index는 NULL을 서로 다른 값으로 보므로 **종료된 Incident는 이력으로 얼마든지 쌓인다.**

Application이 이 값을 직접 채우지 않고 파생시킨 이유는, 직접 채우면 identity나 status를 바꿀 때 두 값이
어긋날 수 있고 **그 어긋남이 곧 중복 Incident**이기 때문이다.

*근거: `V1__create_incident_table.sql`, commit `318c1a3` "feat: Incident ACK와 동시성 안전성 구현"*

### 4.3 교착(deadlock) 가능성

**문제** — 판정은 `DetectionState`와 `Incident` 두 행을 잠근다. 두 처리가 서로 반대 순서로 잠그면 서로를
기다리다 둘 다 멈춘다.

**해결** — 잠금 순서를 **계약으로 고정**했다.

1. `IncidentType` 선언 순서대로 (`ERROR_RATE` → `LATENCY`)
2. 종류 안에서는 `DetectionState` → `Incident`

그리고 `Incident` 조회를 `lockActive()` 한 곳으로 모아, 여는 경로와 끝내는 경로가 서로 다른 순서로
잠글 수 없게 만들었다.

### 4.4 Redis 지연이 MySQL 잠금을 붙드는 문제

**문제** — 판정에는 Redis 읽기(Metric Snapshot)와 MySQL 쓰기(상태 갱신)가 모두 필요하다. Redis 읽기를
Transaction 안에 넣으면 Redis가 느릴 때 **MySQL 행 잠금이 그 시간만큼 함께 붙들린다.**

**해결** — Redis Snapshot을 먼저 읽고 그 다음에 Transaction을 연다.

```java
public void detect(MetricSeries series, Instant evaluationNow) {
    MetricSnapshot snapshot = rollingMetricService.snapshot(series, lastClosedWindowReference(evaluationNow));
    transactionTemplate.executeWithoutResult(status -> evaluate(series, snapshot));
}
```

Redis timeout도 1초로 줄였다. Metric 처리는 Kafka Consumer Thread 위에서 일어나므로, Redis가 응답하지
않을 때 Thread가 오래 묶이면 **Consumer가 poll 주기를 넘겨 재할당을 반복한다.**

### 4.5 창 경계를 흉내 내려다 생긴 문제

**문제** — "마지막으로 닫힌 창"을 얻으려면 기준 시각을 어떻게 정해야 하는가. 현재 Bucket 시작에서
1나노초를 빼는 방식은 **어느 Bucket에도 온전히 속하지 않는 값**을 만든다. 경계 규칙이 조금만 바뀌어도
다른 창을 가리킨다.

**해결** — Bucket 하나만큼 명확히 뒤로 물러난다.

```java
return MetricWindow.bucketStartOf(evaluationNow).minusSeconds(MetricWindow.BUCKET_SECONDS);
```

### 4.6 Latency 표본이 Bucket 처음 값에 고정되는 문제

**문제** — Bucket당 표본을 500개로 제한하는데, 앞에서부터 남기면 **Bucket이 처음 채워진 순간의 표본이
그대로 굳어** 이후 요청이 P95에 전혀 반영되지 않는다.

**해결** — 뒤에 넣고 뒤에서부터 남긴다.

```lua
redis.call('RPUSH', KEYS[3], latencyMs)
redis.call('LTRIM', KEYS[3], -sampleCap, -1)
```

### 4.7 늦게 도착한 Event가 계열을 살아 있게 만드는 문제

**문제** — 계열 registry(ZSET)의 score에 벽시계를 쓰면, 늦게 도착한 Event가 그 계열을 실제보다 최근인
것으로 만든다. **이미 조용해진 계열이 계속 살아 있는 것처럼 보인다.**

**해결** — score에 **Event가 속한 Bucket의 시작**을 쓴다.

```lua
redis.call('ZADD', KEYS[4], bucketStartEpochSecond, seriesMember)
```

### 4.8 Testcontainers 자원 경합

**문제** — 두 Backend의 Integration Test가 각각 MySQL, Redis, Kafka를 Testcontainers로 띄운다. 한
runner에서 몰아 돌리면 Container가 겹쳐 **memory 때문에 실패하고, 그 실패는 코드의 문제가 아니다.**

**해결** — CI에서 두 project를 matrix로 나눠 **다른 runner**에서 돌린다. `fail-fast: false`로 한쪽이
실패해도 다른 쪽을 끝까지 돌린다.

*근거: `.github/workflows/ci.yml`*

## 5. 검증

### 자동화된 테스트

| 대상 | 결과 |
| --- | --- |
| PulseOps Backend | **820 tests** / 78 classes — 실패 0 |
| Demo Shop Backend | **162 tests** / 17 classes — 실패 0 |
| 두 Frontend | `npm ci` + `oxlint` + `vite build` 통과 |

*(2026-09-08 로컬 실행 기준. `cleanTest test`로 강제 실행한 결과이다.)*

Business Logic과 Incident Detection Logic은 Controller에 두지 않았다. `IncidentRule`은 시각도 저장소도
알지 못하는 순수 계산이라 Spring Bean이 아니고 정적 method만 갖는다. 덕분에 기준선 검증이 Unit Test로
끝난다.

Kafka, Redis, MySQL이 함께 얽히는 동작은 Testcontainers Integration Test로 확인한다.

### k6 부하 검증

로컬 Docker Compose 환경에서 네 시나리오를 실행했다. 자세한 조건과 수치는
[performance.md](performance.md)에 있다.

| 시나리오 | 결과 |
| --- | --- |
| Baseline | 2,087 requests / 18.26 req/s, p95 9.33ms, 실패 0건, **Incident 미발생** |
| Error Incident | 1,801 requests, 503 100%, **ERROR_RATE CRITICAL** 약 20초 만에 개설 |
| Recovery | 2,087 requests, `OPEN → ACKNOWLEDGED → RESOLVED` 전이 확인 |
| Latency Incident | 541 requests, p95 3.51s, `errorCount` 0, **LATENCY CRITICAL만** 개설 |

전 구간에서 Kafka record 8,608건과 Redis 반영 8,608건이 일치했고, 중복·실패·거절·창 이탈이 모두 0이었다.

### 관측 검증

- Prometheus target UP, PulseOps custom metric 10종 노출 확인
- Grafana Datasource health OK, Dashboard 20 panel provisioning 확인
- Dashboard의 모든 PromQL이 데이터를 반환하는 것을 직접 질의로 확인

### Docker / CI

- `docker compose config --quiet` 통과
- 9개 service 전부 healthy, `kafka-init` `exited (0)`
- GitHub Actions: backend(matrix 2) / frontend(matrix 2) / docker

## 6. 한계

**정직하게 밝히는 것이 이 프로젝트의 일부다.**

### 전달 보장

- **end-to-end exactly-once가 아니다.** Producer가 fail-open이므로 Kafka 장애 구간의 Telemetry는
  유실된다.
- **guaranteed delivery가 아니다.** 위와 같은 이유이다.
- dedup 창은 900초이다. 그보다 늦게 다시 온 Event는 dedup이 막지 못한다(대개 `OUT_OF_WINDOW`로
  걸러진다).

### 성능 수치

- 측정값은 **로컬 Docker Compose 환경의 관측치**이다. 최대 처리량도, 운영 용량도, SLA도 아니다.
- 부하 속도를 미리 정해 보냈으므로 **포화 지점을 재는 시험이 아니었다.**
- 계열이 하나뿐이어서 Kafka partition 3개 중 1개만 사용되었다. **partition 병렬 처리는 검증되지
  않았다.**
- Consumer Lag이 늘어난 적이 없어 **회복 곡선을 관측하지 못했다.**

### 운영 관점

- **production ready가 아니다.** 인증/인가 계층이 없고, credential이 기본값에 그대로 있다.
- 모든 구성 요소가 **Instance 하나**이다. Kafka Broker 1개, replication factor 1.
- 자원 한도, 재시작 정책, backup, 이중화 어느 것도 정해져 있지 않다.
- 배포(CD)가 없다. CI는 build와 test만 한다.

### 탐지 관점

- **트래픽이 멈추면 Incident가 저절로 끝나지 않는다.** 요청 20건 미만인 창은 판정하지 않는다
  (`INCONCLUSIVE`). 조용해진 것과 나아진 것은 다르기 때문에 내린 선택이지만, 트래픽이 적은 서비스에는
  맞지 않는다.
- 기준선이 **고정값**이다. 서비스마다 정상 범위가 다른데 하나의 기준을 쓴다.
- Demo Shop의 내부 지표(JVM, CPU)를 관측하지 않는다. Prometheus는 PulseOps만 긁는다.

### AI 관점

- AI 결과는 **가설이지 원인이 아니다.**
- GitHub 코드 맥락은 파일 3개, commit 5개의 **부분적 근거**이고, **지금의 기본 branch**를 읽으므로
  Incident 당시의 코드와 다를 수 있다.

## 7. 향후 개선 가능성

**아래는 전부 구현되지 않았다.** 지금 없는 것을 있는 것처럼 쓰지 않기 위해 별도 절로 둔다.

| 개선안 | 지금의 한계 | 왜 지금 안 했는가 |
| --- | --- | --- |
| 계열별 동적 기준선 | 기준선이 고정값이다 | 정상 범위를 학습할 데이터가 아직 없다 |
| 여러 endpoint 부하 시험 | partition 1개만 사용됨 | 검증 범위를 넘어선다 |
| Consumer 재시작 중 중복 실험 | dedup의 실전 검증이 없다 | 시나리오 설계가 필요하다 |
| Demo Shop scrape 추가 | Demo Shop 내부 지표가 없다 | Prometheus 대상 확장은 Phase 범위 밖이다 |
| Alertmanager 연동 | 알림이 화면에만 있다 | 새 인프라 도입이다 |
| Kafka replication 확대 | Broker 1개 | 로컬 데모 구성의 전제를 바꾼다 |
| 인증/인가 | 없음 | 이 프로젝트의 학습 목표가 아니다 |
| CD / 배포 | CI만 있다 | 배포 대상 환경이 없다 |

## 8. 한 문장으로

> **PulseOps는 이벤트 기반 서비스 모니터링 및 Incident 관리 플랫폼이다.**
> 관측이 서비스를 방해하지 않도록 Kafka로 경계를 긋고, 짧은 수명의 실시간 상태는 Redis에, 영구
> 보존이 필요한 판단의 결과는 MySQL에 두었다. 무엇을 장애로 볼 것인지는 규칙과 숫자로만 정하며,
> AI는 그 판단 뒤에서 사람의 원인 분석을 돕는 자리에만 있다.
