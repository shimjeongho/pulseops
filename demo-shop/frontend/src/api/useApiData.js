import { useCallback, useEffect, useState } from 'react'

/**
 * 요청을 실행하고 모든 화면에 필요한 세 가지 상태인 로딩, 실패, 완료를 관리한다.
 * `reload`는 요청을 다시 실행하며, 화면이 어떤 동작을 수행한 뒤 서버 상태를 다시 보여줄 때
 * 사용한다.
 *
 * @param load 요청 Promise를 반환하는 함수
 * @param deps useEffect와 같은 방식으로 사용하는, 요청이 의존하는 값
 */
export function useApiData(load, deps) {
  const [state, setState] = useState({ data: null, error: null, loading: true })
  const [reloadCount, setReloadCount] = useState(0)

  const reload = useCallback(() => setReloadCount((count) => count + 1), [])

  useEffect(() => {
    let cancelled = false
    setState({ data: null, error: null, loading: true })

    load().then(
      (data) => {
        if (!cancelled) {
          setState({ data, error: null, loading: false })
        }
      },
      (error) => {
        if (!cancelled) {
          setState({ data: null, error, loading: false })
        }
      },
    )

    // 응답이 도착하기 전에 화면을 벗어난 경우 Unmount된 Component에 상태를 쓰지 않도록 한다
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, reloadCount])

  return { ...state, reload }
}
