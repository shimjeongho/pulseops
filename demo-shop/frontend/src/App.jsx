import { Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout.jsx'
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
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default App
