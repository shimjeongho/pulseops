import { getJson } from './client.js'

export function fetchMetrics(signal) {
  return getJson('/api/metrics', signal)
}
