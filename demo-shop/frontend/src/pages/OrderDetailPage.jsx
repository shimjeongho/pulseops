import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getOrder } from '../api/orders.js'
import { createPayment } from '../api/payments.js'
import { useApiData } from '../api/useApiData.js'
import { ErrorMessage } from '../components/ErrorMessage.jsx'
import { Loading } from '../components/Loading.jsx'
import { OrderStatusBadge } from './OrderStatusBadge.jsx'
import { formatDateTime, formatPrice } from '../format.js'

export function OrderDetailPage() {
  const { id } = useParams()
  const load = useCallback(() => getOrder(id), [id])
  const { data: order, error, loading, reload } = useApiData(load, [id])

  const [paying, setPaying] = useState(false)
  const [paymentError, setPaymentError] = useState(null)
  const [paidNumber, setPaidNumber] = useState(null)

  async function handlePay() {
    setPaying(true)
    setPaymentError(null)
    try {
      const payment = await createPayment(order.id)
      setPaidNumber(payment.paymentNumber)
    } catch (failure) {
      setPaymentError(failure)
    } finally {
      // whether it worked or not, the screen is refreshed from the server rather than
      // guessed at — a rejected payment must never leave the order looking paid
      setPaying(false)
      reload()
    }
  }

  if (loading) {
    return <Loading label="주문을 불러오는 중입니다..." />
  }
  if (error) {
    return (
      <ErrorMessage error={error}>
        <Link to="/orders" className="button button-quiet">
          주문내역으로
        </Link>
      </ErrorMessage>
    )
  }

  const payable = order.status === 'PENDING_PAYMENT'

  return (
    <>
      <p className="breadcrumb">
        <Link to="/orders">← 주문내역</Link>
      </p>

      <h1 className="page-title">주문 상세</h1>

      <div className="panel">
        <div className="order-detail-head">
          <span className="order-number">{order.orderNumber}</span>
          <OrderStatusBadge status={order.status} />
        </div>

        <dl className="detail-list">
          <div>
            <dt>주문 일시</dt>
            <dd>{formatDateTime(order.createdAt)}</dd>
          </div>
          <div>
            <dt>주문 금액</dt>
            <dd>{formatPrice(order.totalAmount)}</dd>
          </div>
        </dl>
      </div>

      <h2 className="section-title">주문 상품</h2>
      <table className="table">
        <thead>
          <tr>
            <th scope="col">상품</th>
            <th scope="col" className="align-right">
              주문 당시 가격
            </th>
            <th scope="col" className="align-right">
              수량
            </th>
            <th scope="col" className="align-right">
              합계
            </th>
          </tr>
        </thead>
        <tbody>
          {order.items.map((item) => (
            <tr key={item.productId}>
              <td>{item.productName}</td>
              <td className="align-right">{formatPrice(item.unitPrice)}</td>
              <td className="align-right">{item.quantity}</td>
              <td className="align-right">{formatPrice(item.subtotal)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan="3" className="align-right">
              합계
            </th>
            <td className="align-right">
              <strong>{formatPrice(order.totalAmount)}</strong>
            </td>
          </tr>
        </tfoot>
      </table>

      <h2 className="section-title">결제</h2>

      {paidNumber && order.status === 'PAID' ? (
        <p className="notice notice-success" role="status">
          결제가 완료되었습니다. 결제번호 {paidNumber}
        </p>
      ) : null}

      <ErrorMessage error={paymentError} />

      {payable ? (
        <div className="panel">
          <p className="cart-note">
            결제 금액은 이 주문의 금액인 {formatPrice(order.totalAmount)}입니다.
          </p>
          <button
            type="button"
            className="button button-primary"
            onClick={handlePay}
            disabled={paying}
          >
            {paying ? '결제하는 중...' : '결제하기'}
          </button>
        </div>
      ) : (
        <p className="notice notice-success">결제가 완료된 주문입니다.</p>
      )}
    </>
  )
}
