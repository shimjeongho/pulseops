export function DetailField({ label, children }) {
  return (
    <div className="detail-field">
      <dt className="detail-label">{label}</dt>
      <dd className="detail-value">{children}</dd>
    </div>
  )
}
