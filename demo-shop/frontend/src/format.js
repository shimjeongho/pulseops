const currencyFormat = new Intl.NumberFormat('ko-KR')

const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
})

export function formatPrice(amount) {
  if (typeof amount !== 'number' || Number.isNaN(amount)) {
    return '-'
  }
  return `${currencyFormat.format(amount)}원`
}

export function formatDateTime(value) {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : dateFormat.format(date)
}

const ORDER_STATUS_LABELS = {
  PENDING_PAYMENT: '결제 대기',
  PAID: '결제 완료',
}

const PRODUCT_STATUS_LABELS = {
  ACTIVE: '판매중',
  INACTIVE: '판매 중지',
}

export function orderStatusLabel(status) {
  return ORDER_STATUS_LABELS[status] ?? status
}

export function productStatusLabel(status) {
  return PRODUCT_STATUS_LABELS[status] ?? status
}
