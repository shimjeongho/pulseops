import { fetchConsumerLag } from '../api/consumerLag.js'
import { fetchActiveIncidents } from '../api/incidents.js'
import { fetchMetrics } from '../api/metrics.js'
import { useIncidentStream } from '../api/useIncidentStream.js'
import { useLastGoodData } from '../api/useLastGoodData.js'
import { ConsumerLagPanel } from '../components/ConsumerLagPanel.jsx'
import { IncidentSection } from '../components/IncidentSection.jsx'
import { MetricSection } from '../components/MetricSection.jsx'
import { SummaryCard } from '../components/SummaryCard.jsx'
import { formatCount } from '../format.js'

/**
 * Metric은 지금 이 순간의 상태이므로 주기적으로 다시 읽는다. 사람이 화면을 새로 고치는 것보다
 * 빠르게 변하고, 놓친 값을 나중에 되짚을 방법도 없다.
 */
const METRIC_POLLING = { intervalMs: 5000 }

/**
 * Consumer Lag도 지금 이 순간의 값이라 주기로 읽는다. Metric과 같은 주기를 쓰지만 타이머를 하나로 묶지
 * 않는다. 두 값은 서로 다른 시스템에서 오고, 한쪽이 느려질 때 다른 쪽까지 함께 기다리게 할 이유가 없다.
 *
 * 알림으로 바꾸지 않는다. Lag은 Event가 들어오는 내내 계속 달라지는 값이라, 달라질 때마다 밀어 보내면
 * 부하가 클수록 알림도 함께 늘어난다. 관측 화면이 관측 대상의 부하를 따라 커지면 안 된다.
 */
const CONSUMER_LAG_POLLING = { intervalMs: 5000 }

/**
 * Metric과 활성 Incident, 그리고 Kafka Consumer Lag을 한 화면에 모은다.
 *
 * 세 영역의 상태를 이 Page가 각각 따로 들고 있다. 하나로 합치면 한쪽 요청이 실패했을 때 다른 쪽까지
 * 함께 비워지고, 그러면 Redis가 잠깐 흔들렸을 뿐인데 Incident 목록이 사라진다. 세 API는 서로 다른
 * 저장소와 시스템을 읽으므로 함께 실패할 이유도 없다. Broker에 닿지 못해 Lag을 읽지 못하는 것이 Metric과
 * Incident 화면을 함께 멈추게 해서는 안 된다.
 *
 * 다시 읽는 방법도 둘이 다르다. Metric은 쉬지 않고 변하는 값이라 주기로 읽고, Incident는 lifecycle이
 * 바뀌는 순간에만 달라지므로 Backend가 바뀌었다고 알릴 때 읽는다. Incident까지 주기로 읽으면 아무것도
 * 달라지지 않은 목록을 몇 초마다 다시 만들게 되고, Metric을 알림에 맡기면 알림 하나에 두 가지 일이
 * 걸려 어느 쪽 때문에 다시 읽는지가 흐려진다.
 */
export function DashboardPage() {
  const {
    data: metrics,
    error: metricsError,
    loading: metricsLoading,
  } = useLastGoodData(fetchMetrics, METRIC_POLLING)

  const {
    data: incidents,
    error: incidentsError,
    loading: incidentsLoading,
    reload: reloadIncidents,
  } = useLastGoodData(fetchActiveIncidents)

  const {
    data: consumerLag,
    error: consumerLagError,
    loading: consumerLagLoading,
  } = useLastGoodData(fetchConsumerLag, CONSUMER_LAG_POLLING)

  // 어느 Incident가 바뀌었는지는 보지 않는다. 이 목록은 처리 중인 것 전부라 무엇이 바뀌든 다시
  // 읽어야 할 것은 같은 목록이고, 알림만 보고 목록의 한 줄을 고치면 그 줄만 최신인 목록이 된다.
  // 알림에는 상태도 심각도도 없어 애초에 고쳐 넣을 값도 없다.
  useIncidentStream({ onConnected: reloadIncidents, onIncidentChanged: reloadIncidents })

  // 지금 있는 응답에서 셀 수 있는 것만 센다. 여러 계열의 오류율이나 P95를 하나로 합치면 어떤 계열도
  // 뜻하지 않는 숫자가 된다.
  const criticalCount = incidents
    ? incidents.filter((incident) => incident.severity === 'CRITICAL').length
    : null

  return (
    <>
      <h1 className="page-title">Dashboard</h1>
      <section className="summary" aria-label="요약">
        <SummaryCard label="관측 중인 계열" value={formatCount(metrics ? metrics.items.length : null)} />
        <SummaryCard label="활성 Incident" value={formatCount(incidents ? incidents.length : null)} />
        <SummaryCard
          label="심각 Incident"
          value={formatCount(criticalCount)}
          tone={criticalCount > 0 ? 'critical' : 'neutral'}
        />
      </section>
      <MetricSection metrics={metrics} error={metricsError} loading={metricsLoading} />
      <IncidentSection
        incidents={incidents}
        error={incidentsError}
        loading={incidentsLoading}
        onRefresh={reloadIncidents}
      />
      <ConsumerLagPanel lag={consumerLag} error={consumerLagError} loading={consumerLagLoading} />
    </>
  )
}
