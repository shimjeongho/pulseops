import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { acknowledgeIncident, fetchIncident } from '../api/incidents.js'
import { useIncidentStream } from '../api/useIncidentStream.js'
import { DetailField } from '../components/DetailField.jsx'
import { EmptyState } from '../components/EmptyState.jsx'
import { ErrorMessage } from '../components/ErrorMessage.jsx'
import { IncidentAcknowledgeAction } from '../components/IncidentAcknowledgeAction.jsx'
import { IncidentAiAnalysisSection } from '../components/IncidentAiAnalysisSection.jsx'
import { IncidentCodeAnalysisSection } from '../components/IncidentCodeAnalysisSection.jsx'
import { IncidentStatusBadge } from '../components/IncidentStatusBadge.jsx'
import { Loading } from '../components/Loading.jsx'
import { SeverityBadge } from '../components/SeverityBadge.jsx'
import { Timestamp } from '../components/Timestamp.jsx'
import { formatCount, formatErrorRate, formatLatencyMs, incidentTypeLabel } from '../format.js'

const NOT_FOUND = 404

/** 확인 처리로 넘어갈 수 없는 상태였다. 지금 상태가 무엇인지는 이 응답이 아니라 다시 읽어야 안다. */
const CONFLICT = 409

/**
 * Incident 하나를 보여 주고 확인 처리한다.
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
  const [reloadCount, setReloadCount] = useState(0)
  const [acknowledgement, setAcknowledgement] = useState(null)

  /**
   * 지금 화면에 써도 되는 결과인지 가리는 표식이다.
   *
   * 이 화면에서 상세를 쓰는 길은 둘이다. 하나는 조회이고 하나는 확인 처리 응답이며, 둘은 동시에
   * 진행될 수 있다. 알림을 받아 다시 읽는 사이에 확인 처리가 끝나면 늦게 도착한 조회가 나중에 확정된
   * 상태를 덮는다. 그래서 결과를 쓰기 직전에 자기 표식이 아직 최신인지 확인하고, 새 결과를 쓰는 쪽이
   * 표식을 새로 잡아 진행 중이던 것을 무효로 만든다.
   *
   * 표식에 어느 Incident의 것인지도 담는다. 확인 처리 응답이 늦게 도착했을 때 그 사이 다른 Incident로
   * 옮겨 간 화면의 조회까지 무효로 만들면, 그 화면은 오지 않을 결과를 기다리며 멈춘다.
   */
  const latestRef = useRef(null)

  const reload = useCallback(() => setReloadCount((count) => count + 1), [])

  useEffect(() => {
    const controller = new AbortController()
    const token = { incidentId }
    latestRef.current = token

    fetchIncident(incidentId, controller.signal).then(
      (incident) => {
        if (latestRef.current === token) {
          setLoaded({ incidentId, incident, error: null })
        }
      },
      (error) => {
        // 취소는 화면을 벗어났다는 뜻이므로 실패로 남기지 않는다
        if (latestRef.current === token && error.name !== 'AbortError') {
          setLoaded({ incidentId, incident: null, error })
        }
      },
    )

    return () => {
      latestRef.current = null
      controller.abort()
    }
  }, [incidentId, reloadCount])

  // 이 화면이 보여 주는 것은 하나뿐이므로 그 하나가 바뀌었을 때만 다시 읽는다. 나머지 알림에
  // 반응하면 아무것도 달라지지 않을 조회만 늘어난다. 알림의 ID는 숫자이고 주소의 ID는 문자열이다.
  const handleIncidentChanged = useCallback(
    (changedIncidentId) => {
      if (String(changedIncidentId) === incidentId) {
        reload()
      }
    },
    [incidentId, reload],
  )

  // 다시 이어진 순간에는 이 Incident를 무조건 다시 읽는다. 끊겨 있던 동안 다른 사람의 확인이나
  // Detector의 해결을 놓쳤을 수 있고, 놓친 것이 있는지는 다시 읽어 봐야만 안다.
  useIncidentStream({ onConnected: reload, onIncidentChanged: handleIncidentChanged })

  // 들고 있는 결과가 어느 Incident의 것인지 함께 기억한다. 주소만 바뀌고 Component는 그대로
  // 남는 이동에서 앞 Incident의 값을 잠깐 보여 주지 않기 위해서이며, 결과를 지우는 대신 무엇의
  // 결과인지 비교하면 화면을 그리는 동안 상태를 되돌릴 일이 없다. 확인 처리의 진행과 실패도 같은
  // 이유로 같은 방식을 쓴다. 앞 Incident에서 실패한 확인은 다음 Incident의 이야기가 아니다.
  const result = loaded?.incidentId === incidentId ? loaded : null
  const action = acknowledgement?.incidentId === incidentId ? acknowledgement : null
  const pending = action?.pending ?? false

  /**
   * 확인 처리 결과를 상세에 쓰고 진행 중이던 조회를 무효로 만든다.
   *
   * 그 사이 다른 Incident로 옮겨 갔으면 아무것도 하지 않는다. 늦게 도착한 응답이 지금 보고 있는
   * Incident의 자리를 차지하면 안 된다.
   */
  function applyDetail(target, detail) {
    if (latestRef.current?.incidentId !== target) {
      return
    }
    latestRef.current = { incidentId: target }
    setLoaded({ incidentId: target, ...detail })
  }

  /**
   * 확인 처리를 요청하고 그 응답을 그대로 화면에 쓴다.
   *
   * 상태를 화면이 만들지 않는다. Backend가 갱신된 상세를 통째로 돌려주므로 그 본문이 곧 그 시점의
   * 확정된 상태이고, 화면이 ACKNOWLEDGED를 직접 써 넣으면 그 사이 Detector가 올린 심각도를 잃는다.
   *
   * 알림이 오기를 기다리지도 않는다. 알림은 유실될 수 있고, 눌렀는데 아무 일도 일어나지 않는 시간이
   * 생기면 사람은 한 번 더 누른다. 뒤이어 알림이 와서 다시 읽는 것은 같은 상태를 확인하는 일이다.
   */
  async function handleAcknowledge() {
    // 버튼을 잠그는 것만으로는 부족하다. 화면이 다시 그려지기 전의 두 번째 누름은 여기서 막는다.
    if (pending) {
      return
    }

    const target = incidentId
    setAcknowledgement({ incidentId: target, pending: true, error: null })
    try {
      const incident = await acknowledgeIncident(target)
      applyDetail(target, { incident, error: null })
      setAcknowledgement({ incidentId: target, pending: false, error: null })
    } catch (error) {
      // 확인하려던 Incident가 이미 없다. 실패만 곁들여 알리는 대신 상세 자체를 없음으로 바꾼다.
      // 사라진 Incident를 계속 정상 상태로 그리면서 그 옆에 실패만 붙이면, 화면이 아직 있는 것을
      // 확인하지 못했다고 말하게 된다.
      if (error.status === NOT_FOUND) {
        setAcknowledgement(null)
        applyDetail(target, { incident: null, error })
        return
      }

      // 나머지 실패에서는 보고 있던 Incident를 그대로 둔다. 확인에 실패한 것과 Incident를 읽지 못한
      // 것은 다른 일이고, 하나로 묶으면 확인 한 번 실패했다고 근거를 보던 화면이 통째로 사라진다.
      setAcknowledgement({ incidentId: target, pending: false, error })

      // 상태가 이미 달라져 거절됐다면 다시 읽는다. 그 응답이 알려 주는 것은 지금 상태로는 안 된다는
      // 사실뿐이고, 지금 상태가 무엇인지는 알려 주지 않는다.
      if (error.status === CONFLICT) {
        reload()
      }
    }
  }

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

      <IncidentAcknowledgeAction
        status={incident.status}
        pending={pending}
        error={action?.error ?? null}
        onAcknowledge={handleAcknowledge}
      />

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

      {/* 판정 근거 아래에 둔다. 관측된 사실을 먼저 보여 준 뒤에 그것을 읽은 추론이 와야 한다. */}
      <IncidentAiAnalysisSection incidentId={incidentId} />

      {/*
        기본 분석 아래에 둔다. 두 분석은 근거가 다르고, 판정 근거만 본 쪽을 먼저 읽어야 코드 맥락을
        더한 것이 무엇을 바꿨는지 알 수 있다. 기본 분석을 이것으로 대체하지 않는 이유도 같다.
      */}
      <IncidentCodeAnalysisSection incidentId={incidentId} />
    </>
  )
}
