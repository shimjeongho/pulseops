# Monitoring

PulseOps가 내보내는 metric을 Prometheus로 모으고 Grafana로 보는 로컬 구성이다.

```
PulseOps Backend :8081
  → GET /actuator/prometheus
  → Prometheus :9090
  → Grafana :3001
```

Prometheus와 Grafana는 검증 도구이지 Application의 일부가 아니다. Backend Gradle dependency도 Frontend
npm dependency도 아니며, 이 폴더가 없어도 두 Application은 그대로 빌드되고 실행된다.

이 폴더에는 설정 파일만 있다. 두 container를 실제로 띄우는 것은 repository 최상위의
`docker-compose.yml`이며, 여기 있는 파일들은 그 container 안으로 그대로 mount된다. 설정의 근거는
언제나 이 폴더의 파일이다.

## 무엇이 노출되는가

PulseOps Backend는 Actuator에서 두 endpoint만 연다.

| Endpoint | 용도 |
| --- | --- |
| `GET /actuator/health` | Prometheus가 target의 생사를 판단한다 |
| `GET /actuator/prometheus` | metric scrape |

`env`, `configprops`, `beans`, `metrics` 같은 나머지는 열지 않는다. 이 Application에는 인증 계층이 없어
Actuator에서 여는 것은 곧 공개하는 것이고, 앞의 둘은 Claude API key와 GitHub token이 담긴 설정을 그대로
보여 준다. 무엇이 열려 있는지는 `PrometheusEndpointIntegrationTest`가 고정한다.

## Custom Metric

| Metric | Type | Tag | 의미 |
| --- | --- | --- | --- |
| `pulseops_incidents_active` | Gauge | `type`, `severity` | 지금 OPEN 또는 ACKNOWLEDGED인 Incident 수. 근거는 MySQL |
| `pulseops_incidents_opened_total` | Counter | `type`, `severity` | 이 Instance가 떠 있는 동안 열린 Incident 수 |
| `pulseops_incidents_resolved_total` | Counter | `type` | 이 Instance가 떠 있는 동안 끝난 Incident 수 |
| `pulseops_kafka_consumer_lag` | Gauge | 없음 | 아직 읽지 않은 Record 수의 합 |
| `pulseops_kafka_consumer_partition_lag` | Gauge | `partition` | partition별 밀린 Record 수 |
| `pulseops_kafka_consumer_partitions_uninitialized` | Gauge | 없음 | commit 기록이 없어 lag을 계산할 수 없는 partition 수 |
| `pulseops_kafka_consumer_lag_snapshot_age_seconds` | Gauge | 없음 | 위 세 값을 Kafka에서 읽은 지 지난 시간 |
| `pulseops_telemetry_events_processed_total` | Counter | `result` | 처리를 마친 Telemetry Event 수 |
| `pulseops_telemetry_events_failed_total` | Counter | 없음 | 처리 도중 예외가 나 재시도로 넘어간 Event 수 |
| `pulseops_telemetry_events_rejected_total` | Counter | 없음 | 계약을 어겨 재시도 없이 DLT로 간 Event 수 |

HTTP와 JVM은 Micrometer가 기본으로 주는 것을 그대로 쓴다. 같은 뜻의 metric을 다시 만들지 않는다.

Tag에는 `requestId`, `eventId`, `serviceInstance`, raw URI, error message, Incident ID를 쓰지 않는다. 그
값들은 요청마다 달라져 시계열 수가 트래픽만큼 늘어나고, 한 번 만들어진 시계열은 그 요청이 끝난 뒤에도
남는다.

### 재시작하면 무엇이 달라지는가

`_total`로 끝나는 Counter는 이 Application이 살아 있는 동안 실제로 일어난 일만 센다. 재시작하면 0에서
다시 시작하므로 MySQL에 쌓인 Incident 총계와 같지 않고, 같아야 하는 값도 아니다. 하루에 몇 건이
열렸는지를 권위 있게 세는 것은 Daily Incident Report이다.

`pulseops_incidents_active`는 반대로 언제나 MySQL을 읽는다. 재시작해도 값이 이어지며, 이 Instance가 열지
않은 Incident와 사람이 확인 처리한 Incident도 그대로 세어진다.

### 값이 그려지지 않는 구간

PulseOps가 MySQL이나 Kafka를 읽지 못하면 그 Gauge는 0이 아니라 값 없음이 된다. 읽지 못한 것을 0으로
답하면 저장소 장애가 대시보드에서 가장 건강한 상태로 보이기 때문이다. Grafana에서 선이 끊긴 구간은
"아무 일도 없었다"가 아니라 "읽지 못했다"는 뜻이다.

Consumer Lag은 조금 다르다. Prometheus가 긁을 때 Kafka를 직접 부르면 Broker가 아픈 동안 scrape 전체가
그 기다림에 묶여 JVM과 HTTP metric까지 함께 사라진다. 그래서 Gauge는 마지막으로 읽어 둔 값만 내보내고
실제 조회는 다른 Thread에서 한다. 그 값이 얼마나 오래됐는지는
`pulseops_kafka_consumer_lag_snapshot_age_seconds`가 알려 주며, 20초를 넘기면 lag 값 자체가 비워진다.

## 실행

Prometheus와 Grafana는 repository 최상위의 `docker-compose.yml`이 함께 띄운다. 따로 실행할 것은 없다.

```
docker compose up -d
```

전체 stack의 실행과 종료, volume은 [../README.md](../README.md)에 있다.

### 무엇이 어떻게 이어지는가

세 container가 같은 Compose network에 있으므로 서로를 service 이름으로 부른다.

| 어디서 | 어디로 | 무엇으로 |
| --- | --- | --- |
| Prometheus | `pulseops-backend:8081` | `prometheus.yml`의 scrape target |
| Grafana | `prometheus:9090` | Datasource provisioning의 url |

Container 안의 `localhost`는 언제나 자기 자신이라 여기에 쓸 수 없다. Host를 거치지 않으므로
`host.docker.internal`도 필요 없고, PulseOps Backend의 port를 Host에 열지 않아도 scrape가 된다.

### 확인

target이 올라왔는지 본다.

```
curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```

`http://localhost:3001`에서 PulseOps 폴더의 **PulseOps Overview**를 연다.

익명 열람을 켜 두는 것은 이 구성이 로컬 데모이고 로그인 화면이 확인을 늦추기 때문이다. 설정을 바꾸려면
로그인해야 하며 관리자 계정은 `admin`이다. 비밀번호 기본값은 `admin`이고 `.env`의
`GRAFANA_ADMIN_PASSWORD`로 바꾼다.

### PulseOps Backend만 Host에서 실행할 때

Backend를 Container가 아니라 Host에서 직접 띄워 두고 관측만 붙이고 싶다면 두 곳을 바꾼다.

- `prometheus.yml`의 target을 `host.docker.internal:8081`로 바꾼다.
- Linux에서는 `host.docker.internal`이 없으므로 Prometheus service에
  `extra_hosts: ["host.docker.internal:host-gateway"]`를 더한다.

## 설정을 UI에서 만들지 않는 이유

Datasource와 Dashboard는 모두 이 폴더의 파일에서 온다. Grafana UI에서 만든 것은 그 container의 volume
안에만 남아 다시 띄우면 사라지고, 어떤 화면이었는지 repository에 기록되지 않는다. 그래서 provisioning은
`allowUiUpdates: false`이며 UI에서 고친 값은 다시 띄울 때 파일의 내용으로 되돌아간다.

## 이 구성이 말하지 않는 것

여기서 보는 숫자는 로컬 데모 환경의 관측값이다. 처리 가능한 최대 요청 수도, 운영 환경의 용량도,
어떤 SLA도 뜻하지 않는다.

HTTP 패널은 PulseOps 자신이 받은 요청을 보여 준다. 관측 대상인 Demo Shop은 Prometheus의 scrape
대상이 아니므로, k6가 Demo Shop에 건 부하는 이 패널이 아니라 Telemetry와 Incident 패널에서 드러난다.
Demo Shop의 상태는 Prometheus가 아니라 Telemetry Event를 거쳐 PulseOps에 도착한다.
