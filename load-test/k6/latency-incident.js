import http from 'k6/http'
import { check } from 'k6'
import { Rate } from 'k6/metrics'
import { PRODUCTS_URL, guardTarget } from './lib/common.js'

/**
 * 모든 요청을 3.5초 지연시켜 LATENCY Incident가 실제로 열리는지 확인한다.
 *
 * 이 시나리오의 응답은 200이다. 느린 것과 실패한 것은 다른 일이고, PulseOps의 오류율은 5xx만 센다. 그래서
 * 이 부하는 errorCount를 올리지 않으며, Incident가 열린다면 그것은 오직 P95 때문이다. 그 계약이 깨지면
 * 지연 하나가 두 가지 Incident를 동시에 만들게 된다.
 *
 * 기존 Fault Injection 계약을 그대로 쓴다. 지연 시간은 Demo Shop이 정한 고정값이며 요청이 정하지 않는다.
 *
 * Backend에서 장애 주입을 켜 두어야 한다.
 *
 *   java -jar demo-shop-backend.jar --demo-shop.fault-injection.enabled=true
 *
 * 실행:
 *   k6 run load-test/k6/latency-incident.js
 */

/** 지연이 실제로 주입됐는지를 세는 자리이다. 200만으로는 장애 주입이 켜져 있었는지 알 수 없다. */
const expectedDelay = new Rate('expected_delayed_2xx')

/** Demo Shop이 주입하는 지연은 3500ms이다. 네트워크와 측정 오차를 감안해 조금 아래를 기준으로 본다. */
const MIN_INJECTED_DURATION_MS = 3400

export const options = {
  scenarios: {
    latency_incident: {
      executor: 'constant-arrival-rate',
      /**
       * 503 시나리오보다 훨씬 낮게 잡는다. 요청 하나가 3.5초 동안 Servlet Thread를 붙들고 있으므로,
       * 초당 20건이면 70개가 동시에 묶인다. 여기서 확인할 것은 Demo Shop이 Thread를 몇 개까지 버티는지가
       * 아니라 느린 응답이 P95로 관측되는가이다.
       */
      rate: 6,
      timeUnit: '1s',
      duration: '90s',
      /** 요청 하나가 3.5초를 쓰므로 초당 6건을 내보내려면 최소 21개의 VU가 동시에 필요하다. */
      preAllocatedVUs: 30,
      maxVUs: 60,
    },
  },
  thresholds: {
    /**
     * p(95)에 기준을 걸지 않는다. 3.5초는 이 시험이 만들어 낸 결과이며, 그것을 실패로 보고하면 의도대로
     * 동작한 시험이 빨간색으로 끝난다. 대신 "느린 200이 실제로 나왔는가"를 직접 확인한다.
     */
    expected_delayed_2xx: ['rate>0.99'],
    /** 지연은 오류가 아니다. 이 시나리오에서 5xx가 나오면 그것은 의도하지 않은 실패이다. */
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  guardTarget()
  console.log(`scenario=latency-incident fault=LATENCY_3500 endpoint=GET ${PRODUCTS_URL}`)
}

export default function () {
  const response = http.get(PRODUCTS_URL, {
    headers: { 'X-Demo-Fault': 'LATENCY_3500' },
    tags: { name: 'GET /api/products' },
    /** 기본 timeout은 60초이다. 3.5초 응답을 기다리기에 충분하므로 따로 늘리지 않는다. */
  })

  const delayedSuccess = response.status === 200 && response.timings.duration >= MIN_INJECTED_DURATION_MS
  expectedDelay.add(delayedSuccess)
  check(response, {
    'status is 200': (result) => result.status === 200,
    'took at least 3.4s': (result) => result.timings.duration >= MIN_INJECTED_DURATION_MS,
  })
}
