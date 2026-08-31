import { getJson, postJson } from './client.js'

/**
 * 주문을 생성한다. 상품과 수량만 전송한다. 백엔드가 자신의 상품 데이터로 주문 금액을
 * 계산하므로 장바구니에 표시된 값은 신뢰하지 않는다.
 */
export function createOrder(items) {
  const payload = {
    items: items.map((item) => ({ productId: item.productId, quantity: item.quantity })),
  }
  return postJson('/api/orders', payload)
}

export function getOrders() {
  return getJson('/api/orders')
}

export function getOrder(orderId) {
  return getJson(`/api/orders/${orderId}`)
}
