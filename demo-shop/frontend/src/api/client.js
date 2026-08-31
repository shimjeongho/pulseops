/**
 * Demo Shop 백엔드 호출을 위한 얇은 fetch 래퍼이다. 네트워크 오류, Business 거부, 예상치 못한
 * 상태 코드 등 모든 실패는 ApiError로 전달되므로, 각 화면은 한 가지 예외만 잡고 한 가지 형태만
 * 렌더링하면 된다.
 */

/** 실패한 요청을 표현한다. 백엔드가 내려준 오류 규약이 있으면 그 값을 그대로 담는다. */
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
    // JSON이 아닌 본문(프록시 오류 페이지, 빈 응답)에는 쓸 만한 정보가 없다
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
      // 백엔드가 내려준 ErrorResponse를 쓰거나, 없으면 직접 설명을 채운다
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
