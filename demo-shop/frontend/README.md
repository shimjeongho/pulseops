# Demo Shop Frontend

PulseOps 관측 대상 애플리케이션인 Demo Shop의 화면이다.

상품 조회부터 장바구니, 주문 생성, 가상 결제까지의 흐름을 제공한다.

장바구니는 Browser localStorage에만 존재하며 화면 표시용이다. 주문 금액과 결제 금액은 항상 백엔드가 결정한다.

## 요구 환경

- Node.js 20 이상
- Demo Shop Backend가 `http://localhost:8080`에서 실행 중이어야 한다

## 설치

```
npm ci
```

## 실행

```
npm run dev
```

Vite Dev Server가 `http://localhost:5173`에서 뜬다.

## Build

```
npm run build
```

## Lint

```
npm run lint
```

## 장애 주입 테스트 (개발 전용)

PulseOps의 장애 탐지 흐름을 눈으로 확인하기 위한 `/dev/faults` 화면이 있다. 개발 및 Demo 전용이므로 Shop 메뉴에 넣지 않으며, Production build에는 이 Route가 포함되지 않는다.

버튼 하나가 `GET /api/products`로 고정된 수의 요청을 보낸다.

- 정상 요청 발생 — Header 없이 보낸다
- 503 오류 요청 발생 — `X-Demo-Fault: ERROR_503`
- 3.5초 지연 요청 발생 — `X-Demo-Fault: LATENCY_3500`

요청 수와 동시 실행 수는 고정이다. 부하 시험 도구가 아니라 Incident 판정에 필요한 최소한의 요청을 만드는 용도다.

실제로 장애가 발생하려면 Backend에서 장애 주입을 켜야 한다. 기본값은 비활성이며, 화면을 감추는 것이 아니라 이 Backend 설정이 안전 경계다.

```
java -jar demo-shop-backend.jar --demo-shop.fault-injection.enabled=true
```

켜지 않은 Backend에서는 같은 Header를 보내도 평범한 조회 요청으로 처리되고, 화면에는 장애가 나타나지 않았다는 안내가 표시된다.

## Backend 연동

이 프로젝트는 API 주소를 하드코딩하지 않고 `/api/products`처럼 상대 경로로만 호출한다.

`vite.config.js`에서 `/api` 요청을 `http://localhost:8080`으로 Proxy하므로 브라우저 입장에서는 단일 Origin이며, 백엔드에 별도의 CORS 설정이 필요 없다.

Proxy는 Dev Server 설정이므로 `npm run dev`로 실행할 때만 동작한다. 백엔드가 떠 있지 않으면 화면에 연결 실패 안내가 표시된다.
