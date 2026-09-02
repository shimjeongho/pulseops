import { getJson, postJson } from './client.js'

export function fetchActiveIncidents(signal) {
  return getJson('/api/incidents/active', signal)
}

export function fetchIncident(incidentId, signal) {
  return getJson(`/api/incidents/${incidentId}`, signal)
}

/**
 * Incident에 사람이 보고 있다는 표시를 남긴다. Backend가 갱신된 상세를 그대로 돌려주므로 부르는
 * 쪽은 확인 직후 다시 조회하지 않는다.
 */
export function acknowledgeIncident(incidentId, signal) {
  return postJson(`/api/incidents/${incidentId}/acknowledge`, signal)
}
