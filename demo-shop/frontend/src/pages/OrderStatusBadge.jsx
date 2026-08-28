import { orderStatusLabel } from '../format.js'

export function OrderStatusBadge({ status }) {
  const className = status === 'PAID' ? 'badge badge-ok' : 'badge badge-pending'
  return <span className={className}>{orderStatusLabel(status)}</span>
}
