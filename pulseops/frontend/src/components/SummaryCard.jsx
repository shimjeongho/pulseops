export function SummaryCard({ label, value, tone = 'neutral' }) {
  return (
    <div className={`summary-card summary-card-${tone}`}>
      <p className="summary-label">{label}</p>
      <p className="summary-value">{value}</p>
    </div>
  )
}
