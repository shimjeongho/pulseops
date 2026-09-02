import { formatCount, formatErrorRate, formatLatencyMs } from '../format.js'

/** 계열을 하나로 정하는 값은 Backend와 같이 service, method, endpoint 셋이다. */
function seriesKey(item) {
  return `${item.service}|${item.method}|${item.endpoint}`
}

export function MetricTable({ items }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Service</th>
            <th scope="col">Method</th>
            <th scope="col">Endpoint</th>
            <th scope="col" className="numeric">
              요청
            </th>
            <th scope="col" className="numeric">
              오류
            </th>
            <th scope="col" className="numeric">
              오류율
            </th>
            <th scope="col" className="numeric">
              P95
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            // 오류가 하나라도 있었다는 사실만 표시한다. 몇 %부터 문제인지 정하는 것은 Backend의
            // 판정 규칙이며, 화면이 자기 기준을 따로 두면 두 기준이 갈라진다.
            const errorClass = item.errorCount > 0 ? 'numeric numeric-alert' : 'numeric'
            return (
              <tr key={seriesKey(item)}>
                <td>{item.service}</td>
                <td>
                  <span className="method">{item.method}</span>
                </td>
                <td className="endpoint">{item.endpoint}</td>
                <td className="numeric">{formatCount(item.requestCount)}</td>
                <td className={errorClass}>{formatCount(item.errorCount)}</td>
                <td className={errorClass}>{formatErrorRate(item.errorRate)}</td>
                <td className="numeric">{formatLatencyMs(item.p95LatencyMs)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
