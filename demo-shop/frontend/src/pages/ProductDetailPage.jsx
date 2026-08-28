import { useCallback, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getProduct } from '../api/products.js'
import { useApiData } from '../api/useApiData.js'
import { ErrorMessage } from '../components/ErrorMessage.jsx'
import { Loading } from '../components/Loading.jsx'
import { useCart } from '../features/cart/cartContext.js'
import { formatPrice, productStatusLabel } from '../format.js'

export function ProductDetailPage() {
  const { id } = useParams()
  const load = useCallback(() => getProduct(id), [id])
  const { data: product, error, loading } = useApiData(load, [id])

  if (loading) {
    return <Loading label="상품을 불러오는 중입니다..." />
  }
  if (error) {
    return (
      <ErrorMessage error={error}>
        <Link to="/" className="button button-quiet">
          상품 목록으로
        </Link>
      </ErrorMessage>
    )
  }
  return <ProductDetail product={product} />
}

function ProductDetail({ product }) {
  const { addItem } = useCart()
  const [quantity, setQuantity] = useState(1)
  const [addedQuantity, setAddedQuantity] = useState(0)

  const soldOut = product.stockQuantity === 0
  const inactive = product.status !== 'ACTIVE'
  const purchasable = !soldOut && !inactive

  // the shop stops the shopper going past what is on the shelf, but the stock that
  // counts is the one payment checks: this is a convenience, not the rule
  const maxQuantity = Math.max(product.stockQuantity, 1)

  function handleQuantityChange(event) {
    const next = Number.parseInt(event.target.value, 10)
    if (Number.isNaN(next)) {
      return
    }
    setQuantity(Math.min(Math.max(next, 1), maxQuantity))
  }

  function handleAddToCart() {
    addItem(product, quantity)
    setAddedQuantity(quantity)
  }

  return (
    <>
      <p className="breadcrumb">
        <Link to="/">← 상품 목록</Link>
      </p>

      <h1 className="page-title">{product.name}</h1>

      <div className="panel">
        <p className="product-detail-price">{formatPrice(product.price)}</p>
        <p className="product-detail-description">{product.description}</p>

        <dl className="detail-list">
          <div>
            <dt>재고</dt>
            <dd>{product.stockQuantity}개</dd>
          </div>
          <div>
            <dt>판매 상태</dt>
            <dd>{productStatusLabel(product.status)}</dd>
          </div>
        </dl>

        {inactive ? <p className="notice notice-warning">판매하지 않는 상품입니다.</p> : null}
        {!inactive && soldOut ? <p className="notice notice-warning">품절된 상품입니다.</p> : null}

        <div className="quantity-row">
          <label htmlFor="quantity">수량</label>
          <input
            id="quantity"
            type="number"
            className="quantity-input"
            min="1"
            max={maxQuantity}
            value={quantity}
            onChange={handleQuantityChange}
            disabled={!purchasable}
          />
          <button
            type="button"
            className="button button-primary"
            onClick={handleAddToCart}
            disabled={!purchasable}
          >
            장바구니에 담기
          </button>
        </div>

        {addedQuantity > 0 ? (
          <p className="notice notice-success" role="status">
            장바구니에 {addedQuantity}개를 담았습니다. <Link to="/cart">장바구니 보기</Link>
          </p>
        ) : null}
      </div>
    </>
  )
}
