import { fetchActiveIncidents } from '../api/incidents.js'
import { fetchMetrics } from '../api/metrics.js'
import { useLastGoodData } from '../api/useLastGoodData.js'
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
 * Metric과 활성 Incident를 한 화면에 모은다.
 *
 * 두 영역의 상태를 이 Page가 각각 따로 들고 있다. 하나로 합치면 한쪽 요청이 실패했을 때 다른 쪽까지
 * 함께 비워지고, 그러면 Redis가 잠깐 흔들렸을 뿐인데 Incident 목록이 사라진다. 두 API는 서로 다른
 * 저장소를 읽으므로 함께 실패할 이유도 없다.
 *
 * Incident 목록은 주기적으로 다시 읽지 않는다. lifecycle이 바뀌었다는 사실은 Backend가 이미 알림으로
 * 내보내고 있으므로, 그것을 받는 대신 몇 초마다 물어보는 코드를 지금 넣으면 곧 지워야 한다.
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
    </>
  )
}
