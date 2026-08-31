import { useCallback, useEffect, useMemo, useState } from 'react'
import { CartContext } from './cartContext.js'
import { readCart, writeCart } from './cartStorage.js'

export function CartProvider({ children }) {
  const [items, setItems] = useState(readCart)

  useEffect(() => {
    writeCart(items)
  }, [items])

  /**
   * 장바구니에 담는다. 이미 같은 상품이 있으면 해당 항목에 수량을 합친다. 상품당 항목을 하나로
   * 유지해야 주문 요청도 유효하다. 백엔드는 같은 상품을 두 번 포함한 요청을 거부한다.
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
      // 화면 표시용 예상 금액이며 실제 주문 총액은 백엔드가 계산해 내려준다
      estimatedTotal: items.reduce((sum, item) => sum + item.price * item.quantity, 0),
    }
  }, [items, addItem, setQuantity, removeItem, clear])

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}
