import { useCallback, useEffect, useState } from 'react'

/** 주기를 주지 않으면 다시 부르기 전까지 한 번만 요청한다. */
const NO_POLLING = 0

/**
 * 요청 결과를 들고 있되, 뒤이은 요청이 실패해도 마지막으로 성공한 값을 지우지 않는다.
 *
 * 이것이 이 Hook이 있는 이유다. 갱신에 실패한 것과 보여 줄 것이 없는 것은 다른 상태인데, 실패할
 * 때마다 화면을 비우면 Backend가 잠깐 흔들리는 동안 운영자가 보고 있던 숫자가 통째로 사라진다.
 * 조금 낡은 값이라도 남겨 두고 갱신에 실패했다는 사실을 따로 알리는 편이 낫다.
 *
 * intervalMs를 주면 그 주기로 다시 요청한다. 앞선 요청이 아직 끝나지 않았으면 이번 주기는
 * 건너뛴다. 느려진 Backend에 요청이 쌓이면 그 Backend를 더 밀어붙이게 된다.
 *
 * loading은 요청이 진행 중이라는 뜻일 뿐 화면이 비어 있다는 뜻이 아니다. 처음 불러오는 중인지는
 * data가 아직 없는지로 판단해야 하며, 그래서 다시 불러오는 동안에도 이미 받은 값이 그대로 보인다.
 *
 * @param load AbortSignal을 받아 요청 Promise를 돌려주는 함수. render마다 새로 만들면 요청이 다시
 * 시작되므로 Module 함수이거나 useCallback으로 고정한 것이어야 한다.
 */
export function useLastGoodData(load, { intervalMs = NO_POLLING } = {}) {
  const [state, setState] = useState({ data: null, error: null, loading: true })
  const [reloadCount, setReloadCount] = useState(0)

  const reload = useCallback(() => setReloadCount((count) => count + 1), [])

  useEffect(() => {
    let cancelled = false
    let inFlight = false
    const controller = new AbortController()

    async function run() {
      if (inFlight) {
        return
      }
      inFlight = true
      setState((previous) => ({ ...previous, loading: true }))
      try {
        const data = await load(controller.signal)
        if (!cancelled) {
          setState({ data, error: null, loading: false })
        }
      } catch (error) {
        // 취소는 화면을 벗어났다는 뜻이므로 실패로 남기지 않는다
        if (!cancelled && error.name !== 'AbortError') {
          setState((previous) => ({ ...previous, error, loading: false }))
        }
      } finally {
        inFlight = false
      }
    }

    run()
    const timer = intervalMs > NO_POLLING ? setInterval(run, intervalMs) : null

    // StrictMode는 Effect를 두 번 실행하므로 Timer와 진행 중인 요청을 반드시 여기서 거둔다.
    // 거두지 않으면 Timer가 둘 남아 주기가 절반으로 줄어든다.
    return () => {
      cancelled = true
      if (timer !== null) {
        clearInterval(timer)
      }
      controller.abort()
    }
  }, [load, intervalMs, reloadCount])

  return { ...state, reload }
}
