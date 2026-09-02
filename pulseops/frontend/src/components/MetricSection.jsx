import { EmptyState } from './EmptyState.jsx'
import { ErrorMessage } from './ErrorMessage.jsx'
import { Loading } from './Loading.jsx'
import { MetricTable } from './MetricTable.jsx'
import { StaleNotice } from './StaleNotice.jsx'
import { Timestamp } from './Timestamp.jsx'

export function MetricSection({ metrics, error, loading }) {
  // 한 응답의 모든 계열은 같은 순간을 기준으로 잘린 창을 쓰므로 구간을 줄마다 반복하지 않는다.
  const window = metrics?.items[0] ?? null

  let body
  if (!metrics) {
    body = loading ? <Loading label="Metric을 불러오는 중입니다..." /> : <ErrorMessage error={error} />
  } else {
    body = (
      <>
        {error ? <StaleNotice title="최신 Metric을 갱신하지 못했습니다." error={error} /> : null}
        {metrics.items.length === 0 ? (
          <EmptyState
            title="현재 수집된 Metric이 없습니다."
            description="Demo Shop에 요청이 들어오면 관측된 API가 여기에 나타납니다."
          />
        ) : (
          <MetricTable items={metrics.items} />
        )}
      </>
    )
  }

  return (
    <section className="panel" aria-labelledby="metric-heading">
      <div className="panel-head">
        <h2 id="metric-heading" className="panel-title">
          실시간 Metric
        </h2>
        {metrics ? (
          <p className="panel-note">
            기준 시각 <Timestamp value={metrics.generatedAt} />
            {window ? (
              <>
                {' · 관측 구간 '}
                <Timestamp value={window.windowStart} /> ~ <Timestamp value={window.windowEnd} />
              </>
            ) : null}
          </p>
        ) : null}
      </div>
      {body}
    </section>
  )
}
