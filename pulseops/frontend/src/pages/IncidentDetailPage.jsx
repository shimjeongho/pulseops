import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { fetchIncident } from '../api/incidents.js'
import { DetailField } from '../components/DetailField.jsx'
import { EmptyState } from '../components/EmptyState.jsx'
import { ErrorMessage } from '../components/ErrorMessage.jsx'
import { IncidentStatusBadge } from '../components/IncidentStatusBadge.jsx'
import { Loading } from '../components/Loading.jsx'
import { SeverityBadge } from '../components/SeverityBadge.jsx'
import { Timestamp } from '../components/Timestamp.jsx'
import { formatCount, formatErrorRate, formatLatencyMs, incidentTypeLabel } from '../format.js'

const NOT_FOUND = 404

/**
 * Incident 하나를 읽기 전용으로 보여 준다.
 *
 * 목록의 상태를 넘겨받지 않고 언제나 다시 조회한다. 주소만으로 열 수 있어야 하고, 끝난 Incident는
 * 목록에 아예 없으므로 넘겨받을 상태도 없다. 무엇이 참인지는 URL과 Backend가 정한다.
 *
 * trigger로 시작하는 값들은 이 Incident를 연 창의 관측값이며 지금의 값이 아니다. 그래서 창의 경계와
 * 함께 한 자리에 묶어 두어, 화면 위쪽의 현재 상태와 섞이지 않게 한다.
 */
export function IncidentDetailPage() {
  const { incidentId } = useParams()
  const [loaded, setLoaded] = useState(null)

  useEffect(() => {
    let cancelled = false
    const controller = new AbortController()

    fetchIncident(incidentId, controller.signal).then(
      (incident) => {
        if (!cancelled) {
          setLoaded({ incidentId, incident, error: null })
        }
      },
      (error) => {
        // 취소는 화면을 벗어났다는 뜻이므로 실패로 남기지 않는다
        if (!cancelled && error.name !== 'AbortError') {
          setLoaded({ incidentId, incident: null, error })
        }
      },
    )

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [incidentId])

  // 들고 있는 결과가 어느 Incident의 것인지 함께 기억한다. 주소만 바뀌고 Component는 그대로
  // 남는 이동에서 앞 Incident의 값을 잠깐 보여 주지 않기 위해서이며, 결과를 지우는 대신 무엇의
  // 결과인지 비교하면 화면을 그리는 동안 상태를 되돌릴 일이 없다.
  const result = loaded?.incidentId === incidentId ? loaded : null

  const backLink = (
    <p className="back-link">
      <Link to="/">← Dashboard</Link>
    </p>
  )

  if (!result) {
    return (
      <>
        {backLink}
        <Loading label="Incident를 불러오는 중입니다..." />
      </>
    )
  }

  if (result.error) {
    return (
      <>
        {backLink}
        {result.error.status === NOT_FOUND ? (
          <EmptyState title="Incident를 찾을 수 없습니다." description="주소의 Incident 번호를 다시 확인해 주세요." />
        ) : (
          <ErrorMessage error={result.error} />
        )}
      </>
    )
  }

  const incident = result.incident

  return (
    <>
      {backLink}
      <div className="detail-head">
        <h1 className="page-title">Incident #{incident.id}</h1>
        <div className="detail-badges">
          <SeverityBadge severity={incident.severity} />
          <IncidentStatusBadge status={incident.status} />
          <span className="incident-type">{incidentTypeLabel(incident.type)}</span>
        </div>
      </div>

      <section className="panel" aria-labelledby="target-heading">
        <h2 id="target-heading" className="panel-title">
          대상
        </h2>
        <dl className="detail-grid">
          <DetailField label="Service">{incident.service}</DetailField>
          <DetailField label="Method">
            <span className="method">{incident.method}</span>
          </DetailField>
          <DetailField label="Endpoint">
            <span className="endpoint">{incident.endpoint}</span>
          </DetailField>
        </dl>
      </section>

      <section className="panel" aria-labelledby="lifecycle-heading">
        <h2 id="lifecycle-heading" className="panel-title">
          진행
        </h2>
        <dl className="detail-grid">
          <DetailField label="발생">
            <Timestamp value={incident.openedAt} />
          </DetailField>
          <DetailField label="확인">
            <Timestamp value={incident.acknowledgedAt} />
          </DetailField>
          <DetailField label="해결">
            <Timestamp value={incident.resolvedAt} />
          </DetailField>
        </dl>
      </section>

      <section className="panel" aria-labelledby="evidence-heading">
        <h2 id="evidence-heading" className="panel-title">
          판정 근거
        </h2>
        <p className="panel-note">이 Incident를 연 창의 관측값이며 지금의 값이 아니다.</p>
        <dl className="detail-grid">
          <DetailField label="관측 구간">
            <Timestamp value={incident.triggerWindowStart} /> ~ <Timestamp value={incident.triggerWindowEnd} />
          </DetailField>
          <DetailField label="요청 수">{formatCount(incident.triggerRequestCount)}</DetailField>
          <DetailField label="오류 수">{formatCount(incident.triggerErrorCount)}</DetailField>
          <DetailField label="오류율">{formatErrorRate(incident.triggerErrorRate)}</DetailField>
          <DetailField label="P95 응답 시간">{formatLatencyMs(incident.triggerP95LatencyMs)}</DetailField>
        </dl>
      </section>
    </>
  )
}
