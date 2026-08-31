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

## Backend 연동

이 프로젝트는 API 주소를 하드코딩하지 않고 `/api/products`처럼 상대 경로로만 호출한다.

`vite.config.js`에서 `/api` 요청을 `http://localhost:8080`으로 Proxy하므로 브라우저 입장에서는 단일 Origin이며, 백엔드에 별도의 CORS 설정이 필요 없다.

Proxy는 Dev Server 설정이므로 `npm run dev`로 실행할 때만 동작한다. 백엔드가 떠 있지 않으면 화면에 연결 실패 안내가 표시된다.
