import { formatDateTime } from '../format.js'

/**
 * 사람이 읽을 시각을 보여 주되 원본을 함께 남긴다. Backend가 내려준 값은 UTC ISO-8601이고 화면은
 * 브라우저 시간대로 그리므로, log나 DB와 맞춰 볼 때는 원본이 필요하다.
 */
export function Timestamp({ value }) {
  if (!value) {
    return <span>{formatDateTime(value)}</span>
  }
  return (
    <time dateTime={value} title={value}>
      {formatDateTime(value)}
    </time>
  )
}
