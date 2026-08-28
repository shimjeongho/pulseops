const FALLBACK_MESSAGE = '요청을 처리하지 못했습니다.'

/**
 * Shows what went wrong using the backend's own message where there is one. Nothing
 * developer-facing — no status codes, stack traces or exception names — reaches the
 * screen; the field errors are the only detail, and they name the caller's own input.
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
