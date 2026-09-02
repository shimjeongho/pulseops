import { severityLabel } from '../format.js'

const SEVERITY_CLASSES = {
  WARNING: 'badge badge-warning',
  CRITICAL: 'badge badge-critical',
}

/** 색만으로 심각도를 전달하지 않는다. 글자를 함께 두어야 색을 구분하지 못해도 읽을 수 있다. */
export function SeverityBadge({ severity }) {
  return (
    <span className={SEVERITY_CLASSES[severity] ?? 'badge'} title={severity}>
      {severityLabel(severity)}
    </span>
  )
}
