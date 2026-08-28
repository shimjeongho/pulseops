const STORAGE_KEY = 'demo-shop-cart'

/**
 * The cart lives only in this browser. What it stores is display data — the name and
 * price the shopper saw — and never the basis for a business decision: the backend
 * re-prices the order when it is placed.
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
    // anything hand-edited or left by an older version is discarded rather than trusted
    return Array.isArray(parsed) ? parsed.filter(isUsableItem) : []
  } catch {
    return []
  }
}

export function writeCart(items) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items))
  } catch {
    // a full or blocked storage must not break the checkout in progress
  }
}
