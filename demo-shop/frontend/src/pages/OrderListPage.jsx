import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { getOrders } from '../api/orders.js'
import { useApiData } from '../api/useApiData.js'
import { EmptyState } from '../components/EmptyState.jsx'
import { ErrorMessage } from '../components/ErrorMessage.jsx'
import { Loading } from '../components/Loading.jsx'
import { OrderStatusBadge } from './OrderStatusBadge.jsx'
import { formatDateTime, formatPrice } from '../format.js'

export function OrderListPage() {
  const load = useCallback(() => getOrders(), [])
  const { data: orders, error, loading } = useApiData(load, [])

  if (loading) {
    return <Loading label="주문 내역을 불러오는 중입니다..." />
  }
  if (error) {
    return <ErrorMessage error={error} />
  }
  if (orders.length === 0) {
    return (
      <>
        <h1 className="page-title">주문내역</h1>
        <EmptyState title="주문 내역이 없습니다." description="상품을 주문하면 여기에 표시됩니다.">
          <Link to="/" className="button button-primary">
            상품 보러 가기
          </Link>
        </EmptyState>
      </>
    )
  }

  // 백엔드는 생성 순서로 내려주므로 최신 주문이 위로 오도록 뒤집는다
  const newestFirst = [...orders].reverse()

  return (
    <>
      <h1 className="page-title">주문내역</h1>
      <ul className="order-list">
        {newestFirst.map((order) => (
          <li key={order.id}>
            <Link to={`/orders/${order.id}`} className="order-card">
              <span className="order-card-head">
                <span className="order-number">{order.orderNumber}</span>
                <OrderStatusBadge status={order.status} />
              </span>
              <span className="order-card-meta">
                <span>{formatDateTime(order.createdAt)}</span>
                <span>상품 {order.items.length}종</span>
              </span>
              <span className="order-card-total">{formatPrice(order.totalAmount)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}
