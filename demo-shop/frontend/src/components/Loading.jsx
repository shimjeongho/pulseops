export function Loading({ label = '불러오는 중입니다...' }) {
  return (
    <p className="loading" role="status">
      {label}
    </p>
  )
}
