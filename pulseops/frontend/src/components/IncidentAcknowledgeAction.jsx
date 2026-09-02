import { ErrorMessage } from './ErrorMessage.jsx'

/** 확인 처리로 넘어갈 수 있는 유일한 상태이다. 나머지 전이는 Backend가 거절한다. */
const ACKNOWLEDGEABLE_STATUS = 'OPEN'

/**
 * Incident를 사람이 확인했다고 표시한다.
 *
 * OPEN이 아니면 버튼을 두지 않는다. 확인과 해결은 되돌릴 수 없는 전이라 다시 누를 일이 없고, 누를 수
 * 없는 버튼을 남겨 두면 지금 무엇을 할 수 있는지가 상태 badge와 버튼 두 곳에서 설명된다.
 *
 * 반대로 실패는 상태와 상관없이 남긴다. 상태가 이미 달라져 거절된 경우에는 곧바로 다시 읽어 버튼이
 * 사라지는데, 그때 실패까지 함께 사라지면 눌렀는데 아무 일도 일어나지 않은 것처럼 보인다.
 */
export function IncidentAcknowledgeAction({ status, pending, error, onAcknowledge }) {
  const canAcknowledge = status === ACKNOWLEDGEABLE_STATUS

  if (!canAcknowledge && !error) {
    return null
  }

  return (
    <div className="action">
      {canAcknowledge ? (
        <button type="button" className="button" onClick={onAcknowledge} disabled={pending}>
          {pending ? '처리 중...' : 'Incident 확인'}
        </button>
      ) : null}
      <ErrorMessage error={error} />
    </div>
  )
}
