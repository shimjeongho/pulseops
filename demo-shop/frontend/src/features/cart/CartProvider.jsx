import { useCallback, useEffect, useMemo, useState } from 'react'
import { CartContext } from './cartContext.js'
import { readCart, writeCart } from './cartStorage.js'

export function CartProvider({ children }) {
  const [items, setItems] = useState(readCart)

  useEffect(() => {
    writeCart(items)
  }, [items])

  /**
   * Adds to the cart, merging into the line that already holds this product. One line
   * per product also keeps the order request valid: the backend rejects a request that
   * names the same product twice.
   */
  const addItem = useCallback((product, quantity) => {
    setItems((current) => {
      const existing = current.find((item) => item.productId === product.id)
      if (existing) {
        return current.map((item) =>
          item.productId === product.id ? { ...item, quantity: item.quantity + quantity } : item,
        )
      }
      return [
        ...current,
        { productId: product.id, name: product.name, price: product.price, quantity },
      ]
    })
  }, [])

  const setQuantity = useCallback((productId, quantity) => {
    setItems((current) =>
      current.map((item) => (item.productId === productId ? { ...item, quantity } : item)),
    )
  }, [])

  const removeItem = useCallback((productId) => {
    setItems((current) => current.filter((item) => item.productId !== productId))
  }, [])

  const clear = useCallback(() => setItems([]), [])

  const value = useMemo(() => {
    return {
      items,
      addItem,
      setQuantity,
      removeItem,
      clear,
      totalQuantity: items.reduce((sum, item) => sum + item.quantity, 0),
      // shown as an estimate only; the order's real total comes back from the backend
      estimatedTotal: items.reduce((sum, item) => sum + item.price * item.quantity, 0),
    }
  }, [items, addItem, setQuantity, removeItem, clear])

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}
