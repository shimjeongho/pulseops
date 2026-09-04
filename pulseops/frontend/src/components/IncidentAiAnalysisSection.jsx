import { useEffect, useRef, useState } from 'react'
import { createIncidentAnalysis, fetchIncidentAnalysis } from '../api/incidents.js'
import { AiAnalysisResult } from './AiAnalysisResult.jsx'
import { ErrorMessage } from './ErrorMessage.jsx'
import { Loading } from './Loading.jsx'

/** 아직 분석이 없다는 답이다. 화면에서는 실패가 아니라 생성 버튼을 그릴 조건이다. */
const NOT_FOUND = 404

/**
 * Incident 하나에 대한 AI 사후 분석을 보여 주고, 없으면 만들 수 있게 한다.
 *
 * 들어올 때 자동으로 만들지 않는다. 읽기만 하고, 만드는 것은 사람이 눌렀을 때뿐이다. 화면을 여는 것
 * 만으로 외부 API 요금이 붙으면 안 되고, 어떤 Incident를 분석할 가치가 있는지 아는 것은 사람이다.
 *
 * 상세 화면의 상태를 넘겨받지 않고 스스로 읽는다. Incident 상세는 알림을 받을 때마다 다시 읽지만 분석은
 * 한 번 만들어지면 바뀌지 않으므로, 둘을 한 상태에 묶으면 알림이 올 때마다 바뀌지 않는 값을 다시 읽게
 * 된다. 여기서 다시 읽는 것은 보고 있는 Incident가 바뀔 때뿐이다.
 */
export function IncidentAiAnalysisSection({ incidentId }) {
  const [loaded, setLoaded] = useState(null)
  const [generation, setGeneration] = useState(null)

  /**
   * 지금 화면에 써도 되는 결과인지 가리는 표식이다.
   *
   * 조회와 생성이 동시에 진행될 수 있고, 그 사이 다른 Incident로 옮겨 갈 수도 있다. 늦게 도착한 결과가
   * 지금 보고 있는 Incident의 자리를 차지하지 않도록, 결과를 쓰기 직전에 자기 표식이 아직 최신인지
   * 확인한다. Incident 상세 화면이 쓰는 것과 같은 방식이다.
   */
  const latestRef = useRef(null)

  useEffect(() => {
    const controller = new AbortController()
    const token = { incidentId }
    latestRef.current = token

    fetchIncidentAnalysis(incidentId, controller.signal).then(
      (analysis) => {
        if (latestRef.current === token) {
          setLoaded({ incidentId, analysis, error: null })
        }
      },
      (error) => {
        // 취소는 화면을 벗어났다는 뜻이므로 실패로 남기지 않는다
        if (latestRef.current !== token || error.name === 'AbortError') {
          return
        }
        // 분석이 아직 없는 것은 실패가 아니다. 결과도 실패도 없는 상태로 두어 생성 버튼을 그린다.
        setLoaded({ incidentId, analysis: null, error: error.status === NOT_FOUND ? null : error })
      },
    )

    return () => {
      latestRef.current = null
      controller.abort()
    }
  }, [incidentId])

  // 들고 있는 결과가 어느 Incident의 것인지 함께 기억한다. 주소만 바뀌고 Component는 그대로 남는
  // 이동에서 앞 Incident의 분석을 잠깐 보여 주지 않기 위해서이다.
  const result = loaded?.incidentId === incidentId ? loaded : null
  const action = generation?.incidentId === incidentId ? generation : null
  const pending = action?.pending ?? false

  /**
   * 분석을 만들고 그 응답을 그대로 화면에 쓴다.
   *
   * 만든 뒤 다시 읽지 않는다. Backend가 만들어진 분석을 통째로 돌려주므로 그 본문이 곧 결과이고, 한 번
   * 더 읽으면 요청만 늘어난다.
   */
  async function handleGenerate() {
    // 버튼을 잠그는 것만으로는 부족하다. 화면이 다시 그려지기 전의 두 번째 누름은 여기서 막는다.
    if (pending) {
      return
    }

    const target = incidentId
    setGeneration({ incidentId: target, pending: true, error: null })
    try {
      const analysis = await createIncidentAnalysis(target)
      if (latestRef.current?.incidentId === target) {
        setLoaded({ incidentId: target, analysis, error: null })
      }
      setGeneration({ incidentId: target, pending: false, error: null })
    } catch (error) {
      // 분석에 실패해도 보고 있던 Incident 상세는 그대로 둔다. 분석은 이 Incident를 이해하는 데 보태는
      // 것이지 상세를 읽는 조건이 아니며, 하나로 묶으면 AI가 멈춘 동안 판정 근거도 볼 수 없게 된다.
      setGeneration({ incidentId: target, pending: false, error })
    }
  }

  return (
    <section className="panel" aria-labelledby="analysis-heading">
      <h2 id="analysis-heading" className="panel-title">
        기본 AI 분석
      </h2>
      <p className="panel-note">
        저장된 판정 근거만 보고 만든 가설이며 확정된 원인이 아니다. log와 소스 코드, 배포 이력은 분석에
        쓰이지 않았다.
      </p>
      {renderBody()}
    </section>
  )

  function renderBody() {
    if (!result) {
      return <Loading label="AI 분석을 불러오는 중입니다..." />
    }

    if (result.error) {
      return <ErrorMessage error={result.error} />
    }

    if (result.analysis) {
      return <AiAnalysisResult analysis={result.analysis} />
    }

    return (
      <div className="action">
        <p className="empty-state-description">아직 이 Incident의 AI 분석이 없습니다.</p>
        <button type="button" className="button" onClick={handleGenerate} disabled={pending}>
          {pending ? '분석 중...' : 'AI 분석 생성'}
        </button>
        <ErrorMessage error={action?.error ?? null} />
      </div>
    )
  }
}
