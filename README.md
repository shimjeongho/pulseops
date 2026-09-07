# PulseOps

Demo Shop에서 발생하는 서비스 Telemetry Event를 Kafka로 모으고, 실시간 Metric을 계산해 이상 상태를
탐지하고 Incident를 관리하는 이벤트 기반 서비스 모니터링 플랫폼이다.

두 개의 독립적인 Spring Boot Application으로 이루어진다.

| Application | 역할 |
| --- | --- |
| Demo Shop | 관측 대상. 상품, 주문, 결제와 개발용 장애 주입. 요청이 끝날 때마다 Telemetry Event를 Kafka로 보낸다 |
| PulseOps | 관측자. Event를 읽어 Metric을 만들고 Incident를 열고 닫으며, 화면과 API로 그 상태를 보여 준다 |

PulseOps는 Demo Shop의 Business Database에 접근하지 않는다. 두 Application 사이에는 Kafka Topic
하나만 있다.

## 전체 구성

`docker compose up -d --build` 한 번으로 아래 전부가 뜬다.

브라우저가 화면과 API를 만나는 길이다. 두 화면은 각자의 nginx가 서비스하고 `/api`를 자기 Backend로
넘기므로, 브라우저에게는 언제나 하나의 Origin만 보인다.

```
브라우저                                브라우저
   │ :5173                                │ :5174
demo-shop-frontend (nginx)             pulseops-frontend (nginx)
   │ /api → demo-shop-backend:8080        │ /api → pulseops-backend:8081
demo-shop-backend                      pulseops-backend
   │                                      │
MySQL demoshop                         MySQL pulseops + Redis
```

Demo Shop에서 일어난 일이 PulseOps에 도착하는 길이다. 두 Application은 이 Topic 하나로만 이어진다.

```
demo-shop-backend
   │ 요청이 끝날 때마다 Telemetry Event
   ▼
kafka   demo-shop.telemetry.api.v1  (partition 3)
   │
   ▼
pulseops-backend  ──▶ Redis Metric ──▶ Incident 판정 ──▶ MySQL Incident ──▶ SSE
   │ /actuator/prometheus
   ▼
prometheus :9090 ──▶ grafana :3000
```

Container끼리는 service 이름으로 서로를 부른다. `mysql`, `redis`, `kafka`, `demo-shop-backend`,
`pulseops-backend`가 그대로 hostname이다. Container 안의 `localhost`는 언제나 자기 자신이므로 서로를
가리키는 데 쓸 수 없다.

## 요구 환경

- Docker Engine과 Docker Compose v2. `docker compose version`으로 확인한다.
- Docker Desktop을 쓴다면 memory를 넉넉히 준다. 이 stack은 JVM 두 개와 Kafka Broker를 함께 띄운다.
- 아래 Host port가 비어 있어야 한다.

| Port | 쓰는 곳 |
| --- | --- |
| 5173 | Demo Shop 화면 |
| 5174 | PulseOps 화면 |
| 8080 | Demo Shop Backend API |
| 8081 | PulseOps Backend API |
| 9090 | Prometheus |
| 3000 | Grafana |

MySQL, Redis, Kafka는 Host에 열지 않는다. 이 셋은 Compose network 안에서만 쓰이며, 밖으로 열면
Host에 이미 떠 있는 다른 MySQL이나 Redis와 port가 부딪힌다. Application을 Host에서 직접 실행하려면
`docker-compose.yml`에 `ports`를 임시로 더한다.

Application을 build할 JDK나 Node.js는 Host에 없어도 된다. Container 안에서 build한다.

## 실행

```
docker compose up -d --build
```

처음에는 image build와 dependency 내려받기 때문에 몇 분이 걸린다. 두 번째부터는 훨씬 빠르다.

상태를 본다.

```
docker compose ps
```

`mysql`, `redis`, `kafka`, 두 Backend, 두 Frontend, `prometheus`, `grafana`가 `healthy`로 보이면
준비가 끝난 것이다. `kafka-init`은 Topic을 만들고 끝나는 service라 `exited (0)`이 정상이다.

기동 순서는 `depends_on`의 시작 순서가 아니라 healthcheck로 정한다. MySQL이 연결을 받고, Kafka
Broker가 실제로 요청에 답하고, Topic이 만들어진 뒤에야 Application이 뜬다.

## 접근

| 주소 | 무엇 |
| --- | --- |
| http://localhost:5173 | Demo Shop 화면. 상품 조회부터 주문과 결제까지 |
| http://localhost:5174 | PulseOps Dashboard. 실시간 Metric, Incident, Consumer Lag |
| http://localhost:8080/api/products | Demo Shop Backend API |
| http://localhost:8081/api/metrics | PulseOps Metric API |
| http://localhost:8081/api/incidents/active | 처리 중인 Incident |
| http://localhost:8081/api/kafka/consumer-lag | Consumer Lag |
| http://localhost:8081/actuator/health | PulseOps 상태 |
| http://localhost:9090 | Prometheus |
| http://localhost:3000 | Grafana. PulseOps 폴더의 **PulseOps Overview** |

Demo Shop 화면에서 상품을 몇 번 열어 보면 PulseOps 화면의 Metric에 그 요청이 나타난다. 그 경로는
Demo Shop HTTP → Kafka → PulseOps Consumer → Redis이다.

### 화면이 Backend를 찾는 방법

Frontend Container는 nginx이고, `/api`로 시작하는 요청을 각자의 Backend로 넘긴다. 브라우저 입장에서는
화면과 API가 같은 Origin에서 오므로 Backend에 CORS 설정이 필요 없고, Frontend는 지금처럼 상대 경로로만
API를 부르면 된다. build 시점에 Backend 주소를 심지 않기 때문에 같은 image를 다른 환경에서 그대로 쓸
수 있다.

PulseOps의 Incident 알림은 SSE라 응답이 끝나지 않는다. nginx가 응답을 모아 두면 알림이 늦게 도착하고,
읽기 대기 시간이 짧으면 멀쩡한 연결을 nginx가 먼저 끊는다. 그 자리는 `pulseops/frontend/nginx.conf`에
따로 두어 buffering을 끄고 대기 시간을 Backend가 정한 수명보다 길게 잡는다.

## 상태 확인

```
docker compose ps
docker compose logs -f pulseops-backend
docker compose logs --tail=100 demo-shop-backend
```

Application이 뜨지 않으면 대개 아래 중 하나다.

```
docker compose logs mysql          # 계정과 database가 만들어졌는가
docker compose logs kafka-init     # Topic이 만들어졌는가
docker compose logs pulseops-backend
```

## 종료

```
docker compose down
```

Container와 network를 내린다. **데이터는 남는다.** 다시 `docker compose up -d`로 올리면 Incident
이력, Kafka offset, Prometheus 시계열이 그대로 이어진다.

```
docker compose down -v
```

`-v`는 아래 volume을 **모두 삭제한다.** Incident 이력과 수집한 시계열이 사라지고 되돌릴 수 없다.
정리할 생각으로 습관처럼 붙이지 않는다.

## Volume

| Volume | 무엇이 들어 있는가 | 왜 남기는가 |
| --- | --- | --- |
| `pulseops_mysql-data` | Incident, AI 분석, GitHub 코드 맥락, Daily Report | 영구 보존이 목적인 데이터다. 여기가 비면 지금까지 열린 Incident 이력이 사라진다 |
| `pulseops_kafka-data` | KRaft metadata, Topic log, Consumer group offset | 여기가 비면 PulseOps가 Topic을 처음부터 다시 읽어 이미 집계한 Event를 다시 집계한다 |
| `pulseops_redis-data` | Rolling Metric, 중복 방지 Key | Consumer는 at-least-once라 재시작 직후 같은 Event를 다시 받을 수 있다. 중복 방지 Key가 살아 있어야 그 Event가 두 번 집계되지 않는다 |
| `pulseops_prometheus-data` | 수집한 시계열 | 여기가 비면 재시작 이전 구간의 그래프가 사라진다 |
| `pulseops_grafana-data` | Grafana 내부 database. 계정과 개인 설정 | Dashboard와 Datasource는 여기가 아니라 `monitoring/grafana` 아래 파일에서 온다 |

Redis에 있는 값은 전부 TTL을 갖는다. Metric은 300초, 중복 방지 Key는 900초다. volume을 두는 것은
영구 보존을 위해서가 아니며, Redis는 어떤 데이터의 유일한 저장소도 아니다.

## 설정

기본 실행에는 아무 설정도 필요 없다. 바꾸고 싶은 것이 있을 때만 `.env`를 만든다.

```
cp .env.example .env
```

`.env`는 repository에 넣지 않는다.

### 선택: Claude Incident 분석

Incident가 만들어진 뒤 **사람이 요청했을 때만** Claude에게 사후 분석을 맡기는 기능이다. 장애를 탐지하는
데는 쓰이지 않고, Incident를 열거나 상태를 바꾸지 않으며, Scheduler나 알림이 대신 호출하지도 않는다.

기본은 꺼짐이다. 켜지 않아도 Incident 탐지, Metric, 확인 처리, SSE, Daily Report는 전부 그대로
동작한다. 쓰려면 `.env`에 셋을 모두 채운다.

```
PULSEOPS_AI_CLAUDE_ENABLED=true
ANTHROPIC_API_KEY=...
CLAUDE_MODEL=...
```

AI 결과는 확정된 원인이 아니다. 화면에서도 Suggested Analysis로 표시된다.

### 선택: GitHub 코드 맥락

Incident와 관련 있어 보이는 소스 조각과 발생 직전 commit을 GitHub에서 모으는 기능이다. 읽기 권한만
필요하고 commit도 branch도 PR도 만들지 않는다. 이것도 사람이 요청했을 때만 호출한다.

```
PULSEOPS_GITHUB_ENABLED=true
GITHUB_TOKEN=...
GITHUB_OWNER=...
GITHUB_REPOSITORY=...
```

### 선택: 장애 주입

Demo Shop이 `X-Demo-Fault` Header를 알아듣게 한다. 기본은 꺼짐이며, 꺼져 있으면 Interceptor 자체가
등록되지 않아 그 Header는 아무 의미도 없는 평범한 Header가 된다.

```
DEMO_SHOP_FAULT_INJECTION_ENABLED=true
```

Demo와 검증에서만 켠다. 안전 경계는 이 Backend 설정 하나이며, 화면에서 메뉴를 감추는 것은 안전장치가
아니다.

## Prometheus와 Grafana

Prometheus는 PulseOps Backend의 `/actuator/prometheus`를 5초마다 긁는다. 대상이 UP인지 확인한다.

```
curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```

Grafana는 http://localhost:3000 에서 로그인 없이 볼 수 있다. Datasource와 Dashboard는 UI가 아니라
`monitoring/grafana` 아래 파일에서 온다. UI에서 고친 값은 다시 띄우면 파일의 내용으로 돌아간다.

무엇이 수집되고 각 metric이 무슨 뜻인지는 [monitoring/README.md](monitoring/README.md)에 있다.

## k6 부하 확인

`load-test/k6`의 script는 Demo Shop에 실제 HTTP 요청을 보내 관측 경로 전체가 이어져 있는지 본다.
Kafka에 Event를 직접 넣지 않는다. 시작점은 언제나 Demo Shop의 HTTP 요청이다.

Compose stack을 대상으로 하려면 Host에 열려 있는 Demo Shop Backend를 가리킨다.

```
k6 run -e DEMO_SHOP_BASE_URL=http://localhost:8080 load-test/k6/baseline.js
```

자세한 시나리오와 기대 결과는 [load-test/k6/README.md](load-test/k6/README.md)에 있다.

## Host에서 직접 실행하기

Compose 없이 Application만 Host에서 실행할 수도 있다. 그때는 `application.properties`의 기본값이
쓰이며 MySQL, Redis, Kafka가 `localhost`에 있다고 가정한다. Frontend는 `npm run dev`로 띄우고, Vite
Dev Server의 Proxy가 nginx 대신 `/api`를 Backend로 넘긴다.

각 폴더의 README에 실행 방법이 있다.

- [demo-shop/frontend/README.md](demo-shop/frontend/README.md)
- [pulseops/frontend/README.md](pulseops/frontend/README.md)

## CI

`.github/workflows/ci.yml`이 Pull Request와 main push에서 돈다.

확인하는 것:

- Demo Shop Backend `./gradlew test`
- PulseOps Backend `./gradlew test`
- 두 Frontend의 `npm ci`, `npm run lint`, `npm run build`
- `docker compose config`
- 네 Application image의 build

확인하지 않는 것:

- 전체 Compose stack을 띄우는 End-to-End. Kafka, MySQL, Redis, Prometheus, Grafana와 Application
  넷을 매 Pull Request마다 올리는 것은 시간과 자원을 크게 쓰면서, 거기서 새로 잡히는 문제는 로컬
  검증에서 이미 잡히는 것과 같다. 이 확인은 로컬에서 한다.
- 배포. image를 registry로 밀지 않고 Release도 tag도 만들지 않는다.
- 외부 API 호출. Claude와 GitHub 기능은 꺼진 채로 테스트되며, CI는 secret을 요구하지 않는다.
  Pull Request에서 secret 없이 통과해야 한다.

Backend 테스트는 Testcontainers로 MySQL, Redis, Kafka를 직접 띄운다. 그래서 workflow에는 별도의
service container를 두지 않으며, 두 Backend는 자원이 겹치지 않도록 각각 다른 runner에서 돈다.

## 이 구성이 말하지 않는 것

여기 있는 것은 로컬 데모와 검증을 위한 구성이다. 운영 배포 구성이 아니다.

- credential이 코드와 기본값에 그대로 있다. 로컬에서만 쓰는 값이며 비밀이 아니다.
- 인증과 인가 계층이 없다. Actuator에서 여는 것은 곧 공개하는 것이라 `health`와 `prometheus` 둘만
  연다.
- 모든 구성 요소가 Instance 하나뿐이다. Kafka는 Broker 하나이고 replication factor는 1이다.
- 자원 한도, 재시작 정책, backup, 이중화 어느 것도 정해져 있지 않다.
- 여기서 보는 숫자는 이 환경의 관측값이다. 처리 가능한 최대 요청 수도, 운영 환경의 용량도, 어떤
  SLA도 뜻하지 않는다.

Telemetry 전달도 정확히 한 번이 아니다. Producer는 발행에 실패해도 요청을 살리고, Consumer는
at-least-once이며 중복은 Redis로 거른다. Redis가 아픈 구간의 Event는 집계에서 빠질 수 있다.
