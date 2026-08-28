import { Link, NavLink } from 'react-router-dom'
import { useCart } from '../features/cart/cartContext.js'

function navClass({ isActive }) {
  return isActive ? 'nav-link nav-link-active' : 'nav-link'
}

export function Header() {
  const { totalQuantity } = useCart()

  return (
    <header className="header">
      <div className="header-inner">
        <Link to="/" className="brand">
          Demo Shop
        </Link>
        <nav className="nav" aria-label="주요 메뉴">
          <NavLink to="/" end className={navClass}>
            상품
          </NavLink>
          <NavLink to="/cart" className={navClass}>
            장바구니 ({totalQuantity})
          </NavLink>
          <NavLink to="/orders" className={navClass}>
            주문내역
          </NavLink>
        </nav>
      </div>
    </header>
  )
}
