const STORAGE_KEY = 'demo-shop-cart'

/**
 * 장바구니는 이 브라우저 안에만 존재한다. 저장하는 값은 사용자가 본 이름과 가격 같은 화면
 * 표시용 데이터이며 Business 판단의 근거가 되지 않는다. 주문 시점에 백엔드가 금액을 다시
 * 계산한다.
 */

function isUsableItem(item) {
  return (
    item !== null &&
    typeof item === 'object' &&
    Number.isInteger(item.productId) &&
    typeof item.name === 'string' &&
    typeof item.price === 'number' &&
    Number.isInteger(item.quantity) &&
    item.quantity >= 1
  )
}

export function readCart() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return []
    }
    const parsed = JSON.parse(raw)
    // 직접 수정되었거나 이전 버전이 남긴 값은 신뢰하지 않고 버린다
    return Array.isArray(parsed) ? parsed.filter(isUsableItem) : []
  } catch {
    return []
  }
}

export function writeCart(items) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  } catch {
    // 저장 공간이 가득 찼거나 차단되어도 진행 중인 주문 흐름이 깨지지 않아야 한다
  }
}
