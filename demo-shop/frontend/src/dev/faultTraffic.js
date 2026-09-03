/**
 * Fault Lab 전용 요청 생성기이다. 개발 환경에서만 불러오며 일반 Shop 화면은 사용하지 않는다.
 *
 * Incident는 창 하나에 요청이 충분히 쌓여야 판정되므로 버튼 한 번에 정해진 수의 요청을 보낸다.
 * 부하 시험 도구가 아니라 Incident를 재현하기 위한 최소한의 요청 생성기이며, 요청 수와 동시
 * 실행 수는 호출부가 정한 고정값만 받는다. 사용자가 임의의 값을 넣을 수 있게 하면 Demo용
 * 화면이 서비스에 부하를 거는 통로가 된다.
 */

/** 장애를 주입할 대상이다. 데이터를 바꾸지 않아 몇 번을 반복해도 안전한 조회 API를 쓴다. */
export const TARGET_PATH = '/api/products'

const FAULT_HEADER = 'X-Demo-Fault'

/**
 * Backend는 Header가 없으면 평소대로 처리한다. 그래서 정상 요청은 Header를 아예 붙이지 않고
 * 보내며, 이것이 Fault Lab을 거친 요청과 일반 사용자의 요청을 같은 것으로 만든다.
 */
async function sendOne(faultType) {
  const headers = { Accept: 'application/json' }
  if (faultType !== null) {
    headers[FAULT_HEADER] = faultType
  }

  try {
    // Browser는 같은 URL의 GET 응답을 캐시하려고 앞선 요청이 끝날 때까지 뒤의 요청을 붙잡아
    // 둔다. 그러면 아래에서 정한 동시 실행 수가 실제로는 1이 되어, 지연 장애 batch가 몇 배로
    // 길어지고 요청이 어느 창에 들어갈지도 예측할 수 없다.
    const response = await fetch(TARGET_PATH, { method: 'GET', headers, cache: 'no-store' })
    // 본문을 읽어야 연결이 반환된다. 읽지 않고 두면 다음 요청이 연결을 기다리며 밀린다.
    await response.text()
    return response.status
  } catch {
    // 네트워크 실패는 HTTP 상태가 없다. 0으로 모아 두고 화면에서 따로 표시한다.
    return 0
  }
}

function summarize(statusCounts, total, elapsedMs) {
  let succeeded = 0
  for (const [status, count] of statusCounts) {
    if (status >= 200 && status < 300) {
      succeeded += count
    }
  }

  return {
    total,
    succeeded,
    failed: total - succeeded,
    elapsedMs,
    statusCounts: [...statusCounts.entries()]
      .sort(([left], [right]) => left - right)
      .map(([status, count]) => ({ status, count })),
  }
}

/**
 * 요청을 정해진 수만큼 보내고 결과를 모아 돌려준다.
 *
 * 전부 동시에 보내지 않는다. 지연 장애 요청은 한 건이 3.5초를 잡고 있어 한꺼번에 띄우면
 * Browser의 연결 한도에 걸려 실제로는 순서대로 처리되고, 그러면 요청이 어느 창에 들어갈지
 * 예측할 수 없게 된다.
 */
export async function runFaultBatch({ faultType, requestCount, concurrency }) {
  const statusCounts = new Map()
  const startedAt = performance.now()
  let claimed = 0

  async function worker() {
    while (claimed < requestCount) {
      claimed += 1
      const status = await sendOne(faultType)
      statusCounts.set(status, (statusCounts.get(status) ?? 0) + 1)
    }
  }

  await Promise.all(Array.from({ length: concurrency }, worker))

  return summarize(statusCounts, requestCount, Math.round(performance.now() - startedAt))
}
