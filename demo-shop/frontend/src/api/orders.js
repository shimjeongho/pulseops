import { getJson, postJson } from './client.js'

/**
 * Places an order. Only the product and how many of it are sent: the backend prices
 * the order from its own product data, so nothing the cart shows is trusted here.
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
