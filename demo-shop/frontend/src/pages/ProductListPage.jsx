import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { getProducts } from '../api/products.js'
import { useApiData } from '../api/useApiData.js'
import { EmptyState } from '../components/EmptyState.jsx'
import { ErrorMessage } from '../components/ErrorMessage.jsx'
import { Loading } from '../components/Loading.jsx'
import { formatPrice, productStatusLabel } from '../format.js'

function StockBadge({ product }) {
  if (product.status !== 'ACTIVE') {
    return <span className="badge badge-muted">{productStatusLabel(product.status)}</span>
  }
  if (product.stockQuantity === 0) {
    return <span className="badge badge-warning">품절</span>
  }
  return <span className="badge badge-ok">판매중</span>
}

export function ProductListPage() {
  const load = useCallback(() => getProducts(), [])
  const { data: products, error, loading } = useApiData(load, [])

  if (loading) {
    return <Loading label="상품을 불러오는 중입니다..." />
  }
  if (error) {
    return <ErrorMessage error={error} />
  }
  if (products.length === 0) {
    return <EmptyState title="판매 중인 상품이 없습니다." />
  }

  return (
    <>
      <h1 className="page-title">상품</h1>
      <ul className="product-grid">
        {products.map((product) => (
          <li key={product.id}>
            <Link to={`/products/${product.id}`} className="product-card">
              <span className="product-name">{product.name}</span>
              <span className="product-price">{formatPrice(product.price)}</span>
              <span className="product-meta">
                <StockBadge product={product} />
                <span className="product-stock">재고 {product.stockQuantity}개</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </>
  )
}
