import { useEffect, useRef, useState } from 'react'
import {
  createIncidentCodeAnalysis,
  createIncidentGitHubContext,
  fetchIncidentCodeAnalysis,
  fetchIncidentGitHubContext,
} from '../api/incidents.js'
import { AiAnalysisResult } from './AiAnalysisResult.jsx'
import { ErrorMessage } from './ErrorMessage.jsx'
import { Loading } from './Loading.jsx'
import { Timestamp } from './Timestamp.jsx'

/** 아직 수집하거나 만들지 않았다는 답이다. 화면에서는 실패가 아니라 버튼을 그릴 조건이다. */
const NOT_FOUND = 404

/**
 * 아직 없다는 답을 실패가 아니라 빈 값으로 바꾼다.
 *
 * 컨텍스트도 분석도 아무도 요청하지 않았으면 없는 것이 정상이고, 그것을 실패로 다루면 화면이 버튼을
 * 그려야 할 자리에 오류를 그린다. 나머지 실패는 그대로 올려 보낸다.
 */
function absentOnNotFound(request) {
  return request.catch((error) => {
    if (error.status === NOT_FOUND) {
      return null
    }
    throw error
  })
}

/**
 * Incident 하나의 GitHub 코드 컨텍스트를 수집하고, 그것을 근거로 한 AI 분석을 보여 준다.
 *
 * 두 단계를 한 화면에 두되 버튼은 나눈다. 코드 컨텍스트를 모으는 것은 GitHub의 분당 호출 한도를 쓰고,
 * 분석을 만드는 것은 외부 API 요금을 쓴다. 드는 값이 서로 다르므로 사람이 각각 정할 수 있어야 하며,
 * 한 번의 누름으로 둘 다 실행하면 어느 쪽이 실패했는지도 하나의 오류로 뭉뚱그려진다.
 *
 * 들어올 때 자동으로 아무것도 만들지 않는다. 읽기만 하고, 수집도 분석도 사람이 눌렀을 때뿐이다.
 * 화면을 여는 것만으로 외부 호출이 나가면 안 된다.
 *
 * 기본 AI 분석과 상태를 나눈다. 두 분석은 서로 다른 기록이고 한쪽이 없다고 다른 쪽을 못 읽는 것도
 * 아니므로, 한 상태에 묶으면 한쪽의 실패가 다른 쪽 화면까지 지운다.
 */
export function IncidentCodeAnalysisSection({ incidentId }) {
  const [loaded, setLoaded] = useState(null)
  const [collection, setCollection] = useState(null)
  const [generation, setGeneration] = useState(null)

  /**
   * 지금 화면에 써도 되는 결과인지 가리는 표식이다.
   *
   * 조회와 수집과 분석이 동시에 진행될 수 있고, 그 사이 다른 Incident로 옮겨 갈 수도 있다. 늦게 도착한
   * 결과가 지금 보고 있는 Incident의 자리를 차지하지 않도록, 결과를 쓰기 직전에 자기 표식이 아직
   * 최신인지 확인한다. 상세 화면과 기본 분석이 쓰는 것과 같은 방식이다.
   */
  const latestRef = useRef(null)

  useEffect(() => {
    const controller = new AbortController()
    const token = { incidentId }
    latestRef.current = token

    // 컨텍스트와 분석은 서로 없어도 되는 값이라 함께 읽고 함께 쓴다. 하나씩 읽으면 두 번의 갱신 사이에
    // "컨텍스트는 있는데 분석은 아직 모르는" 중간 상태가 화면에 잠깐 그려진다.
    Promise.all([
      absentOnNotFound(fetchIncidentGitHubContext(incidentId, controller.signal)),
      absentOnNotFound(fetchIncidentCodeAnalysis(incidentId, controller.signal)),
    ]).then(
      ([context, analysis]) => {
        if (latestRef.current === token) {
          setLoaded({ incidentId, context, analysis, error: null })
        }
      },
      (error) => {
        // 취소는 화면을 벗어났다는 뜻이므로 실패로 남기지 않는다
        if (latestRef.current === token && error.name !== 'AbortError') {
          setLoaded({ incidentId, context: null, analysis: null, error })
        }
      },
    )

    return () => {
      latestRef.current = null
      controller.abort()
    }
  }, [incidentId])

  // 들고 있는 결과가 어느 Incident의 것인지 함께 기억한다. 주소만 바뀌고 Component는 그대로 남는
  // 이동에서 앞 Incident의 컨텍스트를 잠깐 보여 주지 않기 위해서이다.
  const result = loaded?.incidentId === incidentId ? loaded : null
  const collecting = collection?.incidentId === incidentId ? collection : null
  const generating = generation?.incidentId === incidentId ? generation : null

  /** 수집 결과를 그대로 화면에 쓴다. Backend가 저장된 컨텍스트를 통째로 돌려주므로 다시 읽지 않는다. */
  async function handleCollect() {
    // 버튼을 잠그는 것만으로는 부족하다. 화면이 다시 그려지기 전의 두 번째 누름은 여기서 막는다.
    if (collecting?.pending) {
      return
    }

    const target = incidentId
    setCollection({ incidentId: target, pending: true, error: null })
    try {
      const context = await createIncidentGitHubContext(target)
      if (latestRef.current?.incidentId === target) {
        setLoaded((current) => ({ ...current, incidentId: target, context, error: null }))
      }
      setCollection({ incidentId: target, pending: false, error: null })
    } catch (error) {
      // 수집에 실패해도 보고 있던 Incident 상세와 기본 분석은 그대로 둔다. 코드 컨텍스트는 이 Incident를
      // 이해하는 데 보태는 것이지 상세를 읽는 조건이 아니다.
      setCollection({ incidentId: target, pending: false, error })
    }
  }

  async function handleGenerate() {
    if (generating?.pending) {
      return
    }

    const target = incidentId
    setGeneration({ incidentId: target, pending: true, error: null })
    try {
      const analysis = await createIncidentCodeAnalysis(target)
      if (latestRef.current?.incidentId === target) {
        setLoaded((current) => ({ ...current, incidentId: target, analysis, error: null }))
      }
      setGeneration({ incidentId: target, pending: false, error: null })
    } catch (error) {
      setGeneration({ incidentId: target, pending: false, error })
    }
  }

  return (
    <section className="panel" aria-labelledby="code-analysis-heading">
      <h2 id="code-analysis-heading" className="panel-title">
        코드 컨텍스트 기반 AI 분석
      </h2>
      <p className="panel-note">
        관련 코드 일부와 최근 변경 이력을 추가로 참고한 가설이며, 원인이 확정된 것은 아니다. repository
        전체가 아니라 endpoint로 찾아낸 소수의 파일 조각만 근거로 쓰였다.
      </p>
      {renderBody()}
    </section>
  )

  function renderBody() {
    if (!result) {
      return <Loading label="코드 컨텍스트를 불러오는 중입니다..." />
    }

    if (result.error) {
      return <ErrorMessage error={result.error} />
    }

    return (
      <>
        {result.context ? <CodeContext context={result.context} /> : renderCollectAction()}
        {result.context ? renderAnalysis() : null}
      </>
    )
  }

  function renderCollectAction() {
    return (
      <div className="action">
        <p className="empty-state-description">아직 이 Incident의 GitHub 코드 컨텍스트가 없습니다.</p>
        <button type="button" className="button" onClick={handleCollect} disabled={collecting?.pending}>
          {collecting?.pending ? '수집 중...' : '코드 컨텍스트 수집'}
        </button>
        <ErrorMessage error={collecting?.error ?? null} />
      </div>
    )
  }

  /**
   * 관련 파일을 하나도 찾지 못한 컨텍스트로는 분석을 만들지 않는다. 그렇게 만든 분석은 이름만 코드
   * 기반일 뿐 기본 분석과 같은 근거로 만든 두 번째 분석이며, Backend도 같은 이유로 거절한다.
   */
  function renderAnalysis() {
    if (result.analysis) {
      return <AiAnalysisResult analysis={result.analysis} />
    }

    if (result.context.files.length === 0) {
      return (
        <p className="empty-state-description">
          관련 소스를 찾지 못해 코드 기반 분석을 만들 수 없습니다.
        </p>
      )
    }

    return (
      <div className="action">
        <button type="button" className="button" onClick={handleGenerate} disabled={generating?.pending}>
          {generating?.pending ? '분석 중...' : '코드 기반 AI 분석 생성'}
        </button>
        <ErrorMessage error={generating?.error ?? null} />
      </div>
    )
  }
}

/**
 * 수집된 컨텍스트가 무엇을 근거로 삼았는지 보여 준다.
 *
 * 소스 본문은 보여 주지 않는다. Backend도 응답에 담지 않는다. 사람이 여기서 알아야 할 것은 어느 파일의
 * 어느 판을 봤는가이며, 실제 코드는 GitHub에서 보는 편이 낫다.
 *
 * 수집 시각을 함께 보여 준다. 이 컨텍스트는 그 시점의 repository를 보고 모은 것이고 이후 바뀌지 않으므로,
 * 그 시각은 이 근거가 무엇을 알 수 있었는가의 상한이다.
 */
function CodeContext({ context }) {
  return (
    <div className="analysis-block">
      <h3 className="analysis-subtitle">
        수집된 코드 컨텍스트 · {context.repository} · {context.ref}
      </h3>
      {context.files.length > 0 ? (
        <ul className="code-context-list">
          {context.files.map((file) => (
            <li key={file.path}>
              <span className="code-context-path">{file.path}</span>
              <span className="code-context-meta">
                {file.revisionSha.slice(0, SHORT_SHA_LENGTH)}
                {file.truncated ? ' · 일부만 참고' : ''}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="empty-state-description">관련 소스를 찾지 못했습니다.</p>
      )}

      {context.commits.length > 0 ? (
        <>
          <h3 className="analysis-subtitle">발생 직전 변경</h3>
          <ul className="code-context-list">
            {context.commits.map((commit) => (
              <li key={commit.sha}>
                <span className="code-context-path">{commit.message}</span>
                <span className="code-context-meta">
                  {commit.sha.slice(0, SHORT_SHA_LENGTH)} · <Timestamp value={commit.committedAt} />
                </span>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      <p className="analysis-origin">
        <Timestamp value={context.capturedAt} /> 수집 · {context.sourceRoot}
      </p>
    </div>
  )
}

/** GitHub이 화면과 URL에서 commit을 가리킬 때 쓰는 길이와 같게 맞춘다. */
const SHORT_SHA_LENGTH = 7
