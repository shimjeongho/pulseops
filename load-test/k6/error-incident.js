import http from 'k6/http'
import { check } from 'k6'
import { Rate } from 'k6/metrics'
import { PRODUCTS_URL, guardTarget } from './lib/common.js'

/**
 * 모든 요청에 503을 주입해 ERROR_RATE Incident가 실제로 열리는지 확인한다.
 *
 * Kafka에 Event를 직접 넣지 않는다. 시작점은 언제나 Demo Shop의 HTTP 요청이며, 그래야 TelemetryFilter,
 * Producer, Consumer, Redis, 판정까지 전부가 검증 범위에 들어온다. Event를 Topic에 밀어 넣고 Incident가
 * 열렸다고 말하는 것은 판정 로직만 확인한 것이다.
 *
 * 기존 Fault Injection 계약을 그대로 쓴다. 새 fault 유형을 만들지 않고, Incident 판정 기준도 건드리지
 * 않는다. 시험 편의로 기준선을 낮추면 그 뒤로는 이 시험이 무엇을 확인했는지 말할 수 없다.
 *
 * Backend에서 장애 주입을 켜 두어야 한다. 켜지 않으면 같은 Header를 보내도 평범한 200 조회가 되고, 아래
 * expected_503 기준에서 걸린다.
 *
 *   java -jar demo-shop-backend.jar --demo-shop.fault-injection.enabled=true
 *
 * 실행:
 *   k6 run load-test/k6/error-incident.js
 */

/** 503이 의도대로 나왔는지를 세는 자리이다. 이 시나리오에서 503은 실패가 아니라 기대한 결과이다. */
const expected503 = new Rate('expected_503')

export const options = {
  scenarios: {
    error_incident: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      /**
       * 판정 창은 60초이고 Incident는 이상인 창이 두 번 이어져야 열린다. Bucket이 10초이므로 그 두 번을
       * 채우고도 남을 만큼만 유지한다.
       */
      duration: '90s',
      preAllocatedVUs: 10,
      maxVUs: 40,
    },
  },
  thresholds: {
    /**
     * 503이 100%인 시험이다. 그래서 http_req_failed에 기준을 걸지 않는다. 걸면 의도대로 동작한 시험이
     * 실패로 보고되고, 그 보고를 무시하는 습관이 생긴다. 대신 "기대한 503이 실제로 나왔는가"를 직접
     * 확인한다.
     */
    expected_503: ['rate>0.99'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  guardTarget()
  console.log(`scenario=error-incident fault=ERROR_503 endpoint=GET ${PRODUCTS_URL}`)
}

export default function () {
  const response = http.get(PRODUCTS_URL, {
    headers: { 'X-Demo-Fault': 'ERROR_503' },
    tags: { name: 'GET /api/products' },
  })

  const injected = response.status === 503
  expected503.add(injected)
  check(response, {
    'status is 503': () => injected,
  })
}
