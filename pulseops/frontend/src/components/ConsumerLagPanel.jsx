import { formatCount } from '../format.js'
import { ConsumerLagTable } from './ConsumerLagTable.jsx'
import { EmptyState } from './EmptyState.jsx'
import { ErrorMessage } from './ErrorMessage.jsx'
import { Loading } from './Loading.jsx'
import { StaleNotice } from './StaleNotice.jsx'
import { Timestamp } from './Timestamp.jsx'

/**
 * PulseOps가 Telemetry Topic을 얼마나 뒤처져 읽고 있는지를 보여 준다.
 *
 * 이 숫자는 Kafka에 쌓였지만 아직 읽지 않은 Event의 수이다. Demo Shop이 아픈 정도도, Event가 처리되는
 * 데 걸리는 시간도 아니며, Incident 판정에도 쓰이지 않는다. Incident는 Demo Shop이 실제로 돌려준
 * 응답으로만 열린다.
 *
 * 지금 이 순간의 값만 보여 준다. 이전 값과 비교하지 않으므로 밀림이 늘고 있는지 줄고 있는지는 이
 * 화면으로 말할 수 없다.
 *
 * 조회만 있다. offset을 되돌리거나 Consumer를 멈추는 조작을 두지 않는다. 관측 화면 하나가 Broker
 * 상태를 바꿀 수 있게 되면 그것은 더 이상 관측 화면이 아니다.
 */
export function ConsumerLagPanel({ lag, error, loading }) {
  let body
  if (!lag) {
    body = loading ? (
      <Loading label="Consumer Lag을 불러오는 중입니다..." />
    ) : (
      <ErrorMessage error={error} />
    )
  } else {
    body = (
      <>
        {error ? <StaleNotice title="Consumer Lag을 갱신하지 못했습니다." error={error} /> : null}
        {lag.uninitializedPartitionCount > 0 ? (
          <div className="notice" role="status">
            <p className="notice-title">
              아직 한 번도 읽지 않은 partition이 {formatCount(lag.uninitializedPartitionCount)}개 있습니다.
            </p>
            <p className="notice-detail">
              그 partition은 밀린 정도를 알 수 없어 합계에서 빠져 있습니다. 합계만으로 전체가 따라잡았다고
              볼 수 없습니다.
            </p>
          </div>
        ) : null}
        {lag.partitions.length === 0 ? (
          <EmptyState
            title="Topic에 partition이 없습니다."
            description="Demo Shop이 Telemetry Topic을 만들면 여기에 나타납니다."
          />
        ) : (
          <ConsumerLagTable partitions={lag.partitions} />
        )}
      </>
    )
  }

  return (
    <section className="panel" aria-labelledby="consumer-lag-heading">
      <div className="panel-head">
        <h2 id="consumer-lag-heading" className="panel-title">
          Kafka Consumer Lag
        </h2>
        {lag ? (
          <p className="panel-note">
            {`밀린 Event ${formatCount(lag.totalLag)}건 · partition ${formatCount(lag.partitionCount)}개 · 기준 시각 `}
            <Timestamp value={lag.generatedAt} />
          </p>
        ) : null}
      </div>
      {body}
    </section>
  )
}
