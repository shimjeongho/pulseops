/**
 * Thin wrapper over fetch for the Demo Shop backend. Every failure — a network
 * problem, a business rejection, an unexpected status — arrives at the caller as an
 * ApiError, so pages have one thing to catch and one shape to render.
 */

/** A failed request, carrying the backend's own error contract where there is one. */
export class ApiError extends Error {
  constructor({ status, code, message, fieldErrors = [] }) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

const GENERIC_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

async function readBody(response) {
  try {
    const text = await response.text()
    return text ? JSON.parse(text) : null
  } catch {
    // a non-JSON body (a proxy error page, an empty response) carries nothing useful
    return null
  }
}

async function request(path, options) {
  let response
  try {
    response = await fetch(path, options)
  } catch {
    throw new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인해 주세요.',
    })
  }

  const body = await readBody(response)

  if (!response.ok) {
    throw new ApiError({
      status: response.status,
      // an ErrorResponse from the backend, or a bare status we have to describe ourselves
      code: body?.code ?? 'UNEXPECTED_ERROR',
      message: body?.message ?? GENERIC_MESSAGE,
      fieldErrors: body?.fieldErrors ?? [],
    })
  }

  return body
}

export function getJson(path) {
  return request(path, { method: 'GET', headers: { Accept: 'application/json' } })
}

export function postJson(path, payload) {
  return request(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(payload),
  })
}
