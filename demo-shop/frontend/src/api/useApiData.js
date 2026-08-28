import { useCallback, useEffect, useState } from 'react'

/**
 * Runs a request and tracks the three states every page needs: loading, failed, and
 * loaded. `reload` re-runs it, which is how a page shows the server's state again
 * after acting on it.
 *
 * @param load a function returning the request promise
 * @param deps values the request depends on, as for useEffect
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

    // a page left before its request lands must not write into an unmounted component
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, reloadCount])

  return { ...state, reload }
}
