import { Link } from 'react-router-dom'

export function Header() {
  return (
    <header className="header">
      <div className="header-inner">
        <Link to="/" className="brand">
          <span className="brand-name">PulseOps</span>
          <span className="brand-tagline">Service Monitoring Dashboard</span>
        </Link>
      </div>
    </header>
  )
}
