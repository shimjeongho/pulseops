# Troubleshooting

이 문서는 현재 구성에서 실제로 확인 가능한 문제와 그 진단 순서를 정리한다.

## 0. 먼저 읽을 것 — 위험한 해결책

인터넷에서 흔히 보이지만 **이 문서가 권하지 않는** 명령들이다.

| 명령 | 무엇을 잃는가 |
| --- | --- |
| `docker system prune` | 이 project와 **무관한** image, container, network까지 지운다 |
| `docker volume prune` | Incident 이력, Kafka offset, Prometheus 시계열이 사라진다 |
| `docker container prune` | 다른 project의 멈춘 container까지 지운다 |
| `docker compose down -v` | 이 project의 volume 5개를 전부 지운다 |
| `taskkill /IM java.exe` | 사용자의 **다른 Java process**까지 죽인다 (IDE, 다른 Application) |
| `taskkill /F /IM ...` | 이름으로 죽이는 모든 명령이 같은 문제를 갖는다 |

**진단 → 정확한 대상 확인 → 그 대상만 정리**의 순서를 지킨다.

무엇을 죽일지 정할 때는 이름이 아니라 **PID**나 **container 이름**으로 특정한다.

```bash
docker compose ps            # 이 project의 container만 본다
docker compose stop <서비스> # 그 하나만 멈춘다
docker compose restart <서비스>
```

## 1. Port conflict — 기동 실패

### 증상

```
Error response from daemon: ... failed to bind host port 127.0.0.1:8080: address already in use
```

### 진단

Compose가 Host에 여는 port는 여섯이다.

| Port | 서비스 |
| --- | --- |
| 5173 | demo-shop-frontend |
| 5174 | pulseops-frontend |
| 8080 | demo-shop-backend |
| 8081 | pulseops-backend |
| 9090 | prometheus |
| 3001 | grafana |

무엇이 쓰고 있는지 본다.

**Windows (PowerShell)**

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen |
  Select-Object LocalPort, OwningProcess,
    @{n='Process';e={(Get-Process -Id $_.OwningProcess).ProcessName}}
```

**macOS / Linux**

```bash
lsof -i :8080 -sTCP:LISTEN
```

### 해결

- 그 process가 **자기 것이고 필요 없다면** PID로 정확히 종료한다.
- 그 process가 **무엇인지 모르겠다면 종료하지 않는다.** 대신 Compose 쪽 port를 바꾼다.

`docker-compose.yml`의 `ports`에서 왼쪽(Host) 값만 바꾼다.

```yaml
ports:
  - "18080:8080"   # Host 18080 -> Container 8080
```

Container 안쪽 port는 바꾸지 않는다. Prometheus 설정과 nginx proxy가 Container 안의 이름과 port를
쓰기 때문이다.

### MySQL, Redis, Kafka는 이 문제를 겪지 않는다

이 셋은 **Host에 열리지 않는다.** Compose network 안에서만 쓰인다. Host에 이미 MySQL(3306)이나
Redis(6379)가 떠 있어도 부딪히지 않는다.

Application을 Host에서 직접 실행하려면 `docker-compose.yml`에 `ports`를 임시로 더해야 한다.

## 2. Docker healthcheck — 계속 `health: starting`

### 증상

```
docker compose ps
demo-shop-backend   Up 2 minutes (health: starting)
```

### 진단

healthcheck에는 `start_period`가 있다. MySQL 60초, 두 Backend 60초, Kafka 30초이다. 이 시간 안에는
실패해도 `unhealthy`로 표시되지 않는다.

**2분이 넘도록 `starting`이면** log를 본다.

```bash
docker compose logs --tail=100 demo-shop-backend
```

### 흔한 원인

| log에 보이는 것 | 원인 |
| --- | --- |
| `Communications link failure` | MySQL이 아직 준비되지 않았거나 계정이 없다 |
| `Flyway ... Migration ... failed` | Migration 실패. §4를 본다 |
| `Schema-validation: missing table` | `ddl-auto=validate`인데 Flyway가 돌지 않았다 |
| `Connection to node -1 could not be established` | Kafka Broker가 아직이다 |
| 아무 log도 없이 멈춤 | memory 부족. §9를 본다 |

## 3. Kafka startup — Topic이 없거나 Consumer가 붙지 못한다

### 증상

```
docker compose logs pulseops-backend
... Consumer ... subscribed to topic(s): demo-shop.telemetry.api.v1
... Node -1 disconnected
```

또는 Metric이 전혀 쌓이지 않는다.

### 진단

`kafka-init`이 성공했는지 본다.

```bash
docker compose logs kafka-init
```

기대하는 출력:

```
Created topic demo-shop.telemetry.api.v1.
Created topic demo-shop.telemetry.api.v1.dlt.
demo-shop.telemetry.api.v1
demo-shop.telemetry.api.v1.dlt
```

`docker compose ps -a`에서 `kafka-init`이 `exited (0)`이어야 한다. **0이 아니면 Topic이 없다.**

Topic을 직접 확인한다.

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --list
```

### 해결

`kafka-init`만 다시 돌린다.

```bash
docker compose up kafka-init
```

Kafka Broker 자체가 뜨지 않으면 Broker log를 본다.

```bash
docker compose logs kafka | grep -i error
```

### 왜 자동 생성에 기대지 않는가

`KAFKA_AUTO_CREATE_TOPICS_ENABLE`이 `false`이다. 자동 생성은 Broker 기본 partition 수를 쓰므로, 어느
Application이 먼저 붙느냐에 따라 partition 수가 달라진다. 특히 PulseOps는 Telemetry Topic을 선언하지
않으므로, 먼저 뜨면 없는 Topic을 구독한 채로 기다린다.

## 4. MySQL readiness — 계정이나 database가 없다

### 증상

```
Access denied for user 'pulseops'@'...'
Unknown database 'pulseops'
```

### 진단

계정과 database는 `infra/docker/mysql/init/10-create-databases.sh`가 만든다. 이 script는 MySQL
entrypoint가 **첫 기동에서만** 실행한다.

```bash
docker compose logs mysql | grep -i "create\|error"
```

### 함정: volume이 이미 있으면 init script가 돌지 않는다

`mysql-data` volume이 이미 초기화되어 있으면 entrypoint는 init script를 건너뛴다. `.env`에서
`PULSEOPS_DB_USER` 같은 값을 **나중에** 바꾸면, 새 계정은 만들어지지 않고 Application만 새 이름으로
접속하려 든다.

### 해결

계정 이름을 원래대로 되돌리거나, 계정을 직접 만든다.

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "SELECT user, host FROM mysql.user;"
```

**깨끗한 database가 필요하다면 volume을 지우는 대신 새 project 이름을 쓴다.**

```bash
docker compose -p pulseops-clean up -d --build
```

## 5. Flyway migration 실패

### 증상

```
FlywayValidateException: Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 3
```

### 원인

이미 적용된 migration 파일을 **수정**했다. Flyway는 적용 당시의 checksum을 기록해 두고 비교한다.

### 해결

- 적용된 migration 파일은 고치지 않는다. **새 version 파일을 추가**한다.
- 개발 중이라 이력을 버려도 된다면, 그 project의 database만 새로 만든다(§4의 새 project 방식).

`ddl-auto=validate`이므로 Entity와 Schema가 어긋나도 기동이 실패한다. 이것은 의도된 것이다. 조용히
table을 만들어 주면 운영에서 무엇이 진짜 Schema인지 알 수 없게 된다.

## 6. Redis — Metric이 쌓이지 않는다

### 증상

`/api/metrics`가 계속 빈 배열이거나, Metric이 갱신되지 않는다.

### 진단 순서

**1. Demo Shop이 요청을 받았는가**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/products
```

**2. Event가 Kafka로 갔는가** — Demo Shop log에서 발행 실패를 찾는다.

```bash
docker compose logs demo-shop-backend | grep -i "Telemetry Event를 Kafka로 보내지 못했다"
```

이 WARN이 보이면 Producer가 fail-open으로 포기한 것이다. Kafka 쪽을 본다(§3).

**3. Consumer가 읽었는가**

```bash
curl -s http://localhost:8081/actuator/prometheus | grep pulseops_telemetry_events
```

| 값 | 뜻 |
| --- | --- |
| `processed_total{result="applied"}`가 증가 | 정상 |
| `rejected_total`이 증가 | 계약 위반. DLT를 본다 |
| `failed_total`이 증가 | 처리 중 예외. PulseOps log를 본다 |
| 전부 0 | Consumer가 record를 못 받고 있다. §3을 본다 |

**4. Redis가 살아 있는가**

```bash
docker compose exec redis redis-cli ping     # PONG
docker compose exec redis redis-cli keys 'pulseops:*' | head
```

### 창이 60초라는 것을 기억한다

요청을 멈추면 약 1분에 걸쳐 `requestCount`가 0으로 줄어든다. Metric Key TTL은 300초이므로 5분 뒤에는
계열 자체가 목록에서 사라진다. **이것은 정상이다.**

## 7. DLT에 record가 쌓인다

### 증상

`pulseops_telemetry_events_rejected_total`이 증가한다.

### 진단

DLT를 읽어 본다.

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic demo-shop.telemetry.api.v1.dlt \
  --from-beginning --max-messages 5
```

PulseOps log에서 거절 이유를 찾는다.

```bash
docker compose logs pulseops-backend | grep -i "계약"
```

계약 위반의 종류는 [reliability.md](reliability.md) §5에 정리되어 있다.

**DLT를 다시 읽어 재처리하는 Consumer는 없다.** 사람이 원인을 확인할 때까지 붙잡아 두는 자리이다.

## 8. Prometheus target DOWN

### 증상

Grafana panel이 전부 비어 있다.

### 진단

```bash
curl -s http://localhost:9090/api/v1/targets | python -m json.tool | grep -A2 health
```

`health`가 `up`이 아니면 `lastError`를 본다.

| lastError | 원인 |
| --- | --- |
| `connection refused` | pulseops-backend가 뜨지 않았다 |
| `context deadline exceeded` | `scrape_timeout` 4초를 넘겼다 |
| `404 Not Found` | Actuator에서 prometheus endpoint가 열리지 않았다 |

Actuator를 직접 확인한다.

```bash
curl -s http://localhost:8081/actuator/prometheus | head -5
```

### 404가 나온다면

`management.endpoints.web.exposure.include`에 `prometheus`가 있어야 한다. 기본값은 `health` 하나뿐이라
적지 않으면 열리지 않는다.

> **`*`로 여는 것으로 해결하지 않는다.** `env`와 `configprops`가 함께 열려 Claude API key와 GitHub
> token이 인증 없는 HTTP 응답으로 나간다. 이 Application에는 인증 계층이 없으므로 **여는 것이 곧
> 공개**이다.

## 9. Grafana datasource / dashboard 문제

### Datasource가 안 붙는다

```bash
curl -s http://localhost:3001/api/datasources/uid/pulseops-prometheus/health
```

기대: `{"message":"Successfully queried the Prometheus API.","status":"OK"}`

Datasource는 `http://prometheus:9090`을 본다. **Container 안의 `localhost`는 자기 자신이므로**
여기에 `localhost`를 쓸 수 없다.

### Dashboard가 안 보인다

```bash
curl -s "http://localhost:3001/api/search?query=PulseOps"
```

`PulseOps` 폴더와 `PulseOps Overview`(uid `pulseops-overview`)가 나와야 한다.

안 나오면 provisioning mount를 확인한다.

```bash
docker compose exec grafana ls /var/lib/grafana/dashboards
docker compose exec grafana ls /etc/grafana/provisioning/dashboards
```

### UI에서 고친 것이 사라진다

**정상이다.** `allowUiUpdates: false`이고 Datasource는 `editable: false`이다. 근거는 UI가 아니라
`monitoring/grafana` 아래 파일이다. 바꾸려면 파일을 고치고 Grafana를 다시 올린다.

```bash
docker compose restart grafana
```

## 10. Frontend 502 Bad Gateway

### 증상

http://localhost:5174 는 열리는데 화면에서 API 호출이 502로 실패한다.

### 원인

Frontend Container는 nginx이고 `/api`를 자기 Backend로 넘긴다. Backend가 아직 뜨지 않았으면 502가
나온다.

### 진단

```bash
docker compose ps pulseops-backend      # healthy 인가
docker compose logs pulseops-frontend | tail -20
```

Backend가 healthy가 될 때까지 기다린 뒤 새로고침한다. `depends_on`이 `service_healthy`로 걸려 있으므로
정상 기동에서는 이 순서가 지켜지지만, Backend만 따로 재시작하면 잠시 502가 날 수 있다.

## 11. SSE가 안 오거나 늦게 온다

### 증상

Incident가 열렸는데 Dashboard가 새로고침 전까지 바뀌지 않는다.

### 진단

먼저 SSE 자체를 직접 확인한다.

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8081/api/incidents/stream
```

`event:connected`가 곧바로 오고 20초마다 `event:heartbeat`가 와야 한다.

| 증상 | 원인 |
| --- | --- |
| `connected`도 안 온다 | Backend 문제 |
| `curl`로는 오는데 브라우저에서만 안 온다 | nginx buffering |
| 연결이 자꾸 끊긴다 | nginx 읽기 대기 시간이 SSE 수명보다 짧다 |
| 로컬에서는 오는데 Quick Tunnel 외부 화면에서만 안 온다 | 아래 [Quick Tunnel 외부 경로](#quick-tunnel-외부-경로) |

### nginx buffering

`pulseops/frontend/nginx.conf`에 SSE 경로만 따로 두어 buffering을 끄고 대기 시간을 Backend가 정한
수명보다 길게 잡아 두었다. 이 파일을 고쳤다면 그 설정이 남아 있는지 확인한다.

응답이 끝나지 않는 것이 SSE의 정상 동작이다. nginx가 응답을 모아 두면 알림이 늦게 도착한다.

### 프록시나 확장 프로그램

회사 프록시나 일부 브라우저 확장이 `text/event-stream`을 버퍼링한다. `curl`로는 오는데 브라우저에서만
안 온다면 이쪽을 의심한다.

### Quick Tunnel 외부 경로

무료 외부 데모(Cloudflare Quick Tunnel)에서는 이 증상이 알려진 제한이다. 외부 경로에서도
`/api/incidents/stream`은 `200 text/event-stream`으로 연결되지만, Event가 곧바로 오지 않고 연결이 끝날 때
한꺼번에 도착하는 현상이 실측되었다. 같은 화면을 이 PC에서 열면 `connected`와 `heartbeat`가 곧바로
온다.

```bash
curl -N -H "Accept: text/event-stream" http://127.0.0.1:5174/api/incidents/stream
```

로컬 경로도 같은 nginx와 Backend를 거치므로 위의 nginx buffering 문제가 아니다. Quick Tunnel 경로 안의
어느 구간에서 생기는지는 확인하지 않았다. 외부 데모에서는 새로고침으로 최신 Incident를 본다.

## 12. Testcontainers 자원 부족 — 테스트 실패

### 증상

`./gradlew test`가 아래로 실패한다.

```
Could not start container
Timed out waiting for container port to open
container is unhealthy
```

### 원인

Backend Integration Test는 Testcontainers로 MySQL, Redis, Kafka를 **직접 띄운다.** 두 project의
테스트를 동시에 돌리거나, Compose stack이 떠 있는 상태에서 돌리면 memory와 CPU가 겹친다.

### 해결

**1. Compose stack을 먼저 내린다.**

```bash
docker compose down
```

**2. 두 Backend를 동시에 돌리지 않는다.** 순서대로 실행한다.

```bash
cd pulseops/backend  && sh gradlew --no-daemon --console=plain test
cd demo-shop/backend && sh gradlew --no-daemon --console=plain test
```

CI도 같은 이유로 두 project를 **다른 runner**에서 돌린다.

**3. Docker에 memory를 더 준다.** Docker Desktop → Settings → Resources.

**4. 남은 container를 확인한다.**

```bash
docker ps --filter "label=org.testcontainers=true"
```

Testcontainers는 `ryuk`이 자동으로 정리한다. 정리되지 않은 것이 보이면 **이름을 확인한 뒤** 그것만
지운다. `docker container prune`을 쓰지 않는다.

## 13. `test`가 UP-TO-DATE로 건너뛰어진다

### 증상

```
> Task :test UP-TO-DATE
BUILD SUCCESSFUL in 10s
```

10초 만에 끝났다면 테스트가 **실제로 실행되지 않은 것이다.** Gradle은 입력이 바뀌지 않으면 task를
건너뛴다.

### 해결

```bash
sh gradlew --no-daemon --console=plain cleanTest test
```

`> Task :test`가 `UP-TO-DATE` 없이 나오고 실행 시간이 분 단위여야 한다.

실제 결과 수는 `build/test-results/test/*.xml`에서 확인한다.

## 14. Windows / Docker Desktop 관련

### `gradlew` Permission denied

이 repository의 `gradlew`는 실행 권한 bit 없이(100644) 기록되어 있다. Linux에서 `./gradlew`가
`Permission denied`로 끝난다.

```bash
sh gradlew --no-daemon --console=plain test
```

`gradlew`는 POSIX sh script이므로 이렇게 부르면 권한과 무관하게 동작한다. CI와 Dockerfile도 같은
방식을 쓴다.

### Git Bash에서 volume mount 경로가 깨진다

Git Bash는 `/scripts` 같은 경로를 Windows 경로로 자동 변환한다.

```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "C:\Users\you\dev\pulseops\load-test\k6:/scripts:ro" \
  grafana/k6:1.4.0 run /scripts/baseline.js
```

### Docker Desktop이 memory를 충분히 쓰지 못한다

이 stack은 JVM 2개와 Kafka Broker를 함께 띄운다. 현재 할당량은 이렇게 본다.

```bash
docker info --format "CPUs: {{.NCPU}} / Memory: {{.MemTotal}}"
```

## 15. `.env` 관련

### `.env`를 만들었는데 반영되지 않는다

- `.env`는 `docker-compose.yml`이 있는 **repository 최상위**에 있어야 한다.
- 값을 바꾼 뒤에는 해당 service를 다시 만들어야 한다.

```bash
docker compose up -d --no-deps pulseops-backend
```

`restart`로는 부족하다. 환경 변수는 container를 만들 때 정해진다.

### `.env`가 git에 올라갈까 걱정된다

`.gitignore`에 있다. 확인:

```bash
git check-ignore -v .env
```

## 16. AI / GitHub 기능이 503을 반환한다

### 증상

```json
{ "status": 503, "code": "AI_ANALYSIS_UNAVAILABLE" }
{ "status": 503, "code": "GITHUB_CONTEXT_UNAVAILABLE" }
```

### 원인

**대개 정상이다.** 두 기능은 기본이 꺼짐이다.

```properties
pulseops.ai.claude.enabled=false
pulseops.github.enabled=false
```

### 켰는데도 503이라면

**Claude** — 셋이 모두 있어야 한다.

```
PULSEOPS_AI_CLAUDE_ENABLED=true
ANTHROPIC_API_KEY=...
CLAUDE_MODEL=...
```

`CLAUDE_MODEL`이 비어 있으면 켜지지 않는다. model 이름은 코드에 박혀 있지 않다.

**GitHub** — 넷이 모두 있어야 한다.

```
PULSEOPS_GITHUB_ENABLED=true
GITHUB_TOKEN=...
GITHUB_OWNER=...
GITHUB_REPOSITORY=...
```

`code search`는 인증된 요청만 허용되므로 **공개 repository라도 token이 필요하다.**

설정이 실제로 들어갔는지 확인한다.

```bash
docker compose exec pulseops-backend env | grep -E "PULSEOPS_(AI|GITHUB)|GITHUB_(OWNER|REPOSITORY)"
```

> `ANTHROPIC_API_KEY`와 `GITHUB_TOKEN`은 출력하지 않는다. 있는지만 확인하려면 `-c`로 센다.
> ```bash
> docker compose exec pulseops-backend sh -c 'echo ${ANTHROPIC_API_KEY:+set}'
> ```

### GitHub 맥락이 비어 있다

`pulseops.github.source-root` 밖의 파일은 어떤 검색 결과로 나와도 선택하지 않는다. 기본값은
`demo-shop/backend/src/main/java`이다. 다른 repository를 가리키고 있다면 이 값을 그 repository의 구조에
맞게 바꿔야 한다.

`code search`는 **기본 branch만 색인한다.** `GITHUB_REF`에 다른 branch를 적어도 파일을 찾는 단계는
기본 branch를 본다.

## 17. 그래도 안 되면 — 순서대로 확인

```bash
# 1. 무엇이 떠 있는가
docker compose ps -a

# 2. Topic이 만들어졌는가
docker compose logs kafka-init

# 3. 저장소가 준비되었는가
docker compose logs mysql | tail -30
docker compose exec redis redis-cli ping

# 4. Application이 기동을 마쳤는가
curl -s http://localhost:8080/api/products -o /dev/null -w "%{http_code}\n"
curl -s http://localhost:8081/actuator/health

# 5. Event가 흐르는가
curl -s http://localhost:8081/actuator/prometheus | grep pulseops_telemetry_events

# 6. 관측이 붙었는가
curl -s http://localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```

전부 다시 시작해야 한다면 **volume을 지우지 않는 재시작**을 먼저 시도한다.

```bash
docker compose down
docker compose up -d --build
```

그래도 안 되고 깨끗한 상태가 필요하다면, 기존 volume을 지우는 대신 **새 project 이름**을 쓴다.

```bash
docker compose -p pulseops-clean up -d --build
```
