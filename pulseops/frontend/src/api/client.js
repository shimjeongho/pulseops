/**
 * PulseOps Backend 호출을 위한 얇은 fetch 래퍼이다. 연결 실패와 Backend가 내려준 오류를 모두
 * ApiError 하나로 모으므로, 화면은 한 가지 예외만 잡고 한 가지 형태만 그리면 된다.
 *
 * 요청 취소만은 ApiError로 바꾸지 않고 그대로 통과시킨다. 화면을 벗어나서 취소한 요청은 사용자에게
 * 보여 줄 실패가 아니며, 그것까지 오류가 되면 떠나는 화면이 마지막에 오류를 한 번 그린다.
 *
 * 주소를 하드코딩하지 않고 /api로 시작하는 상대 경로만 쓴다. Dev Server가 그 경로를 Backend로
 * 넘기므로 브라우저 입장에서는 단일 Origin이고, Backend에 CORS 설정이 필요 없다.
 */

/** 실패한 요청이다. Backend가 ErrorResponse를 내려주었으면 그 code와 message를 그대로 담는다. */
export class ApiError extends Error {
  constructor({ status, code, message }) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

const GENERIC_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

async function readBody(response) {
  try {
    const text = await response.text()
    return text ? JSON.parse(text) : null
  } catch {
    // JSON이 아닌 본문(Proxy 오류 페이지, 빈 응답)에는 쓸 만한 정보가 없다
    return null
  }
}

/**
 * 조회와 상태 변경이 method 하나만 다르므로 실제 호출은 한 자리에 둔다. 실패를 어떻게 읽고 어떤
 * 예외로 옮길지가 두 벌이 되면, 같은 실패를 두고 조회와 변경이 서로 다른 모양으로 실패한다.
 */
async function request(method, path, signal) {
  let response
  try {
    response = await fetch(path, { method, headers: { Accept: 'application/json' }, signal })
  } catch (cause) {
    if (cause.name === 'AbortError') {
      throw cause
    }
    throw new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'PulseOps Backend에 연결할 수 없습니다. Backend가 실행 중인지 확인해 주세요.',
    })
  }

  const body = await readBody(response)

  if (!response.ok) {
    throw new ApiError({
      status: response.status,
      // Backend가 정한 code와 문장을 그대로 쓴다. 어떤 실패에 어떤 뜻을 붙일지는 Backend가 정하며,
      // 화면이 code마다 자기 문장을 다시 만들면 같은 실패가 두 가지로 설명된다.
      code: body?.code ?? 'UNEXPECTED_ERROR',
      message: body?.message ?? GENERIC_MESSAGE,
    })
  }

  return body
}

export function getJson(path, signal) {
  return request('GET', path, signal)
}

/**
 * 본문 없이 상태 변경만 요청한다. 무엇을 바꿀지는 경로가 정하고 언제 바뀌었는지는 서버가 정하므로
 * 요청이 실어 보낼 값이 없고, 보낼 값이 없으면 Content-Type도 설명할 것이 없다.
 */
export function postJson(path, signal) {
  return request('POST', path, signal)
}
