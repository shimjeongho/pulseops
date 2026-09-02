const FALLBACK_MESSAGE = '요청을 처리하지 못했습니다.'

/**
 * Backend가 내려준 문장으로 실패를 알린다. 상태 코드나 Exception 이름 같은 개발자용 정보는 화면에
 * 내보내지 않는다. 운영자가 그것으로 할 수 있는 일이 없고, Backend는 이미 사람이 읽을 문장을 정해
 * 두었다.
 */
export function ErrorMessage({ error, children }) {
  if (!error) {
    return null
  }

  return (
    <div className="error-box" role="alert">
      <p className="error-message">{error.message || FALLBACK_MESSAGE}</p>
      {children}
    </div>
  )
}
