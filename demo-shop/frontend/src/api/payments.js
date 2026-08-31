import { postJson } from './client.js'

/**
 * 주문을 결제한다. 결제 금액은 의도적으로 전송하지 않으며 백엔드가 주문의 총 금액을 청구한다.
 */
export function createPayment(orderId) {
  return postJson('/api/payments', { orderId })
}
