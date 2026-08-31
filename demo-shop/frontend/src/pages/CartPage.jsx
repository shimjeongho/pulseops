import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { createOrder } from '../api/orders.js'
import { EmptyState } from '../components/EmptyState.jsx'
import { ErrorMessage } from '../components/ErrorMessage.jsx'
import { useCart } from '../features/cart/cartContext.js'
import { formatPrice } from '../format.js'

export function CartPage() {
  const { items, setQuantity, removeItem, clear, estimatedTotal } = useCart()
  const navigate = useNavigate()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)

  async function handleSubmit() {
    setSubmitting(true)
    setError(null)
    try {
      const order = await createOrder(items)
      // 서버에 주문이 생성된 뒤에만 장바구니를 비운다
      clear()
      navigate(`/orders/${order.id}`)
    } catch (failure) {
      // 거부된 부분을 사용자가 수정할 수 있도록 장바구니를 유지한다
      setError(failure)
      setSubmitting(false)
    }
  }

  if (items.length === 0) {
    return (
      <>
        <h1 className="page-title">장바구니</h1>
        <EmptyState title="장바구니가 비어 있습니다." description="상품을 담아 주문해 보세요.">
          <Link to="/" className="button button-primary">
            상품 보러 가기
          </Link>
        </EmptyState>
      </>
    )
  }

  return (
    <>
      <h1 className="page-title">장바구니</h1>

      <ErrorMessage error={error} />

      <table className="table">
        <thead>
          <tr>
            <th scope="col">상품</th>
            <th scope="col" className="align-right">
              가격
            </th>
            <th scope="col" className="align-right">
              수량
            </th>
            <th scope="col" className="align-right">
              합계
            </th>
            <th scope="col">
              <span className="visually-hidden">삭제</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.productId}>
              <td>
                <Link to={`/products/${item.productId}`}>{item.name}</Link>
              </td>
              <td className="align-right">{formatPrice(item.price)}</td>
              <td className="align-right">
                <label className="visually-hidden" htmlFor={`quantity-${item.productId}`}>
                  {item.name} 수량
                </label>
                <input
                  id={`quantity-${item.productId}`}
                  type="number"
                  className="quantity-input"
                  min="1"
                  value={item.quantity}
                  disabled={submitting}
                  onChange={(event) => {
                    const next = Number.parseInt(event.target.value, 10)
                    if (!Number.isNaN(next) && next >= 1) {
                      setQuantity(item.productId, next)
                    }
                  }}
                />
              </td>
              <td className="align-right">{formatPrice(item.price * item.quantity)}</td>
              <td className="align-right">
                <button
                  type="button"
                  className="button button-quiet"
                  disabled={submitting}
                  onClick={() => removeItem(item.productId)}
                >
                  삭제
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="cart-summary">
        <p className="cart-total">
          예상 결제 금액 <strong>{formatPrice(estimatedTotal)}</strong>
        </p>
        <p className="cart-note">최종 주문 금액은 주문 시점의 상품 가격으로 서버가 계산합니다.</p>
        <div className="button-row">
          <button type="button" className="button button-quiet" disabled={submitting} onClick={clear}>
            장바구니 비우기
          </button>
          <button
            type="button"
            className="button button-primary"
            disabled={submitting}
            onClick={handleSubmit}
          >
            {submitting ? '주문하는 중...' : '주문하기'}
          </button>
        </div>
      </div>
    </>
  )
}
