const countFormat = new Intl.NumberFormat('ko-KR')

const dateTimeFormat = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'medium',
})

/** Incident의 판정 근거와 같은 자리수로 맞춘다. 같은 오류율이 화면마다 다른 숫자로 보이면 안 된다. */
const ERROR_RATE_DIGITS = 2

/** 값이 없다는 표시이다. 없는 값을 0으로 바꾸면 그 자리가 가장 좋은 값처럼 읽힌다. */
export const ABSENT = '—'

export function formatDateTime(value) {
  if (!value) {
    return ABSENT
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? ABSENT : dateTimeFormat.format(date)
}

export function formatCount(value) {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return ABSENT
  }
  return countFormat.format(value)
}

/** Backend의 errorRate는 이미 0~100의 백분율이므로 다시 100을 곱하지 않는다. */
export function formatErrorRate(value) {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return ABSENT
  }
  return `${value.toFixed(ERROR_RATE_DIGITS)}%`
}

/** P95는 표본이 없으면 null이다. 0 ms로 바꾸면 응답 시간을 재지 못한 창이 가장 빠른 창이 된다. */
export function formatLatencyMs(value) {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return ABSENT
  }
  return `${countFormat.format(value)} ms`
}

const SEVERITY_LABELS = {
  WARNING: '경고',
  CRITICAL: '심각',
}

const STATUS_LABELS = {
  OPEN: '발생',
  ACKNOWLEDGED: '확인됨',
  RESOLVED: '해결됨',
}

const TYPE_LABELS = {
  ERROR_RATE: '오류율',
  LATENCY: '응답 시간',
}

export function severityLabel(severity) {
  return SEVERITY_LABELS[severity] ?? severity
}

export function statusLabel(status) {
  return STATUS_LABELS[status] ?? status
}

export function incidentTypeLabel(type) {
  return TYPE_LABELS[type] ?? type
}

/**
 * AI 분석이 자기 가설을 얼마나 믿는지이다. 정답률이 아니라 근거의 두께에 대한 표시이므로, 확률처럼
 * 읽히는 숫자를 만들지 않는다.
 */
const CONFIDENCE_LABELS = {
  LOW: '낮음',
  MEDIUM: '보통',
  HIGH: '높음',
}

export function confidenceLabel(confidence) {
  return CONFIDENCE_LABELS[confidence] ?? confidence
}
