import { getJson } from './client.js'

export function getProducts() {
  return getJson('/api/products')
}

export function getProduct(productId) {
  return getJson(`/api/products/${productId}`)
}
