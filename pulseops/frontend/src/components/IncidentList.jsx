import { Link } from 'react-router-dom'
import { IncidentStatusBadge } from './IncidentStatusBadge.jsx'
import { SeverityBadge } from './SeverityBadge.jsx'
import { Timestamp } from './Timestamp.jsx'
import { incidentTypeLabel } from '../format.js'

/**
 * 받은 순서를 그대로 그린다. 무엇을 먼저 봐야 하는지는 Backend가 심각도와 열린 시각으로 이미
 * 정해서 내려주며, 화면이 같은 규칙을 다시 적으면 한쪽만 바뀌었을 때 두 순서가 갈라진다.
 */
export function IncidentList({ incidents }) {
  return (
    <ul className="incident-list">
      {incidents.map((incident) => (
        <li key={incident.id}>
          <Link to={`/incidents/${incident.id}`} className="incident-card">
            <span className="incident-card-head">
              <SeverityBadge severity={incident.severity} />
              <IncidentStatusBadge status={incident.status} />
              <span className="incident-type">{incidentTypeLabel(incident.type)}</span>
            </span>
            <span className="incident-target">
              <span className="method">{incident.method}</span>
              <span className="endpoint">{incident.endpoint}</span>
            </span>
            <span className="incident-meta">
              <span>{incident.service}</span>
              <span>
                발생 <Timestamp value={incident.openedAt} />
              </span>
              {incident.acknowledgedAt ? (
                <span>
                  확인 <Timestamp value={incident.acknowledgedAt} />
                </span>
              ) : null}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  )
}
