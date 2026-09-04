import { formatCount } from '../format.js'

/**
 * lag을 계산한 두 offset을 함께 보여 준다. 숫자 하나만 두면 그것이 어떤 두 지점 사이의 거리인지 화면에서
 * 확인할 방법이 없다.
 *
 * 아직 읽은 적 없는 partition의 committed offset과 lag은 빈 값으로 남긴다. 0으로 채우면 Consumer가 붙지도
 * 않은 구간이 가장 건강한 구간으로 보인다. 그런 partition이 몇 개인지는 표 위에서 따로 알린다.
 */
export function ConsumerLagTable({ partitions }) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col" className="numeric">
              Partition
            </th>
            <th scope="col" className="numeric">
              Log End Offset
            </th>
            <th scope="col" className="numeric">
              Committed Offset
            </th>
            <th scope="col" className="numeric">
              Lag
            </th>
          </tr>
        </thead>
        <tbody>
          {partitions.map((partition) => (
            <tr key={partition.partition}>
              {/* partition 번호는 세는 값이 아니라 이름이다. 천 단위 구분 기호를 붙이지 않는다. */}
              <td className="numeric">{partition.partition}</td>
              <td className="numeric">{formatCount(partition.logEndOffset)}</td>
              <td className="numeric">{formatCount(partition.committedOffset)}</td>
              <td className={partition.lag > 0 ? 'numeric numeric-alert' : 'numeric'}>
                {formatCount(partition.lag)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
