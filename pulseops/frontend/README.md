# PulseOps Frontend

PulseOps 운영 Dashboard 화면이다.

Demo Shop에서 관측된 실시간 Metric과 그 위에서 열린 Incident를 한 화면에서 보고, Incident 하나의 판정 근거까지 따라 들어갈 수 있다. 같은 화면에서 PulseOps가 Telemetry Topic을 얼마나 뒤처져 읽고 있는지도 함께 본다.

이 화면은 상태를 만들지 않는다. 무엇이 열려 있고 어떤 상태인지는 전부 Backend가 정하며, 화면은 그것을 읽어서 그린다.

## 요구 환경

- Node.js 20 이상
- PulseOps Backend가 `http://localhost:8081`에서 실행 중이어야 한다

## 설치

```
npm ci
```

## 실행

```
npm run dev
```

Vite Dev Server가 `http://localhost:5174`에서 뜬다. Demo Shop Frontend가 5173을 쓰므로 두 화면을 함께 띄워 둘 수 있다.

## Build

```
npm run build
```

## Lint

```
npm run lint
```

## Backend 연동

API 주소를 하드코딩하지 않고 `/api/metrics`처럼 상대 경로로만 호출한다.

`vite.config.js`에서 `/api` 요청을 `http://localhost:8081`로 Proxy하므로 브라우저 입장에서는 단일 Origin이며, Backend에 별도의 CORS 설정이 필요 없다.

Proxy는 Dev Server 설정이므로 `npm run dev`로 실행할 때만 동작한다. Backend가 떠 있지 않으면 화면에 연결 실패 안내가 표시된다.

## 갱신 방식

Metric은 지금 이 순간의 상태이므로 5초마다 다시 읽는다. 갱신에 실패해도 마지막으로 받은 값을 지우지 않고, 갱신하지 못했다는 사실만 따로 알린다. 잠깐의 실패로 보고 있던 숫자가 사라지는 것이 조금 낡은 값을 보는 것보다 나쁘기 때문이다.

활성 Incident 목록은 화면에 들어올 때 한 번 읽고, 그 뒤에는 새로고침 버튼으로 다시 읽는다. Incident lifecycle이 바뀌었다는 사실은 Backend가 이미 알림으로 내보내고 있으므로 몇 초마다 물어보지 않는다.

Kafka Consumer Lag도 지금 이 순간의 값이라 5초마다 다시 읽는다. Metric과 같은 주기를 쓰지만 타이머를 하나로 묶지 않는다. 두 값은 서로 다른 시스템에서 오고, Broker에 닿지 못해 Lag을 읽지 못하는 것이 Metric과 Incident 화면을 함께 멈추게 해서는 안 된다.

Consumer Lag은 Kafka에 쌓였지만 아직 읽지 않은 Event의 수이다. Event가 처리되는 데 걸리는 시간도, Demo Shop이 아픈 정도도 아니며, Incident 판정에도 쓰이지 않는다. 그리고 지금 이 순간의 값만 보여 주므로 밀림이 늘고 있는지 줄고 있는지는 이 화면으로 말할 수 없다.
