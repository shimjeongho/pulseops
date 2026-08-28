import { postJson } from './client.js'

/**
 * Pays for an order. The amount is deliberately not sent: the backend charges the
 * order's own total.
 */
export function createPayment(orderId) {
  return postJson('/api/payments', { orderId })
}
