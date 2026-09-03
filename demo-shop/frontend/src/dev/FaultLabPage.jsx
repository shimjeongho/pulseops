import { useState } from 'react'
import { runFaultBatch, TARGET_PATH } from './faultTraffic.js'

/**
 * Demo Shop에 의도적인 장애를 발생시켜 PulseOps의 탐지 흐름을 확인하는 개발용 화면이다.
 * 일반 사용자를 위한 기능이 아니므로 Shop의 메뉴에 넣지 않고 개발 환경에서만 Route를 연다.
 *
 * 실제 안전 경계는 Backend의 설정이다. 이 화면이 숨겨져 있다는 것은 보호 수단이 아니며,
 * Backend에서 장애 주입을 켜지 않으면 여기서 보낸 요청도 전부 평범한 요청으로 처리된다.
 */

/** Incident 판정에 필요한 최소 요청 수(20)보다 여유를 둔 고정값이다. */
const REQUEST_COUNT = 30

/** 지연 요청 한 건이 3.5초를 잡으므로 한 창 안에 요청이 들어가려면 몇 건은 겹쳐야 한다. */
const CONCURRENCY = 6

/** 지연 장애가 실제로 걸렸다면 batch 전체가 이 시간보다 빨리 끝날 수 없다. */
const LATENCY_EVIDENCE_MS = 3000

const PRESETS = [
  {
    key: 'NORMAL',
    faultType: null,
    label: '정상 요청 발생',
    expectation: '모든 요청이 200으로 끝난다. Metric의 요청 수만 늘고 Incident는 생기지 않는다.',
  },
  {
    key: 'ERROR_503',
    faultType: 'ERROR_503',
    label: '503 오류 요청 발생',
    expectation: '모든 요청이 503으로 끝난다. 여기서의 실패는 의도된 결과이며 오류율을 올린다.',
  },
  {
    key: 'LATENCY_3500',
    faultType: 'LATENCY_3500',
    label: '3.5초 지연 요청 발생',
    expectation: '모든 요청이 200으로 끝나되 응답이 느리다. 오류율은 그대로이고 P95만 올라간다.',
  },
]

function countOf(result, status) {
  return result.statusCounts.find((entry) => entry.status === status)?.count ?? 0
}

/**
 * 장애를 요청했는데 흔적이 없으면 Backend에서 장애 주입이 켜져 있지 않을 가능성이 높다.
 * 결과만 보고 판단하며, 이것을 알아내려고 Backend에 별도의 상태 조회 API를 두지 않는다.
 */
function faultLooksInactive(preset, result) {
  if (preset.faultType === null) {
    return false
  }
  if (preset.faultType === 'ERROR_503') {
    return countOf(result, 503) === 0
  }
  return result.elapsedMs < LATENCY_EVIDENCE_MS
}

function statusLabel(status) {
  return status === 0 ? '연결 실패' : String(status)
}

function BatchResult({ preset, result }) {
  return (
    <>
      <h2 className="section-title">결과 — {preset.label}</h2>
      <div className="panel">
        <dl className="detail-list">
          <div>
            <dt>보낸 요청</dt>
            <dd>{result.total}건</dd>
          </div>
          <div>
            <dt>2xx 응답</dt>
            <dd>{result.succeeded}건</dd>
          </div>
          <div>
            <dt>2xx 아님</dt>
            <dd>{result.failed}건</dd>
          </div>
          <div>
            <dt>소요 시간</dt>
            <dd>{(result.elapsedMs / 1000).toFixed(1)}초</dd>
          </div>
        </dl>
      </div>

      <table className="table">
        <thead>
          <tr>
            <th scope="col">응답 상태</th>
            <th scope="col" className="align-right">
              건수
            </th>
          </tr>
        </thead>
        <tbody>
          {result.statusCounts.map((entry) => (
            <tr key={entry.status}>
              <td>{statusLabel(entry.status)}</td>
              <td className="align-right">{entry.count}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {faultLooksInactive(preset, result) ? (
        <p className="notice notice-warning">
          요청한 장애가 응답에 나타나지 않았습니다. Backend에서 장애 주입이 켜져 있는지 확인해
          주세요.
        </p>
      ) : null}
    </>
  )
}

export function FaultLabPage() {
  const [run, setRun] = useState(null)
  const running = run?.state === 'running'

  async function start(preset) {
    setRun({ preset, state: 'running', result: null })
    const result = await runFaultBatch({
      faultType: preset.faultType,
      requestCount: REQUEST_COUNT,
      concurrency: CONCURRENCY,
    })
    setRun({ preset, state: 'complete', result })
  }

  return (
    <>
      <h1 className="page-title">장애 주입 테스트</h1>
      <p>
        개발 및 Demo 전용 화면입니다. Backend에서 장애 주입을 켰을 때만 실제로 장애가 발생하며,
        켜져 있지 않으면 아래 요청도 평범한 조회 요청으로 처리됩니다.
      </p>

      <div className="panel">
        <dl className="detail-list">
          <div>
            <dt>대상</dt>
            <dd>GET {TARGET_PATH}</dd>
          </div>
          <div>
            <dt>요청 수</dt>
            <dd>{REQUEST_COUNT}건</dd>
          </div>
          <div>
            <dt>동시 실행</dt>
            <dd>{CONCURRENCY}건</dd>
          </div>
          <div>
            <dt>상태</dt>
            <dd>{running ? '실행 중' : '대기'}</dd>
          </div>
        </dl>

        <div className="button-row">
          {PRESETS.map((preset) => (
            <button
              key={preset.key}
              type="button"
              className="button button-primary"
              disabled={running}
              onClick={() => start(preset)}
            >
              {preset.label}
            </button>
          ))}
        </div>

        {run ? <p>{run.preset.expectation}</p> : null}
      </div>

      {run?.state === 'complete' ? <BatchResult preset={run.preset} result={run.result} /> : null}
    </>
  )
}
