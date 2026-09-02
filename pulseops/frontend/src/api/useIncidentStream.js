import { useEffect } from 'react'

/** Backend가 Incident 변경을 흘려보내는 연결이다. */
const STREAM_PATH = '/api/incidents/stream'

/** 연결이 실제로 열렸다는 첫 Event이다. 끊겼다 다시 이어질 때마다 새로 온다. */
const CONNECTED_EVENT = 'connected'

/** Incident 하나가 바뀌었으니 다시 읽으라는 Event이다. */
const INCIDENT_CHANGED_EVENT = 'incident-changed'

/**
 * 알림 본문에서 어느 Incident가 바뀌었는지 읽는다.
 *
 * 약속된 두 값이 모두 있지 않으면 그 알림을 버린다. 뜻을 알 수 없는 본문으로 화면을 움직이면, 알림이
 * 깨졌다는 사실이 "무엇인가 바뀌었는데 화면이 따라오지 않는다"로 잘못 보인다.
 */
function readChangedIncidentId(data) {
  let payload
  try {
    payload = JSON.parse(data)
  } catch {
    return null
  }

  if (payload?.incidentId == null || payload?.occurredAt == null) {
    return null
  }

  return payload.incidentId
}

/**
 * 화면이 열려 있는 동안 Incident 변경 알림을 받는다.
 *
 * 알림으로 상태를 옮기지 않는다. 본문에는 어느 Incident가 언제 바뀌었는지만 있고 무엇이 어떻게
 * 바뀌었는지는 없다. 그래서 이 Hook은 값을 돌려주지 않고 다시 읽으라고만 알리며, 무엇이 참인지는
 * 부르는 쪽이 REST로 다시 물어 정한다. 알림은 유실될 수 있고 순서도 보장되지 않으므로, 그것을 믿기
 * 시작하면 화면이 실제와 다른 상태를 오래 붙들고 있게 된다.
 *
 * onConnected는 연결이 열릴 때마다 불린다. 처음 열린 것과 끊겼다 다시 이어진 것을 가르지 않는데,
 * 가르지 않는 편이 맞다. 끊겨 있던 동안의 변경은 알림으로 오지 않았으므로 다시 이어진 순간에는 언제나
 * 다시 읽어야 하고, 처음 한 번의 중복 조회를 아끼려고 그 둘을 구분하면 놓친 변경도 같은 조건에
 * 걸러진다. 조회 한 번이 그것보다 싸다.
 *
 * 연결이 열렸다는 것을 응답 자체가 아니라 첫 Event로 판단한다. 200과 Content-Type은 중간의 Proxy가
 * 돌려줄 수도 있어, 그것만으로는 Backend까지 실제로 이어졌는지 알 수 없다.
 *
 * 끊김을 직접 다루지 않는다. EventSource는 연결이 끊기면 스스로 다시 잇는다. 여기서 close를 부르면
 * 그 재시도를 막게 되고, 재시도 주기를 손으로 만들면 브라우저가 이미 하는 일을 한 번 더 하다가 연결이
 * 둘이 된다. 끊긴 동안 화면에 남아 있는 값은 지우지 않는다. 알림이 끊긴 것이지 그 값이 틀린 것은
 * 아니다.
 *
 * @param onConnected 연결이 열렸을 때 부를 함수. render마다 새로 만들면 연결이 매번 끊겼다 이어지므로
 * Module 함수이거나 useCallback으로 고정한 것이어야 한다.
 * @param onIncidentChanged 바뀐 Incident의 ID를 받는 함수. 같은 이유로 고정되어 있어야 한다.
 */
export function useIncidentStream({ onConnected, onIncidentChanged }) {
  useEffect(() => {
    const source = new EventSource(STREAM_PATH)

    // Event 이름으로 나누어 받는다. 이름 없는 Event만 받는 onmessage로는 둘을 구분할 수 없고,
    // Backend는 두 Event에 이름을 붙여 보낸다.
    source.addEventListener(CONNECTED_EVENT, () => onConnected())
    source.addEventListener(INCIDENT_CHANGED_EVENT, (event) => {
      const incidentId = readChangedIncidentId(event.data)
      if (incidentId === null) {
        console.warn('Incident 변경 알림의 본문을 이해하지 못해 건너뛴다.')
        return
      }
      onIncidentChanged(incidentId)
    })

    // StrictMode는 Effect를 두 번 실행하므로 연결을 반드시 여기서 닫는다. 닫지 않으면 화면 하나가
    // 연결 둘을 붙들고, 같은 변경 하나에 조회가 두 번 나간다.
    return () => source.close()
  }, [onConnected, onIncidentChanged])
}
