import http from 'k6/http'
import { check } from 'k6'
import { PRODUCTS_URL, guardTarget } from './lib/common.js'

/**
 * 장애 없이 정상 요청만 흘려보낸다.
 *
 * 확인하려는 것은 처리량의 한계가 아니라 관측 경로가 실제로 이어져 있는가이다. Demo Shop의 HTTP 요청이
 * TelemetryFilter를 지나 Kafka로 나가고, PulseOps Consumer가 그것을 읽어 Redis에 Metric을 쌓기까지가
 * 한 줄로 동작하면 이 시나리오는 할 일을 다 한 것이다.
 *
 * 그리고 그 과정에서 Incident가 열리지 않아야 한다. 정상 트래픽만으로 Incident가 생긴다면 판정 기준이
 * 잘못된 것이다.
 *
 * 실행:
 *   k6 run load-test/k6/baseline.js
 */
export const options = {
  scenarios: {
    baseline: {
      /**
       * VU 수가 아니라 초당 요청 수로 부하를 정한다. VU를 고정하면 서버가 빨라질수록 부하도 함께
       * 커져서, 실행할 때마다 서로 다른 양의 요청을 보내게 된다. 재현 가능한 부하가 목적이므로 보내는
       * 쪽의 속도를 우리가 정한다.
       */
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      /** 3.5초 지연 시나리오와 달리 응답이 빨라 VU가 많이 필요하지 않다. */
      preAllocatedVUs: 10,
      maxVUs: 40,
      stages: [
        // JVM이 아직 덥혀지지 않은 구간의 응답 시간을 본 시험에 섞지 않는다
        { target: 20, duration: '15s' },
        // 판정 창 60초를 여러 번 채울 만큼만 유지한다
        { target: 20, duration: '90s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    /** 정상 트래픽이므로 실패는 사실상 0이어야 한다. */
    http_req_failed: ['rate<0.01'],
    /**
     * PulseOps의 LATENCY WARNING 기준선이 1500ms이다. 이 시험이 그것을 넘겼다면 정상 부하만으로도
     * LATENCY Incident가 열릴 수 있다는 뜻이라, 그때는 baseline이라고 부를 수 없다.
     */
    http_req_duration: ['p(95)<1500'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  guardTarget()
  console.log(`scenario=baseline endpoint=GET ${PRODUCTS_URL}`)
}

export default function () {
  const response = http.get(PRODUCTS_URL, { tags: { name: 'GET /api/products' } })

  check(response, {
    'status is 200': (result) => result.status === 200,
  })
}
