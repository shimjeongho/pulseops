# Demo Guide

이 문서는 repository를 clone한 사람이 처음부터 끝까지 따라 하면 PulseOps가 무엇을 하는지 직접 보게
되는 절차이다.

전체 소요 시간은 약 20분이고, 그중 대부분은 첫 image build를 기다리는 시간이다.

> **주의**: 이 절차는 로컬 데모 구성이다. 운영 배포 절차가 아니다.

## 1. Prerequisite

| 필요한 것 | 확인 |
| --- | --- |
| Docker Engine + Docker Compose v2 이상 | `docker compose version` |
| Host port 5173, 5174, 8080, 8081, 9090, 3000이 비어 있을 것 | |
| Docker에 memory 넉넉히 할당 (JVM 2개 + Kafka Broker를 함께 띄운다) | `docker info` |

JDK와 Node.js는 **Host에 없어도 된다.** Container 안에서 build한다.

MySQL, Redis, Kafka는 Host에 열리지 않는다. Compose network 안에서만 쓰이므로 Host에 이미 떠 있는
다른 MySQL이나 Redis와 부딪히지 않는다.

`k6`는 선택이다. 없으면 §6에서 브라우저나 `curl`로 대신할 수 있고, 공식 Container image로도 실행할 수
있다.

## 2. Clone

```bash
git clone <repository-url>
cd pulseops
```

## 3. Environment

**기본 실행에는 아무 설정도 필요 없다.** `.env` 없이 그대로 뜬다.

바꿀 것이 있을 때만 만든다.

```bash
cp .env.example .env
```

`.env`는 repository에 들어가지 않는다(`.gitignore`). 실제 API key와 token이 들어갈 자리이기 때문이다.

**이 문서와 `.env.example` 어디에도 실제 secret을 적지 않는다.**

## 4. Docker Compose Start

```bash
docker compose up -d --build
```

첫 실행은 image build와 dependency 내려받기 때문에 몇 분이 걸린다. 두 번째부터는 훨씬 빠르다.

상태를 본다.

```bash
docker compose ps
```

아래가 전부 `healthy`가 되면 준비가 끝난 것이다.

```
mysql, redis, kafka, demo-shop-backend, demo-shop-frontend,
pulseops-backend, pulseops-frontend, prometheus, grafana
```

`kafka-init`은 Topic을 만들고 끝나는 service라 `exited (0)`이 정상이다.

```bash
docker compose logs kafka-init
```

```
Created topic demo-shop.telemetry.api.v1.
Created topic demo-shop.telemetry.api.v1.dlt.
```

기동 순서는 `depends_on`의 선언 순서가 아니라 healthcheck로 정해진다. MySQL이 연결을 받고, Kafka
Broker가 실제로 요청에 답하고, Topic이 만들어진 뒤에야 Application이 뜬다. 기다리면 된다.

## 5. Service URLs

| 주소 | 무엇 |
| --- | --- |
| http://localhost:5173 | **Demo Shop 화면** — 상품, 장바구니, 주문, 결제 |
| http://localhost:5174 | **PulseOps Dashboard** — 실시간 Metric, Incident, Consumer Lag |
| http://localhost:8080/api/products | Demo Shop Backend API |
| http://localhost:8081/api/metrics | PulseOps Metric API |
| http://localhost:8081/api/incidents/active | 처리 중인 Incident |
| http://localhost:8081/api/incidents/stream | Incident SSE |
| http://localhost:8081/api/kafka/consumer-lag | Consumer Lag |
| http://localhost:8081/api/reports/daily | Daily Incident Report |
| http://localhost:8081/actuator/health | PulseOps 상태 |
| http://localhost:9090 | Prometheus |
| http://localhost:3001 | Grafana (로그인 없이 조회 가능) |

## 6. Normal Traffic

**브라우저로** — http://localhost:5173 을 열고 상품 목록과 상세를 몇 번 오간다.

**또는 `curl`로**

```bash
for i in $(seq 1 30); do curl -s -o /dev/null http://localhost:8080/api/products; done
```

**또는 k6로** (설치했다면)

```bash
k6 run load-test/k6/baseline.js
```

k6를 설치하지 않았다면 공식 image로도 된다. Compose network 안에서 service 이름을 부르므로
`ALLOW_LOAD_TEST=true`가 필요하다.

```bash
docker run --rm --network pulseops_pulseops \
  -e DEMO_SHOP_BASE_URL=http://demo-shop-backend:8080 \
  -e ALLOW_LOAD_TEST=true \
  -v "$(pwd)/load-test/k6:/scripts:ro" \
  grafana/k6:1.4.0 run /scripts/baseline.js
```

network 이름은 Compose project 이름을 따른다. `docker network ls`로 확인한다.

## 7. Metrics

http://localhost:5174 를 열면 방금 보낸 요청이 Metric에 나타난다.

```bash
curl -s http://localhost:8081/api/metrics
```

```json
{
  "generatedAt": "...",
  "items": [
    {
      "service": "demo-shop", "method": "GET", "endpoint": "/api/products",
      "windowStart": "...", "windowEnd": "...",
      "requestCount": 1052, "errorCount": 0, "errorRate": 0.00, "p95LatencyMs": 8
    }
  ]
}
```

**여기까지가 관측 경로 전체이다.**

```
Demo Shop HTTP → TelemetryFilter → Kafka → PulseOps Consumer → Redis Metric
```

창은 60초이고 Bucket은 10초이므로, 요청을 멈추면 약 1분에 걸쳐 `requestCount`가 0으로 줄어든다.

`endpoint`가 `/api/products`인 것에 주목한다. 실제 URI가 아니라 Route Template이다.

## 8. Error Fault Injection

**장애 주입은 기본이 꺼짐이다.** 켜지 않으면 아래 Header는 아무 의미 없는 평범한 Header이다.

먼저 확인해 본다.

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Demo-Fault: ERROR_503" http://localhost:8080/api/products
# 200  <- 꺼져 있으므로 정상 응답
```

켠다. `.env`에 아래를 넣고 Demo Shop Backend만 다시 올린다.

```
DEMO_SHOP_FAULT_INJECTION_ENABLED=true
```

```bash
docker compose up -d --no-deps demo-shop-backend
```

이제 같은 요청이 503을 반환한다.

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Demo-Fault: ERROR_503" http://localhost:8080/api/products
# 503
```

## 9. ERROR_RATE Incident

부하를 보낸다.

```bash
k6 run load-test/k6/error-incident.js
```

또는 Demo Shop 화면의 개발용 Fault Lab에서 오류 트래픽을 보낼 수도 있다.

`curl`만으로 하려면 최소 트래픽 20건을 60초 창 안에 채워야 한다.

```bash
for i in $(seq 1 100); do
  curl -s -o /dev/null -H "X-Demo-Fault: ERROR_503" http://localhost:8080/api/products
done
```

**약 20초 뒤** Incident가 열린다.

```bash
curl -s http://localhost:8081/api/incidents/active
```

```json
[{
  "id": 1, "service": "demo-shop", "method": "GET", "endpoint": "/api/products",
  "type": "ERROR_RATE", "severity": "CRITICAL", "status": "OPEN",
  "openedAt": "...",
  "triggerRequestCount": 316, "triggerErrorCount": 314,
  "triggerErrorRate": 99.37, "triggerP95LatencyMs": 2
}]
```

http://localhost:5174 를 열어 두었다면 **화면이 새로고침 없이 바뀐다.** SSE 때문이다.

직접 SSE를 보려면:

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8081/api/incidents/stream
```

```
event:connected
data:{"connectedAt":"..."}

event:incident-changed
data:{"incidentId":1,"occurredAt":"..."}

event:heartbeat
data:{"sentAt":"..."}
```

`heartbeat`는 20초마다 온다.

**왜 20초가 걸리는가** — 이상인 창이 2회 연속되어야 열리고, 창은 10초마다 진행한다. 자세한 규칙은
[incident-detection.md](incident-detection.md)에 있다.

## 10. ACK — 확인 처리

```bash
curl -s -X POST http://localhost:8081/api/incidents/1/acknowledge
```

`status`가 `OPEN`에서 `ACKNOWLEDGED`로 바뀌고 `acknowledgedAt`이 채워진다. SSE로도 알림이 온다.

Dashboard의 Incident 상세 화면에서 버튼으로도 할 수 있다.

확인 처리는 `OPEN`일 때만 가능하다. 이미 `ACKNOWLEDGED`이거나 `RESOLVED`이면 거절된다.

## 11. Recovery

**장애 주입을 멈추고 정상 요청을 다시 보낸다.**

```bash
k6 run load-test/k6/baseline.js
```

또는 `X-Demo-Fault` Header 없이 요청을 보낸다.

```bash
for i in $(seq 1 200); do curl -s -o /dev/null http://localhost:8080/api/products; done
```

**약 30초 뒤** Incident가 `RESOLVED`가 된다.

```bash
curl -s http://localhost:8081/api/incidents/1
```

```json
{ "id": 1, "status": "RESOLVED", "resolvedAt": "...", ... }
```

`ACKNOWLEDGED`에서 곧바로 `RESOLVED`로 간다. 회복은 서비스의 사실이고 확인 여부는 사람의 일이므로,
Detector는 사람이 확인했는지 보지 않는다.

> **중요**: 요청을 그냥 멈추면 Incident는 끝나지 않는다. 요청이 20건 미만인 창은 판정 자체를 하지
> 않기 때문이다(`INCONCLUSIVE`). 조용해진 것과 나아진 것은 다르다. **정상 요청이 다시 흘러야** 끝난다.

`RESOLVED`가 된 Incident는 다시 `OPEN`이 되지 않는다. 같은 문제가 재발하면 **새 Incident**가 생긴다.

## 12. Latency Fault Injection

같은 스위치를 쓴다. 이미 §8에서 켰다면 그대로 둔다.

```bash
curl -s -o /dev/null -w "status=%{http_code} time=%{time_total}s\n" \
  -H "X-Demo-Fault: LATENCY_3500" http://localhost:8080/api/products
# status=200 time=3.607852s
```

**응답이 200인 것에 주목한다.** 느린 것은 실패가 아니다.

## 13. LATENCY Incident

```bash
k6 run load-test/k6/latency-incident.js
```

이 시나리오는 초당 6건만 보낸다. 요청 하나가 3.5초 동안 Servlet Thread를 붙들기 때문이다.

**약 20초 뒤** Incident가 열린다.

```bash
curl -s http://localhost:8081/api/incidents/active
```

```json
[{
  "id": 2, "type": "LATENCY", "severity": "CRITICAL", "status": "OPEN",
  "triggerRequestCount": 96,
  "triggerErrorCount": 0,
  "triggerErrorRate": 0.00,
  "triggerP95LatencyMs": 3508
}]
```

**이것이 이 데모의 핵심 장면이다.**

- `triggerErrorCount`가 **0**이다.
- `triggerErrorRate`가 **0.00%**이다.
- 그런데도 Incident가 열렸다. **P95 3,508ms 하나 때문이다.**
- `ERROR_RATE` Incident는 열리지 않았다.

PulseOps의 오류율은 5xx만 센다. 그래서 지연 하나가 두 종류의 Incident를 동시에 만들지 않는다. 두
종류는 완전히 독립된 Incident이다.

`/api/metrics`로도 같은 것을 볼 수 있다.

```bash
curl -s http://localhost:8081/api/metrics
# "errorCount": 0, "errorRate": 0.00, "p95LatencyMs": 3508
```

## 14. Consumer Lag

```bash
curl -s http://localhost:8081/api/kafka/consumer-lag
```

```json
{
  "topic": "demo-shop.telemetry.api.v1",
  "consumerGroup": "pulseops-metric-consumer-v1",
  "partitionCount": 3,
  "uninitializedPartitionCount": 2,
  "totalLag": 0,
  "partitions": [
    { "partition": 0, "logEndOffset": 8608, "committedOffset": 8608, "lag": 0, "state": "INITIALIZED" },
    { "partition": 1, "logEndOffset": 0, "committedOffset": null, "lag": null, "state": "UNINITIALIZED" },
    { "partition": 2, "logEndOffset": 0, "committedOffset": null, "lag": null, "state": "UNINITIALIZED" }
  ]
}
```

Dashboard의 Consumer Lag panel에서도 같은 값을 본다.

**partition 1과 2가 `UNINITIALIZED`인 것은 정상이다.** Producer의 message key가
`service:endpoint`이고 이 데모는 endpoint 하나만 때렸으므로, 모든 Event가 같은 partition으로 갔다.
같은 계열의 Event가 순서대로 처리되려면 한 partition에 모여야 한다.

**Consumer Lag은 Incident 판정의 입력이 아니다.** lag이 커져도 Incident가 열리지 않는다. 사람이 보기
위한 운영 지표이다.

## 15. Prometheus

http://localhost:9090 을 연다.

대상이 UP인지 확인한다.

```bash
curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
# "health":"up"
```

Prometheus 화면에서 아래를 질의해 본다.

```promql
pulseops_incidents_active
pulseops_incidents_opened_total
pulseops_incidents_resolved_total
pulseops_telemetry_events_processed_total
pulseops_kafka_consumer_lag
pulseops_kafka_consumer_partitions_uninitialized
```

`pulseops_telemetry_events_processed_total`의 `result` label은 `applied`, `duplicate`,
`out_of_window` 셋이다. `duplicate`가 0보다 크다면 Kafka의 at-least-once 재전달이 실제로 일어났고
Redis dedup이 그것을 막았다는 뜻이다.

> Prometheus는 **PulseOps Backend만** 긁는다. Demo Shop은 scrape 대상이 아니므로,
> `http_server_requests_*`는 PulseOps 자신의 REST API 값이지 Demo Shop의 응답 시간이 아니다.

## 16. Grafana

http://localhost:3001 을 연다. 로그인 없이 볼 수 있다.

**Dashboards → PulseOps 폴더 → PulseOps Overview**

행 구성은 다음과 같다.

| Row | 내용 |
| --- | --- |
| HTTP — PulseOps API | 요청 속도, 5xx 비율, P95 |
| Incident | 진행 중 / 열림 / 종료 |
| Kafka Consumer Lag | 합계, partition별, 미초기화 수, 표본 나이 |
| Telemetry Processing | 처리 결과별 속도, 실패와 거절 |
| JVM / Runtime | Heap, CPU, Thread |

시간 범위를 방금 데모한 구간(예: Last 30 minutes)으로 맞추면 Incident가 열리고 닫힌 자국이 보인다.

Datasource와 Dashboard는 UI가 아니라 `monitoring/grafana` 아래 파일에서 온다. UI에서 고친 값은 다시
띄우면 파일의 내용으로 돌아간다.

## 17. Claude 분석 (선택 — credential 필요)

> **이 단계는 건너뛰어도 된다.** 지금까지의 모든 기능은 이것 없이 동작했다.
> 이 단계는 Anthropic API key가 필요하고 **요금이 발생한다.**

`.env`에 셋을 모두 채운다.

```
PULSEOPS_AI_CLAUDE_ENABLED=true
ANTHROPIC_API_KEY=<your key>
CLAUDE_MODEL=<model id>
```

```bash
docker compose up -d --no-deps pulseops-backend
```

Incident 하나를 골라 분석을 요청한다.

```bash
curl -s -X POST http://localhost:8081/api/incidents/1/analysis
```

Dashboard의 Incident 상세 화면에도 버튼이 있다.

결과는 `summary`, `primaryHypothesis`, `confidence`, `evidence`, `recommendedActions`,
`limitations`를 갖는다.

**결과는 확정된 원인이 아니다.** 화면에서도 **Suggested Analysis**로 표시된다. 분석 결과는 Incident의
상태도 심각도도 바꾸지 못한다.

켜지 않았다면 이렇게 응답한다.

```json
{ "status": 503, "code": "AI_ANALYSIS_UNAVAILABLE", "message": "AI 분석 서비스를 현재 사용할 수 없습니다." }
```

## 18. GitHub 코드 맥락 (선택 — credential 필요)

> **이 단계도 건너뛰어도 된다.** GitHub token이 필요하다.

읽기 권한만 있으면 된다. 이 기능은 commit도 branch도 PR도 만들지 않는다.

```
PULSEOPS_GITHUB_ENABLED=true
GITHUB_TOKEN=<read-only token>
GITHUB_OWNER=<owner>
GITHUB_REPOSITORY=<repo>
```

```bash
docker compose up -d --no-deps pulseops-backend
```

맥락을 수집한다.

```bash
curl -s -X POST http://localhost:8081/api/incidents/1/github-context
```

소스 파일 최대 3개와 commit 최대 5개를 모은다. `pulseops.github.source-root` 밖의 파일은 어떤 검색
결과로 나와도 선택하지 않는다.

수집한 맥락으로 다시 분석한다.

```bash
curl -s -X POST http://localhost:8081/api/incidents/1/analysis/github
```

**GitHub 맥락은 원인을 확정하지 않는다.** 부분적 근거이고, 지금의 기본 branch를 읽으므로 Incident
당시의 코드와 다를 수 있다. 자세한 한계는 [ai-github-boundary.md](ai-github-boundary.md) §4에 있다.

## 19. Stop

```bash
docker compose down
```

Container와 network를 내린다. **데이터는 남는다.** 다시 `docker compose up -d`로 올리면 Incident 이력,
Kafka offset, Prometheus 시계열이 그대로 이어진다.

## 20. Persistent Volume 경고

> ### `docker compose down -v`를 습관처럼 붙이지 않는다.
>
> `-v`는 아래 volume을 **전부 삭제하며 되돌릴 수 없다.**

| Volume | 잃는 것 |
| --- | --- |
| `pulseops_mysql-data` | Incident 이력, AI 분석, GitHub 코드 맥락, Daily Report |
| `pulseops_kafka-data` | KRaft metadata, Topic log, **Consumer group offset** |
| `pulseops_redis-data` | Rolling Metric, **중복 방지 Key** |
| `pulseops_prometheus-data` | 수집한 시계열 |
| `pulseops_grafana-data` | Grafana 내부 database |

`kafka-data`를 지우면 PulseOps가 Topic을 처음부터 다시 읽어 **이미 집계한 Event를 다시 집계한다.**

`redis-data`를 지우면 중복 방지 Key가 사라진다. Consumer는 at-least-once라 재시작 직후 같은 Event를
다시 받을 수 있고, 그때 Key가 없으면 그 Event가 두 번 집계된다.

깨끗한 상태로 다시 시작하고 싶다면 **기존 volume을 지우는 대신 다른 Compose project 이름을 쓴다.**

```bash
docker compose -p pulseops-demo2 up -d --build
```

새 project는 자기 이름의 volume을 새로 만들며 기존 것을 건드리지 않는다.

## 부록: 문제가 생기면

[troubleshooting.md](troubleshooting.md)를 본다.

**절대 하지 말 것**

```bash
docker system prune      # 다른 project의 image와 container까지 지운다
docker volume prune      # 위 volume들이 사라진다
taskkill /IM java.exe    # 사용자의 다른 Java process까지 죽인다
```
