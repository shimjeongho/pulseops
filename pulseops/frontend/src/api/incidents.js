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

/**
 * Incident 하나의 AI 분석을 읽는다. 아직 아무도 요청하지 않은 Incident에서는 404가 오며, 그것은 실패가
 * 아니라 "아직 없음"이라는 정상적인 답이다.
 */
export function fetchIncidentAnalysis(incidentId, signal) {
  return getJson(`/api/incidents/${incidentId}/analysis`, signal)
}

/**
 * AI 분석을 만든다. 사람이 눌렀을 때만 부른다. 이 호출 하나가 외부 API 요금으로 이어지므로, 화면을 다시
 * 그리거나 알림을 받았다는 이유로 부르지 않는다.
 *
 * 이미 만들어진 분석이 있으면 Backend가 다시 만들지 않고 그것을 그대로 돌려준다. 그래서 두 번 눌러도
 * 결과가 달라지지 않는다.
 */
export function createIncidentAnalysis(incidentId, signal) {
  return postJson(`/api/incidents/${incidentId}/analysis`, signal)
}

/**
 * Incident 하나의 GitHub 코드 컨텍스트를 읽는다. 아직 아무도 수집하지 않은 Incident에서는 404가 오며,
 * 그것은 실패가 아니라 "아직 없음"이라는 정상적인 답이다.
 */
export function fetchIncidentGitHubContext(incidentId, signal) {
  return getJson(`/api/incidents/${incidentId}/github-context`, signal)
}

/**
 * GitHub에서 코드 컨텍스트를 수집한다. 사람이 눌렀을 때만 부른다. 이 호출 하나가 GitHub의 분당 호출
 * 한도를 쓰므로, 화면을 다시 그리거나 알림을 받았다는 이유로 부르지 않는다.
 *
 * 이미 수집된 컨텍스트가 있으면 Backend가 다시 모으지 않고 그것을 그대로 돌려준다. 그래서 두 번 눌러도
 * 결과가 달라지지 않는다.
 */
export function createIncidentGitHubContext(incidentId, signal) {
  return postJson(`/api/incidents/${incidentId}/github-context`, signal)
}

/** 코드 컨텍스트까지 근거로 삼은 AI 분석을 읽는다. 기본 분석과 다른 기록이므로 경로도 다르다. */
export function fetchIncidentCodeAnalysis(incidentId, signal) {
  return getJson(`/api/incidents/${incidentId}/analysis/github`, signal)
}

/**
 * 코드 컨텍스트까지 근거로 삼은 AI 분석을 만든다. 사람이 눌렀을 때만 부른다.
 *
 * 이 호출은 GitHub을 부르지 않는다. 근거로 쓸 컨텍스트는 이미 수집되어 저장되어 있어야 하며, 없으면
 * Backend가 만들지 않고 거절한다. 수집과 분석은 드는 값이 서로 달라 사람이 따로 정한다.
 */
export function createIncidentCodeAnalysis(incidentId, signal) {
  return postJson(`/api/incidents/${incidentId}/analysis/github`, signal)
}
