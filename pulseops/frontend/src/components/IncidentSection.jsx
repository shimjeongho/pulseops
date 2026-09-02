import { EmptyState } from './EmptyState.jsx'
import { ErrorMessage } from './ErrorMessage.jsx'
import { IncidentList } from './IncidentList.jsx'
import { Loading } from './Loading.jsx'
import { StaleNotice } from './StaleNotice.jsx'

export function IncidentSection({ incidents, error, loading, onRefresh }) {
  let body
  if (!incidents) {
    body = loading ? <Loading label="Incident를 불러오는 중입니다..." /> : <ErrorMessage error={error} />
  } else {
    body = (
      <>
        {error ? <StaleNotice title="활성 Incident 목록을 갱신하지 못했습니다." error={error} /> : null}
        {incidents.length === 0 ? (
          <EmptyState
            title="현재 활성 Incident가 없습니다."
            description="관측 중인 API에서 이상이 이어지면 여기에 나타납니다."
          />
        ) : (
          <IncidentList incidents={incidents} />
        )}
      </>
    )
  }

  return (
    <section className="panel" aria-labelledby="incident-heading">
      <div className="panel-head">
        <h2 id="incident-heading" className="panel-title">
          활성 Incident
        </h2>
        <button type="button" className="button" onClick={onRefresh} disabled={loading}>
          {loading ? '불러오는 중...' : '새로고침'}
        </button>
      </div>
      {body}
    </section>
  )
}
