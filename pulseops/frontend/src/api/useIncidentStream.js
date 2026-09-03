import { useEffect } from 'react'

/** Backend가 Incident 변경을 흘려보내는 연결이다. */
const STREAM_PATH = '/api/incidents/stream'

/** 연결이 실제로 열렸다는 첫 Event이다. 끊겼다 다시 이어질 때마다 새로 온다. */
const CONNECTED_EVENT = 'connected'

/** Incident 하나가 바뀌었으니 다시 읽으라는 Event이다. */
const INCIDENT_CHANGED_EVENT = 'incident-changed'

/** 연결이 아직 살아 있다는 것만 알리는 Event이다. 이것을 받아 다시 읽지 않는다. */
const HEARTBEAT_EVENT = 'heartbeat'

/**
 * 이만큼 아무것도 오지 않으면 연결이 끊긴 것으로 본다.
 *
 * Backend가 20초마다 신호를 보내므로 이 값은 그것의 세 배이다. 넉넉하게 잡는 이유는 한 번의 지연이나
 * 유실로 멀쩡한 연결을 다시 만들지 않기 위해서이다. 신호가 한 번 늦는 것은 흔한 일이고, 그때마다
 * 연결을 새로 만들면 정작 진짜로 끊긴 것과 구분할 수 없다.
 */
const STALE_AFTER_MS = 60000

/**
 * 이 주기로 마지막 수신 시각을 확인한다.
 *
 * 판정 기준보다 짧게 두어 끊김을 알아채기까지 걸리는 시간이 판정 기준에 너무 크게 밀리지 않게 한다.
 * 이 주기는 Backend에 아무것도 보내지 않으므로 짧아도 비용이 없다.
 */
const WATCHDOG_INTERVAL_MS = 10000

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
 * 끊김은 대부분 EventSource가 스스로 다시 잇는다. 여기서 하는 일은 그것을 대신하는 것이 아니라,
 * EventSource가 끊긴 줄 모르는 경우만 보태는 것이다. Backend Process가 사라지면 연결을 끊어 줄 서버
 * 쪽 시간 초과도 함께 사라지고, 중간의 Proxy가 Client 쪽 연결을 그대로 붙들고 있으면 오류도 종료도
 * 도착하지 않는다. 그러면 연결은 열린 채로 남아 아무것도 오지 않고, 화면은 정상처럼 보이면서 낡은
 * 상태에 갇힌다. Backend가 주기적으로 보내기로 약속한 신호가 오지 않는 것으로 그 상태를 알아낸다.
 *
 * 끊겼다고 판단해도 여기서 다시 읽지는 않는다. 하는 일은 연결을 새로 만드는 것뿐이고, 다시 읽는 것은
 * 새 연결이 열렸을 때이다. 그래야 이 Timer가 조회 주기가 되지 않는다. Incident를 주기로 읽지 않는
 * 것은 이 화면의 설계이며, 연결을 감시하는 일이 그것을 무너뜨리면 안 된다.
 *
 * 마지막 수신 시각을 State로 두지 않는다. 신호는 주기적으로 오고 그때마다 화면을 다시 그릴 이유가
 * 없다. 이 값은 화면에 나타나지 않고 이 Effect 안에서만 쓰이므로 Effect의 지역 변수로 충분하다.
 *
 * @param onConnected 연결이 열렸을 때 부를 함수. render마다 새로 만들면 연결이 매번 끊겼다 이어지므로
 * Module 함수이거나 useCallback으로 고정한 것이어야 한다.
 * @param onIncidentChanged 바뀐 Incident의 ID를 받는 함수. 같은 이유로 고정되어 있어야 한다.
 */
export function useIncidentStream({ onConnected, onIncidentChanged }) {
  useEffect(() => {
    let source = null
    let lastReceivedAt = 0

    function open() {
      // 아직 아무것도 받지 않은 연결을 곧바로 끊긴 것으로 보지 않도록 만든 시각부터 센다. 연결이
      // 열리고 첫 신호가 오기까지는 원래 한 주기가 걸린다.
      lastReceivedAt = Date.now()
      source = new EventSource(STREAM_PATH)

      // Event 이름으로 나누어 받는다. 이름 없는 Event만 받는 onmessage로는 셋을 구분할 수 없고,
      // Backend는 세 Event에 모두 이름을 붙여 보낸다.
      source.addEventListener(CONNECTED_EVENT, () => {
        lastReceivedAt = Date.now()
        onConnected()
      })
      source.addEventListener(HEARTBEAT_EVENT, () => {
        lastReceivedAt = Date.now()
      })
      source.addEventListener(INCIDENT_CHANGED_EVENT, (event) => {
        // 본문을 읽기 전에 먼저 기록한다. 본문이 깨진 것과 연결이 끊긴 것은 다른 일이고, 무엇이든
        // 도착했다는 사실 자체가 연결이 살아 있다는 뜻이다.
        lastReceivedAt = Date.now()
        const incidentId = readChangedIncidentId(event.data)
        if (incidentId === null) {
          console.warn('Incident 변경 알림의 본문을 이해하지 못해 건너뛴다.')
          return
        }
        onIncidentChanged(incidentId)
      })
    }

    open()

    const watchdog = setInterval(() => {
      if (Date.now() - lastReceivedAt < STALE_AFTER_MS) {
        return
      }
      console.warn('실시간 알림이 끊긴 것으로 보여 연결을 다시 만든다.')
      // 새로 만들기 전에 반드시 닫는다. 닫지 않으면 연결이 둘이 되고, 같은 변경 하나에 조회가 두 번
      // 나간다. 새 연결이 다시 시각을 잡으므로 Backend가 계속 죽어 있어도 이 판정은 기준 시간에 한
      // 번씩만 일어난다.
      source.close()
      open()
    }, WATCHDOG_INTERVAL_MS)

    // StrictMode는 Effect를 두 번 실행하므로 연결과 Timer를 반드시 여기서 거둔다. 거두지 않으면
    // 화면 하나가 연결 둘과 Timer 둘을 붙든다.
    return () => {
      clearInterval(watchdog)
      source.close()
    }
  }, [onConnected, onIncidentChanged])
}
