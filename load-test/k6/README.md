# k6 Load Test

Demo Shop에 실제 HTTP 부하를 걸어 PulseOps의 관측 경로 전체를 확인하는 script이다.

```
Demo Shop HTTP
  → TelemetryFilter
  → Kafka Producer
  → Kafka
  → PulseOps Consumer
  → Redis Metric
  → IncidentDetector
  → MySQL Incident
```

Kafka에 Event를 직접 넣지 않는다. 시작점은 언제나 Demo Shop의 HTTP 요청이며, 그래야 위 경로가 전부 검증 범위에 들어온다.

k6는 검증 도구이지 Application의 일부가 아니다. Backend Gradle dependency도 Frontend npm dependency도 아니며, 이 폴더가 없어도 두 Application은 그대로 빌드되고 실행된다.

## 요구 환경

- [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/)가 설치되어 있어야 한다. `k6 version`으로 확인한다.
- Demo Shop Backend, PulseOps Backend, Kafka, Redis, MySQL이 모두 실행 중이어야 한다.
- 장애 시나리오 둘은 Demo Shop에서 장애 주입이 켜져 있어야 한다.

```
java -jar demo-shop-backend.jar --demo-shop.fault-injection.enabled=true
```

## 환경 변수

| 이름 | 기본값 | 설명 |
| --- | --- | --- |
| `DEMO_SHOP_BASE_URL` | `http://localhost:8080` | 부하 대상 Demo Shop Backend 주소 |
| `ALLOW_LOAD_TEST` | 없음 | 로컬이 아닌 주소를 대상으로 할 때 `true`로 명시 |

주소를 코드에 박지 않는다. 대신 로컬이 아닌 주소는 `ALLOW_LOAD_TEST=true` 없이 실행되지 않는다. 이 script들은 초당 수십 건을 몇 분 동안 보내고 그중 둘은 일부러 장애를 주입하므로, 오타 하나로 운영 서버를 향하는 일을 구조적으로 막아 둔다.

각 실행은 요청을 보내기 전에 대상을 먼저 출력한다.

```
target=http://localhost:8080
localTarget=true
```

## 시나리오

### baseline

```
k6 run load-test/k6/baseline.js
```

정상 `GET /api/products` 부하이다. 초당 20건을 90초 동안 보낸다.

기대: Metric에 요청 수가 쌓이고 Incident는 열리지 않는다.

### error-incident

```
k6 run load-test/k6/error-incident.js
```

`X-Demo-Fault: ERROR_503`을 붙여 초당 20건을 90초 동안 보낸다.

기대: 오류율 100%로 `ERROR_RATE` Incident가 `CRITICAL`로 열린다.

503은 이 시나리오가 의도한 결과이므로 `http_req_failed`에 기준을 걸지 않는다. 대신 `expected_503` 비율을 직접 확인한다.

### latency-incident

```
k6 run load-test/k6/latency-incident.js
```

`X-Demo-Fault: LATENCY_3500`을 붙여 초당 6건을 90초 동안 보낸다. 요청 하나가 3.5초 동안 Servlet Thread를 붙들기 때문에 다른 시나리오보다 낮은 속도를 쓴다.

기대: 응답은 200이고 오류 수는 늘지 않으며, P95만으로 `LATENCY` Incident가 `CRITICAL`로 열린다.

3.5초 응답이 이 시나리오가 의도한 결과이므로 `http_req_duration`의 p(95)에 기준을 걸지 않는다. 대신 `expected_delayed_2xx` 비율을 직접 확인한다.

## 결과 해석

k6 기본 console summary를 쓴다. 결과 파일을 repository에 남기지 않는다.

이 script의 숫자는 로컬 검증 환경에서 정해진 속도로 요청을 보냈을 때의 기록이다. 처리 가능한 최대 요청 수도, 운영 환경의 용량도 아니다. 부하를 만드는 쪽의 속도를 우리가 정해 두었으므로 애초에 한계를 재는 시험이 아니다.
