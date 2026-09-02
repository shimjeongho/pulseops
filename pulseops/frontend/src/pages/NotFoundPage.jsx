import { Link } from 'react-router-dom'
import { EmptyState } from '../components/EmptyState.jsx'

export function NotFoundPage() {
  return (
    <EmptyState title="페이지를 찾을 수 없습니다." description="주소를 다시 확인해 주세요.">
      <Link to="/" className="button">
        Dashboard로
      </Link>
    </EmptyState>
  )
}
