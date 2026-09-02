import { statusLabel } from '../format.js'

const STATUS_CLASSES = {
  OPEN: 'badge badge-open',
  ACKNOWLEDGED: 'badge badge-acknowledged',
  RESOLVED: 'badge badge-resolved',
}

export function IncidentStatusBadge({ status }) {
  return (
    <span className={STATUS_CLASSES[status] ?? 'badge'} title={status}>
      {statusLabel(status)}
    </span>
  )
}
