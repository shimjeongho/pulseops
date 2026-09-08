# PulseOps — CLAUDE.md

## 1. Project Overview

PulseOps는 Demo Shop에서 발생하는 서비스 Telemetry Event를 Kafka로 수집하고 실시간 Metric을 계산하여 이상 상태를 탐지하고 Incident를 관리하는 이벤트 기반 서비스 모니터링 플랫폼이다.

프로젝트의 핵심 목적은 단순 CRUD 구현이나 AI 기능 구현이 아니다.

다음 기술을 실제 요구사항에 맞게 설계하고 경험하는 것이 목적이다.

- Spring Boot
- Apache Kafka
- Redis
- MySQL
- Scheduler
- SSE
- Docker
- Claude API
- GitHub API
- Prometheus / Grafana
- k6
- GitHub Actions

위 기술들은 최종 목표 기술 스택이며 모든 기술을 초기 단계에서 동시에 도입하지 않는다.
각 기술은 정의된 Development Phase에 도달했을 때만 도입한다.
현재 Phase보다 이후 Phase의 dependency, configuration, infrastructure 또는 placeholder implementation을 선행 추가하지 않는다.

AI는 장애 탐지기가 아니라 Incident 발생 이후 원인 분석을 보조하는 역할만 수행한다.

---

## 2. System Boundaries

시스템은 크게 두 Application으로 구성한다.

### Demo Shop

관측 대상 Application이다.

책임:

- Product
- Order
- Payment
- 개발용 Fault Injection
- HTTP Telemetry Event 생성
- Kafka Event 발행

Demo Shop은 장애 여부를 판단하지 않는다.

### PulseOps

관측 및 Incident 관리 Application이다.

책임:

- Kafka Event 소비
- Metric 집계
- 이상 상태 탐지
- Incident 관리
- SSE
- Report
- AI 분석
- GitHub 연동

PulseOps는 Demo Shop의 Business Database에 직접 접근하지 않는다.

---

## 3. Architecture Rules

다음 규칙을 반드시 지킨다.

1. Demo Shop과 PulseOps는 독립 Spring Boot Application으로 유지한다.

2. Demo Shop의 Business Request가 PulseOps 처리 때문에 실패하거나 지연되지 않도록 한다.

3. 서비스 Telemetry는 Kafka를 통해 비동기로 전달한다.

4. Redis는 짧은 수명의 실시간 Metric 및 Operational State에 사용한다.

5. MySQL은 Incident, Analysis, Report 등 영구 보존 데이터에 사용한다.

6. 실시간 Incident Detection에 Scheduler를 사용하지 않는다.

7. Scheduler는 시간 기반 작업에만 사용한다.

8. Kafka Consumer는 at-least-once delivery를 전제로 구현한다.

9. Consumer side effect는 가능한 한 idempotent하게 구현한다.

10. Kafka Event에는 eventId와 schemaVersion을 반드시 포함한다.

11. Request Body, Authorization Header, Cookie, 비밀번호 및 민감정보를 Telemetry Event에 포함하지 않는다.

12. AI 결과를 사실 또는 확정된 Root Cause로 취급하지 않는다.

---

## 4. Incident Rules

Incident 상태는 다음 세 가지를 사용한다.

- OPEN
- ACKNOWLEDGED
- RESOLVED

허용되는 상태 전이는 다음과 같다.

OPEN → ACKNOWLEDGED

OPEN → RESOLVED

ACKNOWLEDGED → RESOLVED

RESOLVED Incident를 다시 OPEN하지 않는다.

동일 문제가 다시 발생하면 새로운 Incident를 생성한다.

---

## 5. Kafka Rules

MVP Telemetry Topic:

demo-shop.telemetry.api.v1

Event Schema 변경 시 기존 필드를 의미 없이 변경하지 않는다.

Breaking Change가 필요한 경우 새로운 schemaVersion을 사용한다.

Event에는 최소 다음 정보가 존재해야 한다.

- eventId
- schemaVersion
- occurredAt
- service
- requestId
- method
- endpoint
- httpStatus
- latencyMs

endpoint는 실제 resource ID가 아닌 route template 형태를 사용한다.

예:

/api/orders/{orderId}

Kafka Event 소비 과정에서는 동일 eventId가 반복 전달될 수 있다고 가정한다.

Consumer 처리는 at-least-once 성격이며 중복 반영은 Redis dedup으로 막는다.

Producer는 발행 실패 시 요청을 살리는 fail-open이고 Redis 장애 구간의 Event는 집계에서 빠질 수 있으므로 end-to-end exactly-once가 아니다.

---

## 6. Database Rules

Demo Shop과 PulseOps 데이터의 책임을 분리한다.

Demo Shop DB:

- Product
- Order
- OrderItem
- Payment

PulseOps DB:

- Incident
- IncidentEvidence
- AiAnalysis
- GithubReference
- Report

PulseOps는 모든 Telemetry Event를 무조건 MySQL에 저장하지 않는다.

실시간 상태는 Redis에서 관리하며 Incident 분석에 필요한 Evidence만 영속화한다.

---

## 7. Redis Rules

Redis는 다음 용도로 사용한다.

- Rolling Metrics
- Service Status
- Event Deduplication
- 짧은 TTL Operational State

Redis를 단순히 기술 스택에 포함하기 위한 Cache로 사용하지 않는다.

영구 보존이 필요한 데이터의 유일한 저장소로 Redis를 사용하지 않는다.

Phase 3에서 확정한 Metric 계약은 다음과 같으며 이후 Phase에서 임의로 바꾸지 않는다.

- Metric Error는 httpStatus 500 이상이며 4xx는 requestCount에만 포함한다
- Bucket은 10초이고 Rolling Window는 6 bucket 60초이다
- Metric Key TTL은 300초, dedup Key TTL은 900초이다
- latency 표본은 Bucket당 최근 500개만 남긴다
- Redis 쓰기 실패는 WARN 후 건너뛰며 Kafka 소비를 멈추지 않는다
- Redis 읽기 실패는 빈 Metric으로 바꾸지 않고 그대로 올려 보낸다

---

## 8. AI Rules

Claude API는 Incident가 만들어진 이후에만 사용한다.

AI에게 가능한 경우 다음 Evidence를 함께 제공한다.

- Error Events
- Error Rate
- Latency
- 발생 전후 Metrics
- Service
- Endpoint
- Stack Trace
- 최근 Commit
- Changed Files

AI 결과는 다음과 같이 구조화한다.

- Root Cause Candidates
- Evidence
- Confidence
- Impact
- Recommended Actions
- Additional Checks

UI에서는 AI 결과를 Suggested Analysis로 명확히 표시한다.

---

## 9. Fault Injection Rules

Fault Injection은 개발 및 데모 환경에서만 활성화한다.

Production configuration에서는 Fault Injection endpoint가 노출되지 않도록 한다.

Synthetic Incident와 실제 Application Error를 구분할 수 있어야 한다.

---

## 10. Development Workflow

기능을 구현하기 전에 반드시 현재 구조와 영향 범위를 먼저 분석한다.

한 번의 작업에서 여러 Phase를 동시에 구현하지 않는다.

작업 순서는 기본적으로 다음을 따른다.

1. 현재 코드 분석
2. 변경 계획 작성
3. 영향 범위 확인
4. 최소 범위 구현
5. Unit Test
6. Integration Test
7. 기존 Test Regression 확인
8. 변경 내용 요약

---

## 11. Claude Code Working Rules

Claude Code는 요청받은 범위를 넘어 대규모 구조 변경을 임의로 수행하지 않는다.

사용자가 분석 또는 계획만 요청한 경우 파일을 수정하지 않는다.

새로운 dependency를 추가하기 전에 필요성을 설명한다.

새로운 infrastructure component를 임의로 추가하지 않는다.

기존 Architecture Rule과 충돌하는 구현을 발견하면 구현보다 먼저 문제를 보고한다.

대규모 리팩터링과 기능 구현을 같은 작업에서 진행하지 않는다.

한 Phase의 기능을 구현하면서 다음 Phase 기능을 미리 추가하지 않는다.

코드를 작성할 때는 16. Coding Convention & Code Quality를 따른다.

---

## 12. Testing Policy

Business Logic과 Incident Detection Logic은 Controller에 직접 구현하지 않는다.

주요 Domain Logic은 Unit Test가 가능하도록 분리한다.

Kafka, Redis, MySQL 통합 동작은 Integration Test를 작성한다.

가능한 경우 Testcontainers를 활용한다.

버그 수정 시 해당 버그를 재현하는 Regression Test를 먼저 추가하거나 함께 추가한다.

---

## 13. Definition of Done

기능은 다음 조건을 충족해야 완료된 것으로 간주한다.

- 요구사항 충족
- 정상 경로 테스트
- 주요 실패 경로 테스트
- 관련 Unit Test 통과
- 필요한 Integration Test 통과
- 기존 테스트 Regression 없음
- Architecture Rule 위반 없음
- 민감정보 로그 및 Event 노출 없음
- 변경 내용 문서화

---

## 14. Forbidden Changes

다음 변경은 명시적 승인 없이 하지 않는다.

- Demo Shop과 PulseOps Application 통합
- Kafka 제거
- Redis 제거
- DB 종류 변경
- Event Schema Breaking Change
- Incident State 추가/삭제
- 새로운 인프라 서비스 도입
- 인증/인가 시스템 추가
- Microservice 추가 분리
- Kubernetes 도입
- AI를 Incident Detector로 변경
- Fault Injection을 Production에서 활성화
- 전체 프로젝트에 영향을 주는 대규모 리팩터링

---

## 15. Current Development Principle

현재 Phase에 필요한 최소 기능만 구현한다.

미래 확장을 예상하여 필요 이상으로 추상화하지 않는다.

그러나 다음 확장이 불가능해지는 구조는 피한다.

우선순위는 다음과 같다.

Correctness

→ Testability

→ Reliability

→ Maintainability

→ Performance

→ Abstraction

---

## 16. Coding Convention & Code Quality

이 규칙은 Demo Shop과 PulseOps를 포함한 모든 Phase의 코드에 적용한다.

기능이 동작하더라도 아래 규칙을 명백히 위반해 코드 품질이 악화되었다면 완료로 간주하지 않는다.

### 16-1. Naming Convention

Java:

- Class / Record / Enum: PascalCase
- Method / Variable / Field: camelCase
- Constant: UPPER_SNAKE_CASE
- Package: lowercase
- Exception: XxxException
- Request DTO: XxxRequest
- Response DTO: XxxResponse
- Repository: XxxRepository
- Service: XxxService
- Controller: XxxController

Frontend:

- React Component: PascalCase
- Hook: useXxx
- 일반 함수 / 변수: camelCase
- Context: XxxContext
- Provider: XxxProvider
- API 함수는 역할이 드러나는 동사형 이름을 사용한다. 예: getProducts, getProduct, createOrder, getOrders, getOrder, createPayment

파일 이름도 위 Naming Convention과 일관되게 유지한다.

Component를 담은 파일은 PascalCase, 그 외 Module은 camelCase를 사용한다.

### 16-2. 주석 규칙

개발자가 직접 작성하는 코드 주석과 JavaDoc은 한국어를 기본으로 작성한다.

Class명, Method명, 변수명, API Endpoint, Error Code 등 코드 식별자는 영어를 유지한다.

Framework, Library, Protocol 등 외부 기술의 고유 명칭은 원문 표기를 유지한다.

코드 자체를 그대로 읽어주는 불필요한 주석은 작성하지 않는다.

Business Rule, 설계 의도, 제약사항, 기술 선택 이유처럼 코드만으로 이해하기 어려운 내용을 중심으로 주석을 작성한다.

주석 처리된 오래된 코드를 repository에 남기지 않는다.

eslint directive, URL, tool directive 등 기계적 주석은 번역 대상이 아니다.

### 16-3. 책임 분리

하나의 Class / Component / Method / Function은 가능한 한 하나의 명확한 책임을 갖는다.

Controller는 Request 수신, Validation, Service 호출, Response 반환에 집중한다.

Service는 Use Case와 Business Flow를 담당한다.

Entity 상태 변경은 의미가 드러나는 Domain Method를 우선한다.

Repository는 Persistence 책임에 집중한다.

DTO에 Persistence 또는 복잡한 Business Logic을 넣지 않는다.

React Page/Component에 API, Storage, Formatting, Navigation, Domain Logic을 모두 몰아넣지 않는다.

명확히 공통 책임인 API 처리, Formatting, Storage Logic 등만 적절히 분리한다.

### 16-4. 스파게티 코드 방지

다음 형태를 지양한다.

- 지나치게 긴 Method / Function
- 지나치게 긴 React Component
- 깊게 중첩된 if / for / try-catch
- 서로 관련 없는 여러 Domain 책임이 한 함수에 혼재
- 상태 변경 위치가 여러 곳에 흩어짐
- Side Effect 실행 순서를 이해하기 어려운 구조
- API / Storage / Formatting / Navigation / Business Logic이 한 함수에 뒤섞임
- 이름만 보고 책임을 파악하기 어려운 함수
- 불필요한 전역 상태
- 불필요한 useEffect chain
- 복잡한 nested ternary

복잡한 로직은 의미 있는 작은 단위로 나눈다.

단, 단순한 코드를 지나치게 잘게 쪼개어 흐름을 더 어렵게 만들지 않는다.

### 16-5. 중복 코드

동일한 Business Rule을 여러 위치에 복사하지 않는다.

동일한 Error Parsing, Formatting, Storage Logic이 반복되면 적절한 공통 위치로 분리한다.

단순한 2~3줄의 우연한 중복까지 무조건 추상화하지 않는다.

중복 제거를 이유로 의미 없는 Util / Helper / Wrapper 계층을 만들지 않는다.

실제 중복이고 책임이 명확할 때만 추상화한다.

### 16-6. 단순성 우선

현재 요구사항을 만족하는 가장 단순하고 읽기 쉬운 구현을 우선한다.

미래 확장 가능성만을 이유로 Interface / Factory / Adapter / Manager / Helper / Wrapper를 미리 만들지 않는다.

필요하지 않은 Design Pattern을 도입하지 않는다.

현재 규모에서 불필요한 Hexagonal / Clean Architecture 계층을 추가하지 않는다.

새로운 dependency는 기존 기능으로 해결할 수 없는 경우에만 추가한다.

"나중에 필요할 수 있음"만을 이유로 코드를 만들지 않는다.

### 16-7. Dead Code 금지

다음을 남기지 않는다.

- 사용하지 않는 Class
- 사용하지 않는 Method / Function
- 사용하지 않는 Import
- 사용하지 않는 Variable
- 사용하지 않는 CSS Selector
- 사용하지 않는 Asset
- 주석 처리된 Old Code
- 임시 Debug Code
- 의미 없는 TODO / FIXME
- 미래 Phase Placeholder

TODO / FIXME가 반드시 필요한 경우에는 이유를 명확하게 작성한다.

### 16-8. Backend 스타일

Money 값은 BigDecimal을 사용한다.

Entity를 API Request/Response에 직접 노출하지 않는다.

Transaction Boundary는 Service 계층에서 명확하게 관리한다.

Managed Entity에는 필요한 경우 Dirty Checking을 활용하고 불필요한 save를 반복하지 않는다.

상태 변경은 무분별한 Setter보다 의미 있는 Domain Method를 우선한다.

N+1이 예상되는 조회는 명시적으로 처리한다.

동시성 제어가 필요한 Business Rule은 적절한 DB/JPA Lock 또는 Constraint를 사용한다.

Exception은 Business 의미가 드러나는 이름과 Error Code를 사용한다.

### 16-9. Frontend 스타일

HTTP Client는 Browser fetch를 사용한다.

API 호출은 Domain별 API Module에서 관리한다.

Frontend가 서버 Business 값을 임의로 결정하지 않는다.

Backend Response를 최종 상태 기준으로 사용한다.

파생 가능한 값은 불필요하게 별도 State에 저장하지 않는다.

동일 정보를 여러 State에 중복 저장하지 않는다.

useEffect는 외부 시스템 동기화가 필요한 경우에만 사용한다.

불필요한 useMemo / useCallback / Context를 추가하지 않는다.

의미 있는 HTML 요소(button / Link 등)를 사용한다.

Cart 등 필요한 최소 범위 외에 전역 상태를 만들지 않는다.

### 16-10. 테스트 품질

무엇을 테스트할지는 12. Testing Policy를 따르고, 이 항목은 테스트 코드의 품질 기준을 정한다.

테스트는 구현 상세보다 Business Rule과 외부 계약을 검증한다.

테스트끼리 실행 순서에 의존하지 않는다.

테스트 데이터는 다른 테스트에 영향을 주지 않도록 격리한다.

Thread.sleep 기반 flaky concurrency test를 만들지 않는다.

테스트를 통과시키기 위해 Production Code에 테스트 전용 분기를 추가하지 않는다.

반복 Setup이 실제로 과도할 때만 Test Helper를 사용한다.

Test Helper가 테스트의 의미를 숨기지 않아야 한다.

### 16-11. 변경 후 품질 확인

코드를 생성하거나 수정한 뒤 다음을 확인한다.

- Naming Convention과 일치하는가
- Package / Folder 구조와 일치하는가
- Business Logic이 중복되지 않았는가
- Method / Component가 지나치게 커지지 않았는가
- 스파게티 코드가 생기지 않았는가
- 불필요한 Abstraction을 추가하지 않았는가
- Dead Code가 남지 않았는가
- 영어 설명형 주석을 새로 작성하지 않았는가
- 현재 Phase 이후 기능을 선행 구현하지 않았는가
- 기존 Test / Build / Lint를 깨뜨리지 않았는가

---

## 17. Project Status

Phase 1~14가 완료되었다. 1절이 정의한 목표 기술 스택은 모두 도입되었다.

Phase 번호와 기능의 대응은 **코드 주석과 commit message에 남은 것만** 아래에 적는다. Phase
roadmap 문서는 repository에 없으므로, 여기 없는 번호는 추측하지 않는다.

| Phase | 기능 | 근거 |
| --- | --- | --- |
| 1 | Demo Shop core (Product, Order, Payment) | commit `7807655`, `PaymentApiIntegrationTest` |
| 2 | Telemetry Event v1 계약, Kafka Producer / Consumer | `TelemetryPublisher`, `TelemetryEventListener`, `TelemetryEventValidator`, `MetricSeries`, `application.properties` |
| 3 | Redis Rolling Metric, 중복 방지, Metric 계약 확정 | commit `ee8d301`, `RedisMetricStore`, `MetricRecorder`, `IncidentRule` |
| 4 | Incident 탐지 엔진, DetectionState, MySQL 영속성 | `IncidentDetector`, `IncidentActiveUniquenessIntegrationTest` |
| 5 | PulseOps Web 기반 | `PulseOpsBackendApplicationTests` |
| 6 | Kafka 계약 위반 처리와 DLT | `TelemetryConsumerConfiguration`, `TelemetryEventListener` |
| 7 | 처리 멱등성과 재시도 | `TelemetryProcessingService`, `TelemetryConsumerReliabilityIntegrationTest` |
| 9 | Claude AI 분석, GitHub 코드 컨텍스트 | `IncidentGitHubContext*`, `IncidentAiAnalysisMigrationIntegrationTest` |
| 11 | Kafka Consumer Lag 관측 | `ConsumerLagMetrics`, `ConsumerLagMetricsTest` |
| 12 | Prometheus / Grafana 관측 환경 | `IncidentLifecycleTransition`, `PrometheusEndpointIntegrationTest` |
| 13 | Docker Compose, GitHub Actions CI | commit `32338b0` |
| 14 | 성능 검증, 문서화, capability boundary 확정 | `docs/` |

Phase 8과 10은 repository 안에 번호를 명시한 근거가 없다. 해당 구간의 commit은 Incident 조회 API,
ACK 동시성, SSE, Dashboard Frontend, Fault Injection, Daily Report Scheduler이며 모두 구현되어 있다.

Phase 15는 정의되어 있지 않다. 새로운 기능을 요청받기 전까지 선행 구현하지 않는다.

### 현재 Project Boundary

이 프로젝트가 **하는 것**은 위에 적힌 것이 전부이다. 아래는 **하지 않는다.**

- 배포(CD), registry publish, Kubernetes, cloud 배포
- 인증/인가 계층
- 이중화, autoscaling, 자원 한도, backup
- OpenTelemetry, distributed tracing, Loki, ELK, Alertmanager
- AI를 이용한 장애 탐지 또는 자동 복구

14절 Forbidden Changes가 계속 유효하다.

### Capability Boundary

프로젝트를 설명할 때 지켜야 할 경계이다. 근거는 `docs/performance.md`의 실측 결과이다.

**말할 수 있다**

- 로컬 Docker Compose 환경에서 정해진 속도의 부하를 보냈을 때 관측 경로 전체가 동작한다
- Kafka에 publish된 record에 대해 Consumer 처리는 at-least-once이며 Redis dedup이 중복 반영을 막는다
- ERROR_RATE / LATENCY Incident가 의도한 장애에서 열리고 정상 트래픽 복귀 시 종료된다
- 두 종류의 Incident는 독립이며 지연이 오류로 세어지지 않는다

**말할 수 없다**

- 최대 처리량(TPS), 운영 환경 용량, SLA
- production ready, high availability, fault tolerant, zero downtime
- end-to-end exactly-once, guaranteed delivery
- AI가 장애를 탐지하거나 root cause를 확정한다는 표현

### 문서 지도

| 문서 | 내용 |
| --- | --- |
| `README.md` | 포트폴리오 진입점 |
| `docs/architecture.md` | 전체 구조와 경로별 diagram |
| `docs/incident-detection.md` | 판정 기준선과 생명주기 |
| `docs/reliability.md` | 전달 semantics와 failure boundary |
| `docs/ai-github-boundary.md` | AI / GitHub의 역할과 금지 표현 |
| `docs/performance.md` | 부하 검증 실측 결과 |
| `docs/demo-guide.md` | 실습 절차 |
| `docs/troubleshooting.md` | 문제 진단 |
| `docs/portfolio.md` | 설계 결정의 근거 |

문서와 코드가 어긋나면 **코드가 authoritative source**이다. 문서를 코드에 맞춘다.
