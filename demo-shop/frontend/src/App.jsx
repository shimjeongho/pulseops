import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout.jsx'
import { FaultLabPage } from './dev/FaultLabPage.jsx'
import { CartPage } from './pages/CartPage.jsx'
import { NotFoundPage } from './pages/NotFoundPage.jsx'
import { OrderDetailPage } from './pages/OrderDetailPage.jsx'
import { OrderListPage } from './pages/OrderListPage.jsx'
import { ProductDetailPage } from './pages/ProductDetailPage.jsx'
import { ProductListPage } from './pages/ProductListPage.jsx'

function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<ProductListPage />} />
        <Route path="/products/:id" element={<ProductDetailPage />} />
        <Route path="/cart" element={<CartPage />} />
        <Route path="/orders" element={<OrderListPage />} />
        <Route path="/orders/:id" element={<OrderDetailPage />} />
        {/* 장애 주입은 개발 및 Demo 전용이므로 개발 환경에서만 Route를 연다. Shop의 메뉴에는
            넣지 않으며, Production build에서는 이 Route 자체가 만들어지지 않는다. */}
        {import.meta.env.DEV ? <Route path="/dev/faults" element={<FaultLabPage />} /> : null}
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default App
