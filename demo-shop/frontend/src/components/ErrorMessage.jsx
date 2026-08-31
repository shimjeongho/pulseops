const FALLBACK_MESSAGE = '요청을 처리하지 못했습니다.'

/**
 * 백엔드가 내려준 메시지가 있으면 그 메시지로 오류를 안내한다. 상태 코드, Stack Trace,
 * Exception 이름 같은 개발자용 정보는 화면에 노출하지 않는다. 유일한 상세 정보인 필드 오류는
 * 사용자가 직접 입력한 값에 대한 것이다.
 */
export function ErrorMessage({ error, children }) {
  if (!error) {
    return null
  }

  const fieldErrors = Array.isArray(error.fieldErrors) ? error.fieldErrors : []

  return (
    <div className="error-box" role="alert">
      <p className="error-message">{error.message || FALLBACK_MESSAGE}</p>
      {fieldErrors.length > 0 ? (
        <ul className="error-fields">
          {fieldErrors.map((fieldError) => (
            <li key={fieldError}>{fieldError}</li>
          ))}
        </ul>
      ) : null}
      {children}
    </div>
  )
}
