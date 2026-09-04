import { Timestamp } from './Timestamp.jsx'
import { confidenceLabel } from '../format.js'

/**
 * AI 분석 하나를 그린다.
 *
 * 관측에서 온 것과 모델이 지어낸 것을 자리로 나눠 보여 준다. 한 덩어리로 두면 같은 무게로 읽힌다.
 *
 * 기본 분석과 코드 컨텍스트 기반 분석이 같은 Component를 쓴다. 두 분석은 근거가 다를 뿐 결과의 모양이
 * 같고, 그리는 코드를 두 벌로 두면 한쪽만 고쳐졌을 때 같은 성격의 값이 화면마다 다르게 보인다. 무엇을
 * 근거로 만든 분석인지는 이 Component가 아니라 이것을 감싸는 자리가 설명한다.
 */
export function AiAnalysisResult({ analysis }) {
  return (
    <>
      <p className="analysis-summary">{analysis.summary}</p>

      <div className="analysis-hypothesis">
        <h3 className="analysis-subtitle">가능한 원인 가설</h3>
        <p className="analysis-text">{analysis.primaryHypothesis}</p>
        <p className="analysis-confidence">근거 신뢰도 {confidenceLabel(analysis.confidence)}</p>
      </div>

      <AnalysisList title="근거" items={analysis.evidence} />
      <AnalysisList title="확인할 항목" items={analysis.recommendedActions} />
      <AnalysisList title="분석의 한계" items={analysis.limitations} />

      <p className="analysis-origin">
        <Timestamp value={analysis.generatedAt} /> · {analysis.model}
      </p>
    </>
  )
}

function AnalysisList({ title, items }) {
  return (
    <div className="analysis-block">
      <h3 className="analysis-subtitle">{title}</h3>
      <ul className="analysis-list">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  )
}
