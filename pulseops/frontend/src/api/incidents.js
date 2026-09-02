import { getJson } from './client.js'

export function fetchActiveIncidents(signal) {
  return getJson('/api/incidents/active', signal)
}

export function fetchIncident(incidentId, signal) {
  return getJson(`/api/incidents/${incidentId}`, signal)
}
